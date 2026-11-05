package com.carddemo.authorization;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.accounts.AccountRecord;
import com.carddemo.accounts.AccountStore;
import com.carddemo.accounts.CardXrefStore;
import com.carddemo.accounts.CustomerRecord;
import com.carddemo.accounts.CustomerStore;
import com.carddemo.accounts.Read;
import com.carddemo.accounts.XrefRecord;
import com.carddemo.cics.Syncpoint;
import com.carddemo.cics.TransientData;
import com.carddemo.dli.AuthKey;
import com.carddemo.dli.Dbpautp0;
import com.carddemo.dli.DliStatus;
import com.carddemo.dli.Get;
import com.carddemo.dli.Pcb;
import com.carddemo.dli.PendingAuthDetail;
import com.carddemo.dli.PendingAuthSummary;
import com.carddemo.dli.Ssa;
import com.carddemo.mq.GetOptions;
import com.carddemo.mq.Got;
import com.carddemo.mq.Message;
import com.carddemo.mq.MessageQueues;
import com.carddemo.mq.Opened;
import com.carddemo.mq.QueueHandle;
import com.carddemo.mq.Reason;
import com.carddemo.mq.Trigger;
import com.carddemo.mq.TriggeredProgram;

/**
 * COPAUA0C, transid CP00: decides the card authorization requests on the queue that triggered it. Each request is
 * a comma-separated line; the reply goes to the request's reply-to queue and the decision is recorded in the
 * account's DBPAUTP0 summary and as a new detail segment, with a SYNCPOINT after every request. Warnings and
 * failures are logged to the CSSL transient data queue; a critical one ends the task, keeping what it committed.
 */
@Component
public final class AuthorizationProcessor implements TriggeredProgram {

    static final String TRANSID = "CP00";
    private static final String PROGRAM = "COPAUA0C";
    private static final int PROCESS_LIMIT = 500;
    private static final long WAIT_MILLIS = 5000;
    private static final int PUT_BUFFER = 200;

    private final MessageQueues queues;
    private final CardXrefStore xrefs;
    private final AccountStore accounts;
    private final CustomerStore customers;
    private final Dbpautp0 database;
    private final Syncpoint syncpoint;
    private final TransientData transientData;
    private final Clock clock;

    AuthorizationProcessor(MessageQueues queues, CardXrefStore xrefs, AccountStore accounts, CustomerStore customers,
            Dbpautp0 database, Syncpoint syncpoint, TransientData transientData, Clock clock) {
        this.queues = queues;
        this.xrefs = xrefs;
        this.accounts = accounts;
        this.customers = customers;
        this.database = database;
        this.syncpoint = syncpoint;
        this.transientData = transientData;
        this.clock = clock;
    }

    @Override
    public String transid() {
        return TRANSID;
    }

    @Override
    public String program() {
        return PROGRAM;
    }

    @Override
    public void run(Trigger trigger) {
        new Task(trigger).run();
    }

    /** 9990-END-ROUTINE: the task returns normally after a critical error, so its last unit of work commits. */
    private static final class Terminated extends RuntimeException {
        Terminated() {
            super(null, null, false, false);
        }
    }

    /** One task's WORKING-STORAGE: everything here keeps its value from one request to the next. */
    private final class Task {

        private final Trigger trigger;
        private final AuthorizationRequest request = new AuthorizationRequest();
        private final ErrorLog errorLog = new ErrorLog();
        private final byte[] putBuffer = new byte[PUT_BUFFER];
        private int respLength = 1;
        private char authRespFlag = '\0';

        private Optional<QueueHandle> requestQueue = Optional.empty();
        private boolean moreMessages = true;
        private int processed;
        private Message current;
        private Pcb pcb;

        private boolean approve;
        private boolean insufficientFund;
        private boolean cardFound;
        private boolean acctFound;
        private boolean custNotFound;
        private boolean summaryFound;

        private Optional<XrefRecord> xref = Optional.empty();
        private Optional<AccountRecord> account = Optional.empty();
        private Optional<PendingAuthSummary> summary = Optional.empty();
        private String detailCardNum = "\0".repeat(16);
        private BigDecimal detailTransactionAmt = BigDecimal.ZERO;

