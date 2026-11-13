package com.carddemo.authorizations;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;

import static java.util.Map.entry;

/**
 * COPAUS1C (CPVD): the authorization selected in COPAUS0C. PF8 moves to the next older one of the account and PF5
 * reports it as fraud, or clears the report, through COPAUS2C.
 */
@Component
class AuthorizationDetail implements ScreenProgram {

    /** WS-DECLINE-REASON-TABLE. */
    private static final Map<String, String> REASONS = Map.ofEntries(entry("0000", "APPROVED"),
            entry("3100", "INVALID CARD"), entry("4100", "INSUFFICNT FUND"), entry("4200", "CARD NOT ACTIVE"),
            entry("4300", "ACCOUNT CLOSED"), entry("4400", "EXCED DAILY LMT"), entry("5100", "CARD FRAUD"),
            entry("5200", "MERCHANT FRAUD"), entry("5300", "LOST CARD"), entry("9000", "UNKNOWN"));

    private final PendingAuthorizations pending;
    private final FraudReports fraudReports;
    private final TransactionTemplate transactions;

    AuthorizationDetail(PendingAuthorizations pending, FraudReports fraudReports, TransactionTemplate transactions) {
        this.pending = pending;
        this.fraudReports = fraudReports;
        this.transactions = transactions;
    }

    @Override
    public Program program() {
        return Program.COPAUS1C;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return show(selected(conversation), null);
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        return switch (aid) {
            case ENTER -> show(selected(conversation), null);
            case PF3 -> new Outcome.Transfer(Program.COPAUS0C);
            case PF5 -> markFraud(conversation);
            case PF8 -> next(conversation);
            default -> show(selected(conversation), Message.INVALID_KEY);
        };
    }

    private Optional<Authorization> selected(Conversation conversation) {
        return conversation.get(AuthorizationSelection.class).flatMap(s -> pending.find(s.accountId(), s.key()));
    }

    private Outcome next(Conversation conversation) {
        Optional<AuthorizationSelection> selection = conversation.get(AuthorizationSelection.class);
        Optional<Authorization> next = selection
                .flatMap(s -> pending.from(s.accountId(), s.key(), false, 1).stream().findFirst());
        if (next.isEmpty()) {
            return show(selected(conversation), Message.info("Already at the last Authorization..."));
        }
        AuthorizationSelection s = selection.get();
        conversation.put(new AuthorizationSelection(s.accountId(), s.customerId(), next.get().key()));
        return show(next, null);
    }

    /**
     * MARK-AUTH-FRAUD: F becomes R and anything else F, in AUTHFRDS and the segment together. A failure rolls both
     * back and shows the authorization as it still is, where the legacy screen showed the flag it failed to write.
     */
    private Outcome markFraud(Conversation conversation) {
        Optional<AuthorizationSelection> selection = conversation.get(AuthorizationSelection.class);
        Optional<Authorization> current = selected(conversation);
        if (current.isEmpty()) {
            return show(current, null);
        }
        Authorization a = current.get();
        String status = Authorization.FRAUD_CONFIRMED.equals(a.fraud()) ? Authorization.FRAUD_REMOVED
                : Authorization.FRAUD_CONFIRMED;
        Authorization toggled = a.withFraud(status, fraudReports.today());
        try {
            transactions.executeWithoutResult(tx -> {
                fraudReports.record(toggled, selection.get().accountId(), selection.get().customerId());
                pending.rewriteFraud(toggled);
            });
        } catch (DataAccessException e) {
            return show(current, Message.error(FraudReports.failure(e)));
        }
        return show(Optional.of(toggled), Message.success(Authorization.FRAUD_REMOVED.equals(status)
                ? "AUTH FRAUD REMOVED..." : "AUTH MARKED FRAUD..."));
    }

    private static Outcome.Show show(Optional<Authorization> authorization, Message message) {
        return new Outcome.Show(authorization.map(AuthorizationDetail::fields).orElse(Map.of()), message, "CARDNUM");
    }

    /** POPULATE-AUTH-DETAILS. */
    private static Map<String, String> fields(Authorization a) {
        AuthorizationRequest r = a.request();
        String expiry = Pictures.pad(r.cardExpiryDate(), 4);
        boolean reported = Authorization.FRAUD_CONFIRMED.equals(a.fraud()) || Authorization.FRAUD_REMOVED.equals(a.fraud());
        String reason = REASONS.containsKey(a.responseReason()) ? a.responseReason() + "-" + REASONS.get(a.responseReason())
                : "9999-ERROR";
        return Map.ofEntries(entry("CARDNUM", r.cardNumber()), entry("AUTHDT", Pictures.date(r.date())),
                entry("AUTHTM", Pictures.time(r.time())), entry("AUTHAMT", Pictures.minusAmount(a.approvedAmount(), 12)),
                entry("AUTHRSP", a.approved() ? "A" : "D"), entry("AUTHRSN", reason),
                entry("AUTHCD", "%06d".formatted(r.processingCode())), entry("POSEMD", "%02d".formatted(r.posEntryMode())),
                entry("AUTHSRC", r.messageSource()), entry("MCCCD", r.merchantCategoryCode()),
                entry("CRDEXP", expiry.substring(0, 2) + "/" + expiry.substring(2, 4)), entry("AUTHTYP", r.authType()),
                entry("TRNID", r.transactionId()), entry("AUTHMTC", a.matchStatus()),
                entry("AUTHFRD", reported ? a.fraud() + "-" + a.fraudReportDate() : "-"),
                entry("MERNAME", r.merchantName()), entry("MERID", r.merchantId()), entry("MERCITY", r.merchantCity()),
                entry("MERST", r.merchantState()), entry("MERZIP", r.merchantZip()));
    }
}
