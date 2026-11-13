package com.carddemo.inquiries;

import com.carddemo.accounts.Account;
import com.carddemo.accounts.AccountRepository;
import com.carddemo.mq.MessageProgram;
import com.carddemo.mq.MqMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;

/**
 * COACCT01. An INQA request whose account id is not zero reads ACCTDAT and replies on CARD.DEMO.REPLY.ACCT.
 * Anything else gets the invalid-parameters reply. A failed read is put on CARD.DEMO.ERROR.
 */
@Component
class AccountInquiry implements MessageProgram {

    static final String REPLY = "CARD.DEMO.REPLY.ACCT";
    static final String ERROR = "CARD.DEMO.ERROR";

    /** DFHRESP(IOERR). A failed read has no CICS RESP here, so the error text uses this value. */
    private static final int IOERR = 17;
    private static final String NEGATIVE = "}JKLMNOPQR";

    private final AccountRepository accounts;
    private final TransactionTemplate read;
    private final String requestQueue;

    AccountInquiry(AccountRepository accounts, PlatformTransactionManager transactions,
                   @Value("${carddemo.mq.account-request:CARD.DEMO.REQUEST.ACCT}") String requestQueue) {
        this.accounts = accounts;
        this.requestQueue = requestQueue;
        // The listener's transaction must still commit after a failed read, or the error put is never sent.
        // A select in that transaction aborts it on Postgres.
        this.read = new TransactionTemplate(transactions);
        this.read.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Override
    public String program() {
        return "COACCT01";
    }

    @Override
    public String requestQueue() {
        return requestQueue;
    }

    @Override
    public List<MqMessage.Put> process(MqMessage request) {
        return decide(request, requestQueue, id -> read.execute(status -> accounts.find(id)));
    }

    static List<MqMessage.Put> decide(MqMessage request, String requestQueue, LongFunction<Optional<Account>> accounts) {
        Request parsed = Request.parse(request.text());
        if (!parsed.lookup()) {
            return List.of(MqMessage.put(REPLY, invalid(parsed.function(), parsed.key()), request.correlationId()));
        }
        try {
            Optional<Account> found = accounts.apply(Long.parseLong(parsed.key()));
            String text = found.map(AccountInquiry::reply).orElseGet(() -> notFound(parsed.key()));
            return List.of(MqMessage.put(REPLY, text, request.correlationId()));
        } catch (DataAccessException e) {
            return List.of(MqMessage.put(ERROR, readError(requestQueue), request.correlationId()));
        }
    }

    record Request(String function, String key) {
        static Request parse(String text) {
            String body = text == null ? "" : text;
            String padded = body.length() >= 15 ? body : body + " ".repeat(15 - body.length());
            return new Request(padded.substring(0, 4), padded.substring(4, 15));
        }

        boolean lookup() {
            return function.equals("INQA") && key.chars().allMatch(Character::isDigit) && Long.parseLong(key) > 0;
        }
    }

    static String reply(Account account) {
        return "ACCOUNT ID : " + "%011d".formatted(account.id())
                + "ACCOUNT STATUS : " + fixed(account.activeStatus(), 1)
                + "BALANCE : " + zoned(account.currentBalance())
                + "CREDIT LIMIT : " + zoned(account.creditLimit())
                + "CASH LIMIT : " + zoned(account.cashCreditLimit())
                + "OPEN DATE : " + date(account.openDate())
                + "EXPR DATE : " + date(account.expirationDate())
                + "REIS DATE : " + date(account.reissueDate())
                + "CREDIT BAL : " + zoned(account.currentCycleCredit())
                + "DEBIT BAL : " + zoned(account.currentCycleDebit())
                + "GROUP ID : " + fixed(account.groupId(), 10);
    }

    static String invalid(String function, String key) {
        return "INVALID REQUEST PARAMETERS ACCT ID : " + key + "FUNCTION : " + function;
    }

    static String notFound(String key) {
        return "INVALID REQUEST PARAMETERS ACCT ID : " + key;
    }

    /**
     * MQ-ERR-DISPLAY for the READ failure. The return message is PIC X(25), so the literal loses its last three
     * characters. The paragraph name is spaces. The queue name is the input queue, PIC X(48).
     */
    static String readError(String queue) {
        return " ".repeat(27) + fixed("ERROR WHILE READING ACCTFILE", 25) + "  %02d  %05d  ".formatted(IOERR, 0)
                + fixed(queue, 48);
    }

    /**
     * PIC S9(10)V99 as zoned decimal, twelve digits and an implied point. A non-negative value keeps a digit in the
     * last byte, the zone Enterprise COBOL writes under the default NUMPROC(NOPFD). A negative value uses the
     * EBCDIC trailing overpunch.
     */
    static String zoned(BigDecimal value) {
        BigDecimal scaled = value.setScale(2, RoundingMode.DOWN);
        String digits = "%012d".formatted(scaled.abs().unscaledValue().longValue());
        if (scaled.signum() >= 0) {
            return digits;
        }
        return digits.substring(0, 11) + NEGATIVE.charAt(digits.charAt(11) - '0');
    }

    private static String date(LocalDate date) {
        return date == null ? " ".repeat(10) : date.toString();
    }

    private static String fixed(String value, int width) {
        String text = value == null ? "" : value;
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
    }
}