        private String replyCardNum;
        private String replyTransactionId;
        private String replyAuthIdCode;
        private String replyRespCode;
        private String replyRespReason;
        private BigDecimal approvedAmt = BigDecimal.ZERO;

        Task(Trigger trigger) {
            this.trigger = trigger;
        }

        void run() {
            try {
                initialize();
                mainProcess();
                terminate();
            } catch (Terminated end) {
                // the critical error already closed the request queue
            }
        }

        private void initialize() {
            openRequestQueue();
            readRequest();
        }

        private void openRequestQueue() {
            Opened opened = queues.open(trigger.queue());
            if (opened.reason() == Reason.NONE) {
                requestQueue = opened.handle();
            } else {
                errorLog.mqCodes(opened.reason());
                logError("M001", 'C', 'M', "REQ MQ OPEN ERROR");
            }
        }

        private void mainProcess() {
            while (moreMessages) {
                request.unstring(current.data());
                processAuth();
                processed++;
                syncpoint.commit();
                if (processed > PROCESS_LIMIT) {
                    return;
                }
                readRequest();
            }
        }

        private void readRequest() {
            Got got = queues.get(requestQueue.orElseThrow(), GetOptions.waitMillis(WAIT_MILLIS, false));
            if (got.ok()) {
                current = got.message().orElseThrow();
            } else if (got.reason() == Reason.NO_MSG_AVAILABLE) {
                moreMessages = false;
            } else {
                errorLog.mqCodes(got.reason());
                errorLog.eventKey(detailCardNum);
                logError("M003", 'C', 'C', "FAILED TO READ REQUEST MQ");
            }
        }

        @LegacyDefect(id = "COPAUA0C-summary-flag-leaks",
                goldens = {"auth-multiple-messages"},
                behavior = "the summary-found flag is set only by a summary read, which a card missing from the "
                        + "xref skips, so that request is decided against the previous request's summary and can "
                        + "be approved 00",
                fix = "reset the summary-found flag, and the other read flags, at the start of every request")
        private void processAuth() {
            approve = true;
            pcb = database.schedule();
            cardFound = true;
            acctFound = true;
            readXref();
            if (cardFound) {
                readAccount();
                readCustomer();
                readSummary();
            }
            makeDecision();
            sendResponse();
            if (cardFound) {
                updateSummary();
                insertAuth();
            }
        }

        private void readXref() {
            String cardNum = request.text(AuthorizationRequest.CARD_NUM);
            // card_xref keys never hold LOW-VALUES, and PostgreSQL text cannot carry them as a parameter
            Read<XrefRecord> read = cardNum.indexOf('\0') >= 0 ? new Read.Missing<>()
                    : xrefs.find(cardNum.stripTrailing());
            switch (read) {
                case Read.Found<XrefRecord> found -> {
                    xref = Optional.of(found.record());
                    cardFound = true;
                }
                case Read.Missing<XrefRecord> missing -> {
                    cardFound = false;
                    acctFound = false;
                    errorLog.eventKey(cardNum);
                    logError("A001", 'W', 'A', "CARD NOT FOUND IN XREF");
                }
                case Read.Failed<XrefRecord> failed -> {
                    errorLog.respCodes(failed.resp(), failed.resp2());
                    errorLog.eventKey(cardNum);
                    logError("C001", 'C', 'C', "FAILED TO READ XREF FILE");
                }
            }
        }

        private void readAccount() {
            long acctId = xref.orElseThrow().acctId();
            String key = "%011d".formatted(acctId);
            switch (accounts.find(acctId)) {
                case Read.Found<AccountRecord> found -> {
                    account = Optional.of(found.record());
                    acctFound = true;
                }
                case Read.Missing<AccountRecord> missing -> {
                    acctFound = false;
                    errorLog.eventKey(key);
                    logError("A002", 'W', 'A', "ACCT NOT FOUND IN XREF");
                }
                case Read.Failed<AccountRecord> failed -> {
                    errorLog.respCodes(failed.resp(), failed.resp2());
                    errorLog.eventKey(key);
                    logError("C002", 'C', 'C', "FAILED TO READ ACCT FILE");
                }
            }
        }

