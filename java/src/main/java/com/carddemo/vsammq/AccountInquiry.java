package com.carddemo.vsammq;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.carddemo.accounts.AccountRecord;
import com.carddemo.accounts.AccountStore;
import com.carddemo.accounts.Read;
import com.carddemo.cics.Syncpoint;
import com.carddemo.mq.MessageQueues;
import com.carddemo.records.Fields;

/**
 * COACCT01, transid CDRA: answers {@code INQA} and an 11-digit account id with the account's ACCTDAT fields on
 * CARD.DEMO.REPLY.ACCT. Amounts go out as zoned {@code S9(10)V99}, the sign overpunched on the last digit.
 */
@Component
public final class AccountInquiry extends MqInquiryProgram {

    static final String TRANSID = "CDRA";
    static final String REPLY_QUEUE = "CARD.DEMO.REPLY.ACCT";

    private final AccountStore accounts;

    AccountInquiry(MessageQueues queues, Syncpoint syncpoint, AccountStore accounts) {
        super(queues, syncpoint);
        this.accounts = accounts;
    }

    @Override
    public String transid() {
        return TRANSID;
    }

    @Override
    public String program() {
        return "COACCT01";
    }

    @Override
    String replyQueue() {
        return REPLY_QUEUE;
    }

    @Override
    Answer answer(byte[] request) {
        String function = new String(request, 0, 4, ISO_8859_1);
        String key = new String(request, 4, AccountKey.LENGTH, ISO_8859_1);
        if (!function.equals("INQA") || !AccountKey.positive(key)) {
            return new Answer.Reply("INVALID REQUEST PARAMETERS ACCT ID : " + key + "FUNCTION : " + function);
        }
        long acctId = AccountKey.recordId(key);
        Read<AccountRecord> read = acctId < 0 ? new Read.Missing<>() : accounts.find(acctId);
        return switch (read) {
            case Read.Found<AccountRecord> found -> new Answer.Reply(response(found.record()));
            case Read.Missing<AccountRecord> missing ->
                    new Answer.Reply("INVALID REQUEST PARAMETERS ACCT ID : " + key);
            case Read.Failed<AccountRecord> failed ->
                    new Answer.Failure(failed.resp(), failed.resp2(), "ERROR WHILE READING ACCTFILE");
        };
    }

    /** WS-ACCT-RESPONSE. */
    private static String response(AccountRecord account) {
        return "ACCOUNT ID : " + "%011d".formatted(account.id())
                + "ACCOUNT STATUS : " + pad(account.activeStatus(), 1)
                + "BALANCE : " + zoned(account.currBal())
                + "CREDIT LIMIT : " + zoned(account.creditLimit())
                + "CASH LIMIT : " + zoned(account.cashCreditLimit())
                + "OPEN DATE : " + date(account.openDate())
                + "EXPR DATE : " + date(account.expirationDate())
                + "REIS DATE : " + date(account.reissueDate())
                + "CREDIT BAL : " + zoned(account.currCycCredit())
                + "DEBIT BAL : " + zoned(account.currCycDebit())
                + "GROUP ID : " + pad(account.groupId(), 10);
    }

    private static String zoned(BigDecimal amount) {
        byte[] field = new byte[12];
        Fields.zoned(field, 0, 12, 2, true, amount);
        return new String(field, ISO_8859_1);
    }

    private static String date(LocalDate date) {
        return date == null ? pad("", 10) : date.toString();
    }
}
