package com.carddemo.authorization;

import static com.carddemo.online.maps.COPAU0A.ACCTID;
import static com.carddemo.online.maps.COPAU0A.ADDR001;
import static com.carddemo.online.maps.COPAU0A.ADDR002;
import static com.carddemo.online.maps.COPAU0A.APPRAMT;
import static com.carddemo.online.maps.COPAU0A.APPRCNT;
import static com.carddemo.online.maps.COPAU0A.CASHBAL;
import static com.carddemo.online.maps.COPAU0A.CASHLIM;
import static com.carddemo.online.maps.COPAU0A.CNAME;
import static com.carddemo.online.maps.COPAU0A.CREDBAL;
import static com.carddemo.online.maps.COPAU0A.CREDLIM;
import static com.carddemo.online.maps.COPAU0A.CUSTID;
import static com.carddemo.online.maps.COPAU0A.DECLAMT;
import static com.carddemo.online.maps.COPAU0A.DECLCNT;
import static com.carddemo.online.maps.COPAU0A.ERRMSG;
import static com.carddemo.online.maps.COPAU0A.PHONE1;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.accounts.AccountRecord;
import com.carddemo.accounts.AccountStore;
import com.carddemo.accounts.CardXrefStore;
import com.carddemo.accounts.CustomerRecord;
import com.carddemo.accounts.CustomerStore;
import com.carddemo.accounts.Read;
import com.carddemo.accounts.XrefRecord;
import com.carddemo.dli.AuthKey;
import com.carddemo.dli.Dbpautp0;
import com.carddemo.dli.Get;
import com.carddemo.dli.Pcb;
import com.carddemo.dli.PendingAuthDetail;
import com.carddemo.dli.PendingAuthSummary;
import com.carddemo.dli.Ssa;
import com.carddemo.menu.MainMenuScreen;
import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.CommonMessages;
import com.carddemo.online.FieldAttribute;
import com.carddemo.online.Key;
import com.carddemo.online.Outcome;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.ScreenState;
import com.carddemo.online.SendMode;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.COPAU0A;

/**
 * COPAUS0C, the pending authorizations of one account: its customer, the IMS summary's totals and five
 * authorizations a page, newest first. S on a row opens COPAUS1C on that authorization.
 */