        private void readCustomer() {
            int custId = xref.orElseThrow().custId();
            String key = "%09d".formatted(custId);
            switch (customers.find(custId)) {
                case Read.Found<CustomerRecord> found -> custNotFound = false;
                case Read.Missing<CustomerRecord> missing -> {
                    custNotFound = true;
                    errorLog.eventKey(key);
                    logError("A003", 'W', 'A', "CUST NOT FOUND IN XREF");
                }
                case Read.Failed<CustomerRecord> failed -> {
                    errorLog.respCodes(failed.resp(), failed.resp2());
                    errorLog.eventKey(key);
                    logError("C003", 'C', 'C', "FAILED TO READ CUST FILE");
                }
            }
        }

        private void readSummary() {
            Get<PendingAuthSummary> get = pcb.gu(Ssa.summary(xref.orElseThrow().acctId()));
            switch (get.status()) {
                case OK -> {
                    summary = get.segment();
                    summaryFound = true;
                }
                case GE -> summaryFound = false;
                default -> {
                    errorLog.imsCode(get.status());
                    errorLog.eventKey(detailCardNum);
                    logError("I002", 'C', 'I', "IMS GET SUMMARY FAILED");
                }
            }
        }

        private void makeDecision() {
            replyCardNum = request.text(AuthorizationRequest.CARD_NUM);
            replyTransactionId = request.text(AuthorizationRequest.TRANSACTION_ID);
            replyAuthIdCode = request.text(AuthorizationRequest.AUTH_TIME);
            BigDecimal amount = request.transactionAmt();
            if (summaryFound) {
                PendingAuthSummary current = summary.orElseThrow();
                if (amount.compareTo(current.creditLimit().subtract(current.creditBalance())) > 0) {
                    approve = false;
                    insufficientFund = true;
                }
            } else if (acctFound) {
                AccountRecord current = account.orElseThrow();
                if (amount.compareTo(current.creditLimit().subtract(current.currBal())) > 0) {
                    approve = false;
                    insufficientFund = true;
                }
            } else {
                approve = false;
            }
            if (approve) {
                authRespFlag = 'A';
                replyRespCode = "00";
                approvedAmt = amount;
            } else {
                authRespFlag = 'D';
                replyRespCode = "05";
                approvedAmt = BigDecimal.ZERO.setScale(2);
            }
            replyRespReason = "0000";
            if (!approve) {
                if (!cardFound || !acctFound || custNotFound) {
                    replyRespReason = "3100";
                } else if (insufficientFund) {
                    replyRespReason = "4100";
                } else {
                    replyRespReason = "9000";
                }
            }
            appendReply(String.join(",", replyCardNum, replyTransactionId, replyAuthIdCode, replyRespCode,
                    replyRespReason, AuthPicture.signed(approvedAmt, 10)) + ",");
        }

        @LegacyDefect(id = "COPAUA0C-reply-length-never-reset",
                goldens = {"auth-approved", "auth-multiple-messages", "auth-malformed-request"},
                behavior = "the STRING pointer that becomes the reply length starts at 1 and is never reset, so "
                        + "every reply carries one more byte than its text, a low-value, and each later reply in "
                        + "the same task repeats every earlier reply before its own",
                fix = "clear the put buffer and set the pointer to 1 before each STRING, and send pointer - 1 bytes")
        private void appendReply(String reply) {
            for (int i = 0; i < reply.length() && respLength <= PUT_BUFFER; i++) {
                putBuffer[respLength - 1] = (byte) reply.charAt(i);
                respLength++;
            }
        }

        private void sendResponse() {
            byte[] data = Arrays.copyOf(putBuffer, respLength);
            if (respLength > PUT_BUFFER) {
                // the length runs one byte past W02-PUT-BUFFER, onto WS-AUTH-RESP-FLG that follows it
                data[PUT_BUFFER] = (byte) authRespFlag;
            }
            Message reply = new Message(data, Message.NONE, current.correlId(), "");
            Reason reason = queues.put1(current.replyToQ(), reply, false);
            if (reason != Reason.NONE) {
                errorLog.mqCodes(reason);
                errorLog.eventKey(detailCardNum);
                logError("M004", 'C', 'M', "FAILED TO PUT ON REPLY MQ");
            }
        }

