package com.carddemo.authorizations;

import com.carddemo.accounts.Account;
import com.carddemo.accounts.AccountRepository;
import com.carddemo.accounts.CardXref;
import com.carddemo.accounts.CardXrefRepository;
import com.carddemo.accounts.CustomerRepository;
import com.carddemo.mq.MessageProgram;
import com.carddemo.mq.MqMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * COPAUA0C (CP00): decides each authorization request from the card's pending totals, or its account when it has
 * none, replies to the request's reply-to queue, and records the decision in DBPAUTP0. Every request starts from
 * nothing, where the legacy task carried flags and the reply buffer from one message to the next.
 */
@Component
class AuthorizationRequests implements MessageProgram {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationRequests.class);

    private static final String DECLINED = "05";
    private static final String APPROVED_REASON = "0000";
    private static final String NOT_FOUND = "3100";
    private static final String INSUFFICIENT_FUNDS = "4100";

    private final String requestQueue;
    private final CardXrefRepository xrefs;
    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final PendingAuthorizations pending;
    private final Clock clock;

    AuthorizationRequests(@Value("${carddemo.authorizations.request-queue:AWS.M2.CARDDEMO.PAUTH.REQUEST}")
                          String requestQueue, CardXrefRepository xrefs, AccountRepository accounts,
                          CustomerRepository customers, PendingAuthorizations pending, Clock clock) {
        this.requestQueue = requestQueue;
        this.xrefs = xrefs;
        this.accounts = accounts;
        this.customers = customers;
        this.pending = pending;
        this.clock = clock;
    }

    @Override
    public String program() {
        return "COPAUA0C";
    }

    @Override
    public String requestQueue() {
        return requestQueue;
    }

    private record Decision(String responseCode, String reason, BigDecimal approvedAmount) {

        boolean approved() {
            return Authorization.APPROVED.equals(responseCode);
        }
    }

    @Override
    public List<MqMessage.Put> process(MqMessage message) {
        AuthorizationRequest request = AuthorizationRequest.parse(message.text());
        if (message.replyTo() == null) {
            logError("M004", "FAILED TO PUT ON REPLY MQ", request.cardNumber());
            return List.of();
        }
        Optional<CardXref> xref = xrefs.find(request.cardNumber());
        if (xref.isEmpty()) {
            logError("A001", "CARD NOT FOUND IN XREF", request.cardNumber());
            Decision declined = new Decision(DECLINED, NOT_FOUND, new BigDecimal("0.00"));
            return List.of(reply(message, request, declined));
        }
        CardXref card = xref.get();
        Optional<Account> account = accounts.find(card.accountId());
        if (account.isEmpty()) {
            logError("A002", "ACCT NOT FOUND IN XREF", "%011d".formatted(card.accountId()));
        }
        boolean customerFound = customers.find(card.customerId()).isPresent();
        if (!customerFound) {
            logError("A003", "CUST NOT FOUND IN XREF", "%09d".formatted(card.customerId()));
        }
        Optional<AuthorizationSummary> summary = pending.summary(card.accountId());

        Decision decision = decide(request.amount(), summary, account, customerFound);
        record(card, request, decision, summary, account);
        return List.of(reply(message, request, decision));
    }

    /** 6000-MAKE-DECISION: a miss anywhere makes a decline a '3100', even when the amount is also over the limit. */
    private static Decision decide(BigDecimal amount, Optional<AuthorizationSummary> summary,
                                   Optional<Account> account, boolean customerFound) {
        Optional<BigDecimal> available = summary.map(AuthorizationSummary::available)
                .or(() -> account.map(a -> a.creditLimit().subtract(a.currentBalance())));
        if (available.isPresent() && amount.compareTo(available.get()) <= 0) {
            return new Decision(Authorization.APPROVED, APPROVED_REASON, amount);
        }
        boolean missing = account.isEmpty() || !customerFound;
        return new Decision(DECLINED, missing ? NOT_FOUND : INSUFFICIENT_FUNDS, new BigDecimal("0.00"));
    }

    /**
     * 8400-UPDATE-SUMMARY and 8500-INSERT-AUTH. The limits are refreshed from the account when it was read; the
     * declined total grows by the request's amount.
     */
    private void record(CardXref card, AuthorizationRequest request, Decision decision,
                        Optional<AuthorizationSummary> summary, Optional<Account> account) {
        AuthorizationSummary current = summary.orElseGet(() -> AuthorizationSummary.empty(card.accountId(), card.customerId()));
        if (account.isPresent()) {
            current = current.withLimits(account.get().creditLimit(), account.get().cashCreditLimit());
        }
        pending.save(decision.approved() ? current.approved(decision.approvedAmount()) : current.declined(request.amount()));
        pending.insert(new Authorization(card.accountId(), AuthorizationKey.at(LocalDateTime.now(clock)), request,
                request.time(), decision.responseCode(), decision.reason(), decision.approvedAmount(),
                decision.approved() ? "P" : "D", "", ""));
    }

    /**
     * 7100-SEND-RESPONSE: STRING DELIMITED BY SIZE of the CCPAURLY fields, each followed by a comma, the amount as
     * WS-APPROVED-AMT-DIS PIC -zzzzzzzzz9.99.
     */
    private static MqMessage.Put reply(MqMessage message, AuthorizationRequest request, Decision decision) {
        String text = String.join(",", Pictures.pad(request.cardNumber(), 16), Pictures.pad(request.transactionId(), 15),
                Pictures.pad(request.time(), 6), decision.responseCode(), decision.reason(),
                Pictures.minusAmount(decision.approvedAmount(), 14)) + ",";
        return MqMessage.put(message.replyTo(), text, message.correlationId());
    }

    /** 9500-LOG-ERROR wrote a CCPAUERY record to the CSSL transient data queue; nothing reads it back. */
    private static void logError(String location, String message, String key) {
        log.warn("COPAUA0C {} {} {}", location, message, key);
    }
}