@Component
public final class PendingAuthSummaryScreen
        extends Screen<COPAU0A, PendingAuthSummaryScreen.Action, PendingAuthSummaryScreen.State, PendingAuthContext> {

    public static final ScreenRef<PendingAuthContext> REF =
            ScreenRef.of("COPAUS0C", "CPVS", "pending-auth-summary", PendingAuthContext.class);

    enum Action implements ScreenAction {
        CONTINUE(Key.ENTER), BACK(Key.PF3), BACKWARD(Key.PF7), FORWARD(Key.PF8);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    /**
     * The COMMAREA COPAUS0C returns with.
     *
     * @param acctId CDEMO-ACCT-ID, {@code null} until an account id is entered
     * @param custId CDEMO-CUST-ID, set by the last card xref read
     */
    record State(Long acctId, int custId, AuthorizationPaging paging) implements ScreenState {}

    private record Row(COPAU0A sel, COPAU0A trnId, COPAU0A date, COPAU0A time, COPAU0A type, COPAU0A approval,
            COPAU0A status, COPAU0A amount) {}

    private static final List<Row> ROWS = IntStream.rangeClosed(1, AuthorizationPaging.ROWS)
            .mapToObj(n -> new Row(COPAU0A.valueOf("SEL%04d".formatted(n)), COPAU0A.valueOf("TRNID%02d".formatted(n)),
                    COPAU0A.valueOf("PDATE%02d".formatted(n)), COPAU0A.valueOf("PTIME%02d".formatted(n)),
                    COPAU0A.valueOf("PTYPE%02d".formatted(n)), COPAU0A.valueOf("PAPRV%02d".formatted(n)),
                    COPAU0A.valueOf("PSTAT%02d".formatted(n)), COPAU0A.valueOf("PAMT%03d".formatted(n))))
            .toList();

    private static final int RESP_NOTFND = 13;

    private final CardXrefStore xrefs;
    private final AccountStore accounts;
    private final CustomerStore customers;
    private final Dbpautp0 database;

    PendingAuthSummaryScreen(CardXrefStore xrefs, AccountStore accounts, CustomerStore customers, Dbpautp0 database) {
        super(REF, COPAU0A.MAP, Action.class, State.class, Access.SIGNED_ON);
        this.xrefs = xrefs;
        this.accounts = accounts;
        this.customers = customers;
        this.database = database;
    }

    /**
     * A transaction start has no COMMAREA and shows the empty map. An XCTL, from the menu or from COPAUS1C,
     * gathers the details of the account the COMMAREA names, if it names one.
     */
    @Override
    protected Outcome enter(Arrival<PendingAuthContext> arrival) {
        SymbolicMap<COPAU0A> map = SymbolicMap.lowValues(COPAU0A.MAP);
        if (arrival.from().isEmpty()) {
            return show(map.cursor(ACCTID), new State(null, 0, new AuthorizationPaging()));
        }
        Optional<PendingAuthContext> context = arrival.handoff();
        Task task = new Task(map, arrival.aid(), new State(context.map(PendingAuthContext::acctId).orElse(null),
                context.map(PendingAuthContext::custId).orElse(0),
                context.map(c -> c.paging().copy()).orElseGet(AuthorizationPaging::new)));
        if (task.acctId != null) {
            task.wsAcctId = "%011d".formatted(task.acctId);
            map.set(ACCTID, task.wsAcctId);
        } else {
            map.blank(ACCTID);
        }
        task.gatherDetails();
        return task.send();
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COPAU0A> map, State state) {
        Task task = new Task(map, action.key(), state);
        return switch (action) {
            case CONTINUE -> task.processEnterKey();
            case BACK -> transfer(MainMenuScreen.REF.program());
            case BACKWARD -> task.processPf7Key();
            case FORWARD -> task.processPf8Key();
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<COPAU0A> map, State state) {
        Task task = new Task(map, Key.ENTER, state);
        task.error = true;
        task.message.replace(CommonMessages.INVALID_KEY);
        return task.send();
    }

    /** {@code ACCTIDI IS NUMERIC} on the space-padded 11-character field. */
    static boolean numericAcctId(String raw) {
        return raw.length() == ACCTID.length() && raw.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** One run of the program: its working storage and the COMMAREA. */
    private final class Task {
        private final SymbolicMap<COPAU0A> map;
        private final Key aid;
        private final AuthorizationPaging paging;
        private Long acctId;
        private int custId;
        private String wsAcctId;
        private final WsMessage message = new WsMessage();
        private boolean error;
        private boolean endOfAuths;
        private boolean summaryFound;
        private SendMode mode = SendMode.ERASE_CURSOR;
        private Pcb pcb;
        private AuthKey keySave;

        Task(SymbolicMap<COPAU0A> map, Key aid, State state) {
            this.map = map.cursor(ACCTID);
            this.aid = aid;
            this.paging = state.paging().copy();
            this.acctId = state.acctId();
            this.custId = state.custId();
        }

        Outcome processEnterKey() {
            if (map.isBlank(ACCTID)) {
                wsAcctId = null;
                error = true;
                message.replace("Please enter Acct Id...");
            } else if (!numericAcctId(AuthPicture.pad(map.text(ACCTID), ACCTID.length()))) {
                wsAcctId = null;
                error = true;
                message.replace("Acct Id must be Numeric ...");
            } else {
                wsAcctId = map.text(ACCTID);
                acctId = Long.parseLong(wsAcctId);
                Optional<Integer> row = IntStream.range(0, ROWS.size()).boxed()
                        .filter(i -> !map.isBlank(ROWS.get(i).sel())).findFirst();
                paging.selFlag = row.map(i -> map.text(ROWS.get(i).sel())).orElse("");
                paging.selected = row.map(i -> paging.authKeys[i]).orElse(null);
                if (!paging.selFlag.isEmpty() && paging.selected != null) {
                    switch (paging.selFlag) {
                        case "S", "s" -> {
                            return transfer(PendingAuthDetailScreen.REF,
                                    new PendingAuthContext(acctId, custId, paging.copy()));
                        }
                        default -> message.replace("Invalid selection. Valid value is S");
                    }
                }
            }
            gatherDetails();
            if (wsAcctId == null) {
                map.blank(ACCTID);
            } else {
                map.set(ACCTID, wsAcctId);
            }
            return send();
        }

        Outcome processPf7Key() {
            if (paging.pageNum > 1) {
                paging.pageNum--;
                keySave = paging.prevPages[paging.pageNum - 1];
                getAuthSummary();
                mode = SendMode.CURSOR;
                paging.nextPage = true;
                initializeAuthData();
                processPageForward();
            } else {
                message.replace("You are already at the top of the page...");
                mode = SendMode.CURSOR;
            }
            return send();
        }

        Outcome processPf8Key() {
            if (paging.last == null) {
                keySave = null;
            } else {
                keySave = paging.last;
                getAuthSummary();
                repositionAuthorizations();
            }
            mode = SendMode.CURSOR;
            if (paging.nextPage) {
                initializeAuthData();
                processPageForward();
            } else {
                message.replace("You are already at the bottom of the page...");
            }
            return send();
        }

        void gatherDetails() {
            paging.pageNum = 0;
            if (wsAcctId != null) {
                gatherAccountDetails();
                initializeAuthData();
                if (summaryFound) {
                    processPageForward();
                }
            }
        }

        void processPageForward() {
            if (error) {
                return;
            }
            int idx = 1;
            paging.last = null;
            while (idx <= AuthorizationPaging.ROWS && !endOfAuths && !error) {
                Optional<PendingAuthDetail> detail = aid == Key.PF7 && idx == 1
                        ? repositionAuthorizations()
                        : getAuthorizations();
                if (!endOfAuths && !error) {
                    PendingAuthDetail auth = detail.orElseThrow();
                    populateAuthList(idx, auth);
                    idx++;
                    paging.last = auth.key();
                    if (idx == 2) {
                        paging.pageNum++;
                        paging.prevPages[paging.pageNum - 1] = auth.key();
                    }
                }
            }
            lookAhead();
        }

        @LegacyDefect(id = "COPAUS0C-pf8-past-last-page-empty-list",
                goldens = {"pending-auth-summary-paging"},
                behavior = "NEXT-PAGE-NO is set only when the page loop ends before the last authorization, so a "
                        + "last page that is not full leaves NEXT-PAGE-YES from the page before, and the next PF8 "
                        + "shows an empty list instead of 'You are already at the bottom of the page...'",
                fix = "set NEXT-PAGE-NO whenever the loop reaches the end of the authorizations")
        private void lookAhead() {
            if (!endOfAuths && !error) {
                getAuthorizations();
                paging.nextPage = !endOfAuths && !error;
            }
        }

        /** {@code GNP SEGMENT(PAUTDTL1)}. */
        private Optional<PendingAuthDetail> getAuthorizations() {
            return authorization(gnp(Ssa.detail()), " System error while reading AUTH Details: Code:");
        }

        /** {@code GNP SEGMENT(PAUTDTL1) WHERE (PAUT9CTS = WS-AUTH-KEY-SAVE)}. */
        private Optional<PendingAuthDetail> repositionAuthorizations() {
            Ssa.Detail ssa = keySave == null ? Ssa.detail() : Ssa.detail(keySave);
            return authorization(gnp(ssa), " System error while repos. AUTH Details: Code:");
        }

        /** A call before any SCHD in this task gets TH, PSB not scheduled. */
        private Optional<Get<PendingAuthDetail>> gnp(Ssa.Detail ssa) {
            return pcb == null ? Optional.empty() : Optional.of(pcb.gnp(ssa));
        }

        private Optional<PendingAuthDetail> authorization(Optional<Get<PendingAuthDetail>> got, String failure) {
            String status = got.map(g -> g.status().code()).orElse("TH");
            switch (status) {
                case "  " -> endOfAuths = false;
                case "GE", "GB" -> endOfAuths = true;
                default -> {
                    error = true;
                    message.overlay(failure + status);
                }
            }
            return got.flatMap(Get::segment);
        }

        private void populateAuthList(int idx, PendingAuthDetail auth) {
            Row row = ROWS.get(idx - 1);
            paging.authKeys[idx - 1] = auth.key();
            map.set(row.trnId(), auth.transactionId())
                    .set(row.date(), AuthPicture.monthDayYear(auth.authOrigDate()))
                    .set(row.time(), AuthPicture.hourMinuteSecond(auth.authOrigTime()))
                    .set(row.type(), auth.authType())
                    .set(row.approval(), "00".equals(auth.authRespCode()) ? "A" : "D")
                    .set(row.status(), auth.matchStatus())
                    .set(row.amount(), AuthPicture.signed(auth.approvedAmt(), 8))
                    .attribute(row.sel(), FieldAttribute.BMUNP);
        }

        private void initializeAuthData() {
            for (Row row : ROWS) {
                map.attribute(row.sel(), FieldAttribute.BMPRO)
                        .blank(row.trnId(), row.date(), row.time(), row.type(), row.approval(), row.status(),
                                row.amount());
            }
        }

        @LegacyDefect(id = "COPAUS0C-error-reads-continue",
                goldens = {"pending-auth-summary-account-not-found"},
                behavior = "each NOTFND of the xref, account and customer reads sends the screen and the next read "
                        + "still runs, every STRING overwriting the start of WS-MESSAGE without clearing it, so a "
                        + "missing account ends on 'Customer:          not found in CUST file' with the last "
                        + "character of the account message after it",
                fix = "stop at the first read that fails and clear WS-MESSAGE before each STRING")
        private void gatherAccountDetails() {
            Optional<XrefRecord> xref = getCardXrefByAcct();
            Optional<AccountRecord> account = getAcctDataByAcct(xref);
            Optional<CustomerRecord> customer = getCustDataByCust(xref);
            customer.ifPresent(this::showCustomer);
            showLimits(account);
            getAuthSummary().ifPresentOrElse(this::showSummary,
                    () -> map.set(APPRCNT, zeros(APPRCNT)).set(DECLCNT, zeros(DECLCNT)).set(CREDBAL, zeros(CREDBAL))
                            .set(CASHBAL, zeros(CASHBAL)).set(APPRAMT, zeros(APPRAMT)).set(DECLAMT, zeros(DECLAMT)));
        }

        private Optional<XrefRecord> getCardXrefByAcct() {
            return switch (xrefs.findByAccount(acctId)) {
                case Read.Found<XrefRecord> found -> {
                    custId = found.record().custId();
                    yield Optional.of(found.record());
                }
                case Read.Missing<XrefRecord> missing -> {
                    message.overlay(notFound("Account:" + wsAcctId, "XREF", RESP_NOTFND, 0));
                    yield Optional.empty();
                }
                case Read.Failed<XrefRecord> failed -> {
                    error = true;
                    message.overlay(systemError("Account:" + wsAcctId, "XREF", failed.resp(), failed.resp2()));
                    yield Optional.empty();
                }
            };
        }

        /** Without an xref record XREF-ACCT-ID holds no account, and the READ finds none. */
        private Optional<AccountRecord> getAcctDataByAcct(Optional<XrefRecord> xref) {
            String id = xref.map(x -> "%011d".formatted(x.acctId())).orElse(" ".repeat(11));
            Read<AccountRecord> read = xref.isPresent() ? accounts.find(xref.get().acctId()) : new Read.Missing<>();
            return switch (read) {
                case Read.Found<AccountRecord> found -> Optional.of(found.record());
                case Read.Missing<AccountRecord> missing -> {
                    message.overlay(notFound("Account:" + id, "ACCT", RESP_NOTFND, 0));
                    yield Optional.empty();
                }
                case Read.Failed<AccountRecord> failed -> {
                    error = true;
                    message.overlay(systemError("Account:" + id, "ACCT", failed.resp(), failed.resp2()));
                    yield Optional.empty();
                }
            };
        }

        private Optional<CustomerRecord> getCustDataByCust(Optional<XrefRecord> xref) {
            String id = xref.map(x -> "%09d".formatted(x.custId())).orElse(" ".repeat(9));
            Read<CustomerRecord> read = xref.isPresent() ? customers.find(xref.get().custId()) : new Read.Missing<>();
            return switch (read) {
                case Read.Found<CustomerRecord> found -> Optional.of(found.record());
                case Read.Missing<CustomerRecord> missing -> {
                    message.overlay(notFound("Customer:" + id, "CUST", RESP_NOTFND, 0));
                    yield Optional.empty();
                }
                case Read.Failed<CustomerRecord> failed -> {
                    error = true;
                    message.overlay(systemError("Customer:" + id, "CUST", failed.resp(), failed.resp2()));
                    yield Optional.empty();
                }
            };
        }

        private void showCustomer(CustomerRecord customer) {
            map.set(CUSTID, "%09d".formatted(customer.id()))
                    .set(CNAME, upToSpace(customer.firstName()) + " " + AuthPicture.pad(customer.middleName(), 1) + " "
                            + upToSpace(customer.lastName()))
                    .set(ADDR001, upToDoubleSpace(customer.addrLine1(), 50) + "," + upToDoubleSpace(customer.addrLine2(), 50))
                    .set(ADDR002, upToDoubleSpace(customer.addrLine3(), 50) + "," + AuthPicture.pad(customer.stateCd(), 2)
                            + "," + AuthPicture.pad(customer.addrZip(), 10).substring(0, 5))
                    .set(PHONE1, Objects.toString(customer.phoneNum1(), ""));
        }

        @LegacyDefect(id = "COPAUS0C-limits-from-acctdata",
                goldens = {"pending-auth-summary-valid-account"},
                behavior = "CREDLIM and CASHLIM show ACCT-CREDIT-LIMIT and ACCT-CASH-CREDIT-LIMIT from ACCTDATA, not "
                        + "the IMS summary's PA-CREDIT-LIMIT and PA-CASH-LIMIT the other totals come from, so "
                        + "account 1 shows 2020.00 while its summary says 2022.00",
                fix = "show the summary's limits next to the summary's balances")
        private void showLimits(Optional<AccountRecord> account) {
            map.set(CREDLIM, account.map(a -> AuthPicture.signed(a.creditLimit(), 8)).orElse("         .00"))
                    .set(CASHLIM, account.map(a -> AuthPicture.signed(a.cashCreditLimit(), 5)).orElse("      .00"));
        }

        private void showSummary(PendingAuthSummary summary) {
            map.set(APPRCNT, count(summary.approvedAuthCnt()))
                    .set(DECLCNT, count(summary.declinedAuthCnt()))
                    .set(CREDBAL, AuthPicture.signed(summary.creditBalance(), 8))
                    .set(CASHBAL, AuthPicture.signed(summary.cashBalance(), 5))
                    .set(APPRAMT, AuthPicture.signed(summary.approvedAuthAmt(), 5))
                    .set(DECLAMT, AuthPicture.signed(summary.declinedAuthAmt(), 5));
        }

        /** SCHD, then {@code GU SEGMENT(PAUTSUM0) WHERE (ACCNTID = CDEMO-ACCT-ID)}. */
        private Optional<PendingAuthSummary> getAuthSummary() {
            pcb = database.schedule();
            Get<PendingAuthSummary> got = pcb.gu(Ssa.summary(acctId == null ? 0 : acctId));
            switch (got.status()) {
                case OK -> summaryFound = true;
                case GE -> summaryFound = false;
                default -> {
                    error = true;
                    message.overlay(" System error while reading AUTH Summary: Code:" + got.status().code());
                }
            }
            return got.segment();
        }

        /** SEND-PAULST-SCREEN. Earlier SENDs in the same task only show what this one overwrites. */
        Outcome send() {
            map.set(ERRMSG, message.toString());
            return show(map, mode, new State(acctId, custId, paging));
        }
    }

    private static String notFound(String subject, String file, int resp, int resp2) {
        return subject + " not found in " + file + " file. Resp:" + "%09d".formatted(resp) + " Reas:"
                + "%09d".formatted(resp2);
    }

    private static String systemError(String subject, String file, int resp, int resp2) {
        return subject + " System error while reading " + file + " file. Resp:" + "%09d".formatted(resp) + " Reas:"
                + "%09d".formatted(resp2);
    }

    private static String zeros(COPAU0A field) {
        return "0".repeat(field.length());
    }

    /** {@code PIC 9(03)} from an {@code S9(04) COMP} count: the sign and the high digit are lost. */
    private static String count(int value) {
        return "%03d".formatted(Math.abs(value) % 1000);
    }

    /** {@code DELIMITED BY SPACES}. */
    private static String upToSpace(String text) {
        String value = Objects.toString(text, "");
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }

    /** {@code DELIMITED BY '  '} on the space-padded field. */
    private static String upToDoubleSpace(String text, int length) {
        String value = AuthPicture.pad(text, length);
        int gap = value.indexOf("  ");
        return gap < 0 ? value : value.substring(0, gap);
    }
}