        @LegacyDefect(id = "COPAUA0C-limits-overwritten-from-acctdata",
                goldens = {"auth-approved", "auth-declined-insufficient-funds", "auth-multiple-messages"},
                behavior = "every summary write copies the ACCTDATA credit and cash limits over the summary's own",
                fix = "keep the summary's limits, which the batch and the screens maintain")
        private void updateSummary() {
            XrefRecord card = xref.orElseThrow();
            PendingAuthSummary area = summaryFound ? summary.orElseThrow() : initialized(card);
            BigDecimal creditLimit = account.map(AccountRecord::creditLimit).orElse(BigDecimal.ZERO);
            BigDecimal cashLimit = account.map(AccountRecord::cashCreditLimit).orElse(BigDecimal.ZERO);
            PendingAuthSummary updated = approve
                    ? new PendingAuthSummary(area.acctId(), area.custId(), area.authStatus(), area.accountStatus(),
                            creditLimit, cashLimit, area.creditBalance().add(approvedAmt), BigDecimal.ZERO,
                            area.approvedAuthCnt() + 1, area.declinedAuthCnt(), area.approvedAuthAmt().add(approvedAmt),
                            area.declinedAuthAmt(), area.filler())
                    : new PendingAuthSummary(area.acctId(), area.custId(), area.authStatus(), area.accountStatus(),
                            creditLimit, cashLimit, area.creditBalance(), area.cashBalance(), area.approvedAuthCnt(),
                            area.declinedAuthCnt() + 1, area.approvedAuthAmt(),
                            area.declinedAuthAmt().add(declinedAmount()), area.filler());
            summary = Optional.of(updated);
            DliStatus status = summaryFound ? pcb.repl(updated) : pcb.isrt(updated);
            if (status != DliStatus.OK) {
                errorLog.imsCode(status);
                errorLog.eventKey(detailCardNum);
                logError("I003", 'C', 'I', "IMS UPDATE SUMRY FAILED");
            }
        }

        @LegacyDefect(id = "COPAUA0C-declined-amount-from-previous-detail",
                goldens = {"auth-declined-insufficient-funds"},
                behavior = "a decline adds PA-TRANSACTION-AMT of the detail I/O area to the declined total before "
                        + "this request's detail is built there, so it adds the previous request's amount in the "
                        + "task, zero for the first",
                fix = "add the request's transaction amount")
        private BigDecimal declinedAmount() {
            return detailTransactionAmt;
        }

        @LegacyDefect(id = "COPAUA0C-initialize-replacing-numeric",
                goldens = {"auth-no-summary"},
                behavior = "a new summary is built with INITIALIZE REPLACING NUMERIC DATA BY ZERO, which leaves "
                        + "the authorization and account status fields and the filler as the I/O area last held "
                        + "them, low-values in a fresh task",
                fix = "INITIALIZE the whole summary, so its text fields are spaces")
        private PendingAuthSummary initialized(XrefRecord card) {
            PendingAuthSummary area = summary.orElse(LOW_VALUES_SUMMARY);
            return new PendingAuthSummary(card.acctId(), card.custId(), area.authStatus(), area.accountStatus(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO,
                    BigDecimal.ZERO, area.filler());
        }

        private void insertAuth() {
            LocalDateTime now = LocalDateTime.now(clock);
            int yyddd = now.getYear() % 100 * 1000 + now.getDayOfYear();
            long timeWithMs = Long.parseLong(now.format(HHMMSS)) * 1000 + now.get(ChronoField.MILLI_OF_SECOND);
            AuthKey key = new AuthKey(99999 - yyddd, (int) (999999999 - timeWithMs));
            detailCardNum = request.text(AuthorizationRequest.CARD_NUM);
            detailTransactionAmt = request.transactionAmt();
            PendingAuthDetail detail = new PendingAuthDetail(key,
                    request.segmentText(AuthorizationRequest.AUTH_DATE),
                    request.segmentText(AuthorizationRequest.AUTH_TIME),
                    request.segmentText(AuthorizationRequest.CARD_NUM),
                    request.segmentText(AuthorizationRequest.AUTH_TYPE),
                    request.segmentText(AuthorizationRequest.CARD_EXPIRY_DATE),
                    request.segmentText(AuthorizationRequest.MESSAGE_TYPE),
                    request.segmentText(AuthorizationRequest.MESSAGE_SOURCE),
                    AuthorizationRequest.segmentText(replyAuthIdCode),
                    replyRespCode,
                    replyRespReason,
                    request.number(AuthorizationRequest.PROCESSING_CODE),
                    detailTransactionAmt,
                    approvedAmt,
                    request.segmentText(AuthorizationRequest.MERCHANT_CATAGORY_CODE),
                    request.segmentText(AuthorizationRequest.ACQR_COUNTRY_CODE),
                    request.number(AuthorizationRequest.POS_ENTRY_MODE),
                    request.segmentText(AuthorizationRequest.MERCHANT_ID),
                    request.segmentText(AuthorizationRequest.MERCHANT_NAME),
                    request.segmentText(AuthorizationRequest.MERCHANT_CITY),
                    request.segmentText(AuthorizationRequest.MERCHANT_STATE),
                    request.segmentText(AuthorizationRequest.MERCHANT_ZIP),
                    request.segmentText(AuthorizationRequest.TRANSACTION_ID),
                    approve ? "P" : "D",
                    "",
                    "",
                    "\0".repeat(17));
            DliStatus status = pcb.isrt(Ssa.summary(xref.orElseThrow().acctId()), detail);
            if (status != DliStatus.OK) {
                errorLog.imsCode(status);
                errorLog.eventKey(detailCardNum);
                logError("I004", 'C', 'I', "IMS INSERT DETL FAILED");
            }
        }

        private void terminate() {
            closeRequestQueue();
        }

        private void closeRequestQueue() {
            if (requestQueue.isEmpty()) {
                return;
            }
            Reason reason = queues.close(requestQueue.get());
            if (reason == Reason.NONE) {
                requestQueue = Optional.empty();
            } else {
                errorLog.mqCodes(reason);
                logError("M005", 'W', 'M', "FAILED TO CLOSE REQUEST MQ");
            }
        }

        private void logError(String location, char level, char subsystem, String message) {
            LocalDateTime now = LocalDateTime.now(clock);
            errorLog.record(now.format(YYMMDD), now.format(HHMMSS), location, level, subsystem, message);
            transientData.writeq("CSSL", errorLog.image());
            if (level == 'C') {
                terminate();
                throw new Terminated();
            }
        }
    }

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    private static final PendingAuthSummary LOW_VALUES_SUMMARY = new PendingAuthSummary(0, 0, "\0",
            List.of("\0\0", "\0\0", "\0\0", "\0\0", "\0\0"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, "\0".repeat(34));

    /**
     * CCPAUERY's ERROR-LOG-RECORD, 122 bytes. A field keeps what the last error moved into it until another moves
     * something else, as the codes and event key of a record in WORKING-STORAGE do.
     */
    private static final class ErrorLog {

        private final byte[] image = new byte[122];

        void record(String date, String time, String location, char level, char subsystem, String message) {
            put(0, 6, date);
            put(6, 6, time);
            put(12, 8, TRANSID);
            put(20, 8, PROGRAM);
            put(28, 4, location);
            put(32, 1, String.valueOf(level));
            put(33, 1, String.valueOf(subsystem));
            put(52, 50, message);
        }

        void mqCodes(Reason reason) {
            respCodes(reason.completionCode(), reason.code());
        }

        void respCodes(int code1, int code2) {
            put(34, 9, "%09d".formatted(code1));
            put(43, 9, "%09d".formatted(code2));
        }

        void imsCode(DliStatus status) {
            put(34, 9, status.code());
        }

        void eventKey(String key) {
            put(102, 20, key);
        }

        byte[] image() {
            return image.clone();
        }

        private void put(int offset, int length, String text) {
            String padded = AuthPicture.pad(text, length);
            for (int i = 0; i < length; i++) {
                image[offset + i] = (byte) padded.charAt(i);
            }
        }
    }
}
