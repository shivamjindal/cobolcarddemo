package com.carddemo.account;

import static com.carddemo.account.FetchedDetails.slice;
import static com.carddemo.account.Moves.move;
import static com.carddemo.online.maps.CACTUPA.AADDGRP;
import static com.carddemo.online.maps.CACTUPA.ACCTSID;
import static com.carddemo.online.maps.CACTUPA.ACRCYCR;
import static com.carddemo.online.maps.CACTUPA.ACRCYDB;
import static com.carddemo.online.maps.CACTUPA.ACRDLIM;
import static com.carddemo.online.maps.CACTUPA.ACSADL1;
import static com.carddemo.online.maps.CACTUPA.ACSADL2;
import static com.carddemo.online.maps.CACTUPA.ACSCITY;
import static com.carddemo.online.maps.CACTUPA.ACSCTRY;
import static com.carddemo.online.maps.CACTUPA.ACSEFTC;
import static com.carddemo.online.maps.CACTUPA.ACSFNAM;
import static com.carddemo.online.maps.CACTUPA.ACSGOVT;
import static com.carddemo.online.maps.CACTUPA.ACSHLIM;
import static com.carddemo.online.maps.CACTUPA.ACSLNAM;
import static com.carddemo.online.maps.CACTUPA.ACSMNAM;
import static com.carddemo.online.maps.CACTUPA.ACSPFLG;
import static com.carddemo.online.maps.CACTUPA.ACSPH1A;
import static com.carddemo.online.maps.CACTUPA.ACSPH1B;
import static com.carddemo.online.maps.CACTUPA.ACSPH1C;
import static com.carddemo.online.maps.CACTUPA.ACSPH2A;
import static com.carddemo.online.maps.CACTUPA.ACSPH2B;
import static com.carddemo.online.maps.CACTUPA.ACSPH2C;
import static com.carddemo.online.maps.CACTUPA.ACSSTTE;
import static com.carddemo.online.maps.CACTUPA.ACSTFCO;
import static com.carddemo.online.maps.CACTUPA.ACSTNUM;
import static com.carddemo.online.maps.CACTUPA.ACSTTUS;
import static com.carddemo.online.maps.CACTUPA.ACSZIPC;
import static com.carddemo.online.maps.CACTUPA.ACTSSN1;
import static com.carddemo.online.maps.CACTUPA.ACTSSN2;
import static com.carddemo.online.maps.CACTUPA.ACTSSN3;
import static com.carddemo.online.maps.CACTUPA.ACURBAL;
import static com.carddemo.online.maps.CACTUPA.DOBDAY;
import static com.carddemo.online.maps.CACTUPA.DOBMON;
import static com.carddemo.online.maps.CACTUPA.DOBYEAR;
import static com.carddemo.online.maps.CACTUPA.ERRMSG;
import static com.carddemo.online.maps.CACTUPA.EXPDAY;
import static com.carddemo.online.maps.CACTUPA.EXPMON;
import static com.carddemo.online.maps.CACTUPA.EXPYEAR;
import static com.carddemo.online.maps.CACTUPA.FKEY05;
import static com.carddemo.online.maps.CACTUPA.FKEY12;
import static com.carddemo.online.maps.CACTUPA.INFOMSG;
import static com.carddemo.online.maps.CACTUPA.OPNDAY;
import static com.carddemo.online.maps.CACTUPA.OPNMON;
import static com.carddemo.online.maps.CACTUPA.OPNYEAR;
import static com.carddemo.online.maps.CACTUPA.RISDAY;
import static com.carddemo.online.maps.CACTUPA.RISMON;
import static com.carddemo.online.maps.CACTUPA.RISYEAR;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.account.AccountEdits.Verdict;
import com.carddemo.accounts.AccountRecord;
import com.carddemo.accounts.AccountStore;
import com.carddemo.accounts.CardXrefStore;
import com.carddemo.accounts.CustomerRecord;
import com.carddemo.accounts.CustomerStore;
import com.carddemo.accounts.ForUpdate;
import com.carddemo.accounts.Held;
import com.carddemo.accounts.Read;
import com.carddemo.accounts.Write;
import com.carddemo.accounts.XrefRecord;
import com.carddemo.menu.MainMenuScreen;
import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.Color;
import com.carddemo.online.FieldAttribute;
import com.carddemo.online.Key;
import com.carddemo.online.NoHandoff;
import com.carddemo.online.Outcome;
import com.carddemo.online.ProgramName;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.ScreenState;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.CACTUPA;

/**
 * COACTUPC. ENTER fetches an account and its customer through the card cross-reference; changes are
 * edited, then confirmed with PF5, which rewrites both records. PF12 re-reads what is stored.
 */
@Component
final class AccountUpdateScreen extends Screen<CACTUPA, AccountUpdateScreen.Action, AccountUpdateScreen.State, NoHandoff> {

    static final ScreenRef<NoHandoff> REF = ScreenRef.of("COACTUPC", "CAUP", "account-update", NoHandoff.class);

    private static final String PROMPT_FOR_SEARCH_KEYS = "Enter or update id of account to update";
    private static final String PROMPT_FOR_CHANGES = "Update account details presented above.";
    private static final String PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";
    private static final String CONFIRM_UPDATE_SUCCESS = "Changes committed to database";
    private static final String INFORM_FAILURE = "Changes unsuccessful. Please try again";
    private static final String NO_CHANGES_DETECTED = "No change detected with respect to values fetched.";
    private static final String COULD_NOT_LOCK_ACCOUNT = "Could not lock account record for update";
    private static final String DATA_WAS_CHANGED_BEFORE_UPDATE = "Record changed by some one else. Please review";
    private static final String LOCKED_BUT_UPDATE_FAILED = "Update of record failed";

    private static final Pattern ELEVEN_DIGITS = Pattern.compile("\\d{11}");

    /** Every field 1100 receives after the account number: ACSTTUS to ACSPFLG in map order. */
    private static final Set<CACTUPA> DETAILS = EnumSet.range(ACSTTUS, ACSPFLG);

    /** The details 3320 leaves protected while they are open for changes. */
    private static final Set<CACTUPA> NEVER_EDITED = EnumSet.of(ACSTNUM, ACSCTRY);

    private static final List<CACTUPA> AMOUNTS = List.of(ACRDLIM, ACSHLIM, ACURBAL, ACRCYCR, ACRCYDB);

    enum Action implements ScreenAction {
        PROCESS(Key.ENTER), EXIT(Key.PF3), SAVE(Key.PF5), CANCEL(Key.PF12),
        /** CSSTRPFY folds PF13 to PF24 onto PF1 to PF12. */
        EXIT_SHIFTED(Key.PF15), SAVE_SHIFTED(Key.PF17), CANCEL_SHIFTED(Key.PF24);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    /** ACUP-CHANGE-ACTION. */
    enum Stage {
        NOT_FETCHED, SHOW_DETAILS, CHANGES_NOT_OK, CHANGES_OK_NOT_CONFIRMED,
        CHANGES_OKAYED_AND_DONE, CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED
    }

    /**
     * What the COMMAREA carries to the next turn. {@code returnTo} is CDEMO-FROM-PROGRAM, where PF3
     * goes; the main menu's XCTL makes COACTUPC initialize the COMMAREA, so from the menu it is the
     * menu again.
     */
    sealed interface State extends ScreenState permits Searching, Reviewing, Finished {
        ProgramName returnTo();
    }

    /** No account fetched yet. */
    record Searching(ProgramName returnTo) implements State {}

    /**
     * Details on screen: as fetched, with changes that failed an edit, or with valid changes waiting
     * for PF5.
     */
    record Reviewing(ProgramName returnTo, Stage stage, FetchedDetails fetched) implements State {
        Reviewing {
            if (stage != Stage.SHOW_DETAILS && stage != Stage.CHANGES_NOT_OK && stage != Stage.CHANGES_OK_NOT_CONFIRMED) {
                throw new IllegalArgumentException("not a review stage: " + stage);
            }
        }
    }

    /** A save ran, whatever came of it; any key but PF3 starts a new search. */
    record Finished(ProgramName returnTo) implements State {}

    private final CardXrefStore xrefs;
    private final AccountStore accounts;
    private final CustomerStore customers;
    private final Clock clock;

    AccountUpdateScreen(CardXrefStore xrefs, AccountStore accounts, CustomerStore customers, Clock clock) {
        super(REF, CACTUPA.MAP, Action.class, State.class, Access.SIGNED_ON);
        this.xrefs = xrefs;
        this.accounts = accounts;
        this.customers = customers;
        this.clock = clock;
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return promptForSearch(arrival.from().orElse(MainMenuScreen.REF.program()));
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<CACTUPA> map, State state) {
        return switch (action) {
            case EXIT, EXIT_SHIFTED -> transfer(state.returnTo());
            case PROCESS -> process(Action.PROCESS, map, state);
            case SAVE, SAVE_SHIFTED -> process(Action.SAVE, map, state);
            case CANCEL, CANCEL_SHIFTED -> process(Action.CANCEL, map, state);
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<CACTUPA> map, State state) {
        return process(anyOtherKeyIsEnter(), map, state);
    }

    @LegacyDefect(id = "COACTUPC-any-other-key-is-enter",
            goldens = {"account-update-pf12-before-fetch", "account-update-pf5-before-confirmation"},
            behavior = "a key 0000-MAIN does not accept at this point, such as PF12 before an account is fetched or "
                    + "PF5 with no change waiting for confirmation, is taken as ENTER and processes the screen",
            fix = "redisplay the screen with the invalid key message, as the menus do")
    private static Action anyOtherKeyIsEnter() {
        return Action.PROCESS;
    }

    private Outcome process(Action key, SymbolicMap<CACTUPA> received, State state) {
        return switch (state) {
            case Searching searching -> new Task(searching.returnTo(), Stage.NOT_FETCHED, null).processInputs(key, received);
            case Reviewing reviewing ->
                    new Task(reviewing.returnTo(), reviewing.stage(), reviewing.fetched()).processInputs(key, received);
            case Finished finished -> promptForSearch(finished.returnTo());
        };
    }

    /** The first display, which 0000-MAIN repeats with fresh storage after a save. */
    private Outcome promptForSearch(ProgramName returnTo) {
        Task task = new Task(returnTo, Stage.NOT_FETCHED, null);
        task.entering = true;
        return task.sendMap();
    }

    /** One run of the program: its working storage and its COMMAREA. */
    private final class Task {
        private final ProgramName returnTo;
        private final ReturnMessage message = new ReturnMessage();
        private Stage stage;
        /** ACUP-OLD-DETAILS; null until 9000 finds the customer. */
        private FetchedDetails fetched;
        /** CDEMO-PGM-ENTER. */
        private boolean entering;
        private Entered entered;
        /** CC-ACCT-ID; null for LOW-VALUES. */
        private String accountId;
        /** FLG-ACCTFILTER. */
        private Verdict accountFilter = Verdict.BLANK;
        private AccountEdits edits = AccountEdits.none();

        Task(ProgramName returnTo, Stage stage, FetchedDetails fetched) {
            this.returnTo = returnTo;
            this.stage = stage;
            this.fetched = fetched;
        }

        Outcome processInputs(Action pressed, SymbolicMap<CACTUPA> received) {
            Action key = switch (pressed) {
                case SAVE -> stage == Stage.CHANGES_OK_NOT_CONFIRMED ? pressed : anyOtherKeyIsEnter();
                case CANCEL -> stage != Stage.NOT_FETCHED ? pressed : anyOtherKeyIsEnter();
                default -> pressed;
            };
            entered = new Entered(received);
            accountId = entered.text(ACCTSID);
            editMapInputs();
            decideAction(key);
            return sendMap();
        }

        /** 1200-EDIT-MAP-INPUTS. */
        private void editMapInputs() {
            if (stage == Stage.NOT_FETCHED) {
                editAccount();
                if (accountFilter == Verdict.BLANK) {
                    message.replace(noSearchCriteriaReceived());
                }
                return;
            }
            accountFilter = Verdict.VALID;
            if (fetched.unchangedIn(entered)) {
                message.replace(NO_CHANGES_DETECTED);
                return;
            }
            if (stage == Stage.CHANGES_OK_NOT_CONFIRMED) {
                return;
            }
            edits = AccountEdits.run(entered, message, LocalDate.now(clock));
            stage = edits.anyFailed() ? Stage.CHANGES_NOT_OK : Stage.CHANGES_OK_NOT_CONFIRMED;
        }

        /** 1210-EDIT-ACCOUNT. */
        private void editAccount() {
            if (accountId == null) {
                accountFilter = Verdict.BLANK;
                message.offer("Account number not provided");
            } else if (!ELEVEN_DIGITS.matcher(accountId).matches() || Long.parseLong(accountId) == 0) {
                accountFilter = Verdict.NOT_OK;
                message.offer("Account Number if supplied must be a 11 digit Non-Zero Number");
            } else {
                accountFilter = Verdict.VALID;
            }
        }

        @LegacyDefect(id = "COACTUPC-blank-account-message", goldens = {"account-update-blank-account",
                "account-update-pf12-before-fetch"},
                behavior = "a blank or '*' account sets 'Account number not provided', then 1200 overwrites it with "
                        + "'No input received'",
                fix = "keep 'Account number not provided'")
        private static String noSearchCriteriaReceived() {
            return "No input received";
        }

        /** 2000-DECIDE-ACTION. */
        private void decideAction(Action key) {
            if (stage == Stage.NOT_FETCHED || key == Action.CANCEL) {
                if (accountFilter == Verdict.VALID) {
                    message.clear();
                    readAccount().ifPresent(found -> {
                        fetched = found;
                        stage = Stage.SHOW_DETAILS;
                    });
                }
            } else if (stage == Stage.CHANGES_OK_NOT_CONFIRMED && key == Action.SAVE) {
                writeProcessing();
                stage = switch (message.text()) {
                    case COULD_NOT_LOCK_ACCOUNT -> Stage.CHANGES_OKAYED_LOCK_ERROR;
                    case LOCKED_BUT_UPDATE_FAILED -> Stage.CHANGES_OKAYED_BUT_FAILED;
                    case DATA_WAS_CHANGED_BEFORE_UPDATE -> Stage.SHOW_DETAILS;
                    default -> Stage.CHANGES_OKAYED_AND_DONE;
                };
            }
        }

        /** 9000-READ-ACCT: the details, once the customer is found. */
        private Optional<FetchedDetails> readAccount() {
            long id = stage == Stage.NOT_FETCHED ? Long.parseLong(accountId) : fetched.accountId();
            return switch (xrefs.findByAccount(id)) {
                case Read.Found<XrefRecord> found -> readAccountAndCustomer(id, found.record());
                case Read.Missing<XrefRecord> missing -> {
                    accountFilter = Verdict.NOT_OK;
                    message.offer("Account:" + "%011d".formatted(id) + " not found in Cross ref file.  Resp:"
                            + ReturnMessage.code(ReturnMessage.NOTFND) + " Reas:" + ReturnMessage.code(0));
                    yield Optional.empty();
                }
                case Read.Failed<XrefRecord> failed -> {
                    accountFilter = Verdict.NOT_OK;
                    message.replace(ReturnMessage.fileError("READ", "CXACAIX", failed.resp(), failed.resp2()));
                    yield Optional.empty();
                }
            };
        }

        @LegacyDefect(id = "COACTUPC-customer-without-account",
                behavior = "an account missing from ACCTDAT, or whose READ fails, still reads its customer and opens "
                        + "the details for changes, because 9000-READ-ACCT tests a message 88 that nothing sets; "
                        + "COACTUPC also shows and compares account fields from a record it never read, which this "
                        + "port leaves blank",
                fix = "stop after the failed account READ")
        private Optional<FetchedDetails> readAccountAndCustomer(long id, XrefRecord xref) {
            Optional<AccountRecord> account = switch (accounts.find(id)) {
                case Read.Found<AccountRecord> found -> Optional.of(found.record());
                case Read.Missing<AccountRecord> missing -> {
                    accountFilter = Verdict.NOT_OK;
                    message.offer("Account:" + "%011d".formatted(id) + " not found in Acct Master file.Resp:"
                            + ReturnMessage.code(ReturnMessage.NOTFND) + " Reas:" + ReturnMessage.code(0));
                    yield Optional.empty();
                }
                case Read.Failed<AccountRecord> failed -> {
                    accountFilter = Verdict.NOT_OK;
                    message.replace(ReturnMessage.fileError("READ", "ACCTDAT", failed.resp(), failed.resp2()));
                    yield Optional.empty();
                }
            };
            return switch (customers.find(xref.custId())) {
                case Read.Found<CustomerRecord> found -> Optional.of(FetchedDetails.of(id, account, found.record()));
                case Read.Missing<CustomerRecord> missing -> {
                    message.offer("CustId:" + "%09d".formatted(xref.custId()) + " not found in customer master.Resp: "
                            + ReturnMessage.code(ReturnMessage.NOTFND) + " REAS:" + ReturnMessage.code(0));
                    yield Optional.empty();
                }
                case Read.Failed<CustomerRecord> failed -> {
                    message.replace(ReturnMessage.fileError("READ", "CUSTDAT", failed.resp(), failed.resp2()));
                    yield Optional.empty();
                }
            };
        }

        /** 9600-WRITE-PROCESSING; 2000 reads the outcome from the message it leaves. */
        private void writeProcessing() {
            if (!(accounts.findForUpdate(fetched.accountId()) instanceof Held<AccountRecord> account)) {
                message.offer(COULD_NOT_LOCK_ACCOUNT);
                return;
            }
            if (!(customers.findForUpdate(fetched.customer().id()) instanceof Held<CustomerRecord> customer)) {
                customerNotLocked();
                return;
            }
            if (!fetched.stillIn(account.record(), customer.record())) {
                message.replace(DATA_WAS_CHANGED_BEFORE_UPDATE);
                return;
            }
            if (accounts.rewrite(account, updatedAccount()) == Write.FAILED) {
                message.replace(LOCKED_BUT_UPDATE_FAILED);
                return;
            }
            // COACTUPC's SYNCPOINT ROLLBACK here undoes nothing: every file is defined RECOVERY(NONE).
            if (customers.rewrite(customer, updatedCustomer()) == Write.FAILED) {
                message.replace(LOCKED_BUT_UPDATE_FAILED);
            }
        }

        @LegacyDefect(id = "COACTUPC-customer-lock-failure-committed",
                behavior = "when the account locks but the customer READ UPDATE fails, 2000-DECIDE-ACTION has no WHEN "
                        + "for 'Could not lock customer record for update', so the screen says 'Changes committed to "
                        + "database' although nothing was written",
                fix = "treat it as a lock error, as for the account")
        private void customerNotLocked() {
            message.offer("Could not lock customer record for update");
        }

        /** ACCT-UPDATE-RECORD. */
        private AccountRecord updatedAccount() {
            return new AccountRecord(fetched.accountId(), entered.text(ACSTTUS),
                    entered.amount(ACURBAL).orElseThrow(), entered.amount(ACRDLIM).orElseThrow(),
                    entered.amount(ACSHLIM).orElseThrow(), entered.date(OPNYEAR, OPNMON, OPNDAY),
                    entered.date(EXPYEAR, EXPMON, EXPDAY), entered.date(RISYEAR, RISMON, RISDAY),
                    entered.amount(ACRCYCR).orElseThrow(), entered.amount(ACRCYDB).orElseThrow(),
                    groupIdIntoZip(), null, null);
        }

        @LegacyDefect(id = "COACTUPC-group-id-into-zip",
                goldens = {"account-update-save", "account-update-save-group-and-customer"},
                behavior = "ACCT-UPDATE-RECORD has no ACCT-ADDR-ZIP, so the group id is written over the zip, and "
                        + "ACCT-GROUP-ID and the FILLER get the update record's FILLER, which nothing initializes "
                        + "(LOW-VALUES under the harness); a blank group id writes LOW-VALUES",
                fix = "lay ACCT-UPDATE-RECORD out like CVACT01Y and keep the stored zip")
        private String groupIdIntoZip() {
            return entered.text(AADDGRP);
        }

        /** CUST-UPDATE-RECORD. LOW-VALUES cannot be stored in its text columns, so a blank field is stored as SPACES. */
        private CustomerRecord updatedCustomer() {
            return new CustomerRecord(fetched.customer().id(), stored(ACSFNAM), stored(ACSMNAM), stored(ACSLNAM),
                    stored(ACSADL1), stored(ACSADL2), stored(ACSCITY), stored(ACSSTTE), stored(ACSCTRY),
                    stored(ACSZIPC), phone(ACSPH1A, ACSPH1B, ACSPH1C), phone(ACSPH2A, ACSPH2B, ACSPH2C),
                    Integer.parseInt(entered.text(ACTSSN1) + entered.text(ACTSSN2) + entered.text(ACTSSN3)),
                    stored(ACSGOVT), entered.date(DOBYEAR, DOBMON, DOBDAY), stored(ACSEFTC), stored(ACSPFLG),
                    Integer.parseInt(entered.text(ACSTFCO)), null);
        }

        private String stored(CACTUPA field) {
            String text = entered.text(field);
            return text == null ? "" : text;
        }

        /** {@code STRING '(' A ')' B '-' C DELIMITED BY SIZE}. */
        private String phone(CACTUPA area, CACTUPA prefix, CACTUPA line) {
            return ("(" + "%-3s".formatted(stored(area)) + ")" + "%-3s".formatted(stored(prefix)) + "-"
                    + stored(line)).stripTrailing();
        }

        /** 3000-SEND-MAP. */
        Outcome sendMap() {
            SymbolicMap<CACTUPA> map = SymbolicMap.lowValues(CACTUPA.MAP);
            setupScreenVars(map);
            map.set(INFOMSG, infoMessage()).set(ERRMSG, message.text());
            setupScreenAttrs(map);
            setupInfoMsgAttrs(map);
            return show(map, state());
        }

        private State state() {
            return switch (stage) {
                case NOT_FETCHED -> new Searching(returnTo);
                case SHOW_DETAILS, CHANGES_NOT_OK, CHANGES_OK_NOT_CONFIRMED -> new Reviewing(returnTo, stage, fetched);
                case CHANGES_OKAYED_AND_DONE, CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED -> new Finished(returnTo);
            };
        }

        /** 3200-SETUP-SCREEN-VARS. */
        private void setupScreenVars(SymbolicMap<CACTUPA> map) {
            if (entering) {
                return;
            }
            boolean noAccountId = Intrinsics.zoned(Intrinsics.field(accountId, 11)) == 0;
            if (!(noAccountId && accountFilter == Verdict.VALID)) {
                move(map, ACCTSID, accountId);
            }
            if (stage == Stage.NOT_FETCHED || noAccountId) {
                return;
            }
            if (stage == Stage.SHOW_DETAILS) {
                showOriginalValues(map);
            } else {
                showUpdatedValues(map);
            }
        }

        /** 3202-SHOW-ORIGINAL-VALUES. */
        private void showOriginalValues(SymbolicMap<CACTUPA> map) {
            edits = AccountEdits.none();
            FetchedDetails.Account account = fetched.account();
            if (account != null) {
                move(map, ACSTTUS, account.activeStatus());
                map.set(ACURBAL, AmountPicture.edit(account.currBal()))
                        .set(ACRDLIM, AmountPicture.edit(account.creditLimit()))
                        .set(ACSHLIM, AmountPicture.edit(account.cashCreditLimit()))
                        .set(ACRCYCR, AmountPicture.edit(account.currCycCredit()))
                        .set(ACRCYDB, AmountPicture.edit(account.currCycDebit()));
                showDate(map, OPNYEAR, OPNMON, OPNDAY, account.opened());
                showDate(map, EXPYEAR, EXPMON, EXPDAY, account.expires());
                showDate(map, RISYEAR, RISMON, RISDAY, account.reissued());
                move(map, AADDGRP, account.groupId());
            }
            FetchedDetails.Customer customer = fetched.customer();
            String ssn = customer.ssnText();
            map.set(ACSTNUM, customer.idText())
                    .set(ACTSSN1, slice(ssn, 1, 3))
                    .set(ACTSSN2, slice(ssn, 4, 2))
                    .set(ACTSSN3, slice(ssn, 6, 4))
                    .set(ACSTFCO, customer.ficoText());
            showDate(map, DOBYEAR, DOBMON, DOBDAY, customer.born());
            move(map, ACSFNAM, customer.firstName());
            move(map, ACSMNAM, customer.middleName());
            move(map, ACSLNAM, customer.lastName());
            move(map, ACSADL1, customer.addrLine1());
            move(map, ACSADL2, customer.addrLine2());
            move(map, ACSCITY, customer.addrLine3());
            move(map, ACSSTTE, customer.stateCd());
            move(map, ACSZIPC, customer.addrZip());
            move(map, ACSCTRY, customer.countryCd());
            showPhone(map, ACSPH1A, ACSPH1B, ACSPH1C, customer.phoneNum1());
            showPhone(map, ACSPH2A, ACSPH2B, ACSPH2C, customer.phoneNum2());
            move(map, ACSGOVT, customer.govtIssuedId());
            move(map, ACSEFTC, customer.eftAccountId());
            move(map, ACSPFLG, customer.priCardHolderInd());
        }

        private static void showDate(SymbolicMap<CACTUPA> map, CACTUPA year, CACTUPA month, CACTUPA day,
                FetchedDetails.DateParts date) {
            map.set(year, date.year()).set(month, date.month()).set(day, date.day());
        }

        private static void showPhone(SymbolicMap<CACTUPA> map, CACTUPA area, CACTUPA prefix, CACTUPA line,
                String phone) {
            map.set(area, slice(phone, 2, 3)).set(prefix, slice(phone, 6, 3)).set(line, slice(phone, 10, 4));
        }

        /** 3203-SHOW-UPDATED-VALUES: what was typed, with an amount that passed its edit re-edited. */
        private void showUpdatedValues(SymbolicMap<CACTUPA> map) {
            DETAILS.forEach(field -> move(map, field, entered.text(field)));
            for (CACTUPA amount : AMOUNTS) {
                if (!edits.failed(amount)) {
                    entered.amount(amount).ifPresent(value -> map.set(amount, AmountPicture.edit(value)));
                }
            }
        }

        /** 3250-SETUP-INFOMSG. */
        private String infoMessage() {
            if (entering) {
                return PROMPT_FOR_SEARCH_KEYS;
            }
            return switch (stage) {
                case NOT_FETCHED -> PROMPT_FOR_SEARCH_KEYS;
                case SHOW_DETAILS, CHANGES_NOT_OK -> PROMPT_FOR_CHANGES;
                case CHANGES_OK_NOT_CONFIRMED -> PROMPT_FOR_CONFIRMATION;
                case CHANGES_OKAYED_AND_DONE -> CONFIRM_UPDATE_SUCCESS;
                case CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED -> INFORM_FAILURE;
            };
        }

        /** 3300-SETUP-SCREEN-ATTRS. */
        private void setupScreenAttrs(SymbolicMap<CACTUPA> map) {
            map.attribute(ACCTSID, FieldAttribute.BMPRF).attribute(INFOMSG, FieldAttribute.BMPRF);
            DETAILS.forEach(field -> map.attribute(field, FieldAttribute.BMPRF));
            switch (stage) {
                case NOT_FETCHED, CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED ->
                        map.attribute(ACCTSID, FieldAttribute.BMFSE);
                case SHOW_DETAILS, CHANGES_NOT_OK -> DETAILS.stream()
                        .filter(field -> !NEVER_EDITED.contains(field))
                        .forEach(field -> map.attribute(field, FieldAttribute.BMFSE));
                case CHANGES_OK_NOT_CONFIRMED, CHANGES_OKAYED_AND_DONE -> {
                }
            }
            map.cursor(cursorField());
            if (accountFilter == Verdict.NOT_OK) {
                map.color(ACCTSID, Color.RED);
            }
            if (accountFilter == Verdict.BLANK && !entering) {
                map.set(ACCTSID, "*").color(ACCTSID, Color.RED);
            }
            if (stage == Stage.NOT_FETCHED || accountFilter != Verdict.VALID) {
                return;
            }
            for (CACTUPA field : DETAILS) {
                if (edits.failed(field)) {
                    map.color(field, Color.RED);
                    if (edits.blank(field)) {
                        map.set(field, "*");
                    }
                }
            }
        }

        private CACTUPA cursorField() {
            if (message.text().equals(NO_CHANGES_DETECTED)) {
                return ACSTTUS;
            }
            if (accountFilter != Verdict.VALID) {
                return ACCTSID;
            }
            return edits.firstFailed().orElseGet(Task::cursorOtherwise);
        }

        @LegacyDefect(id = "COACTUPC-cursor-on-protected-account",
                goldens = {"account-update-fetch", "account-update-pf12-discards-edits",
                        "account-update-confirm-pf12-cancels"},
                behavior = "with the details open for changes and nothing to flag, the cursor goes to the account "
                        + "number, which is protected by then, through the WHEN OTHER of 3300's cursor EVALUATE",
                fix = "put the cursor on the account status while the details are open for changes")
        private static CACTUPA cursorOtherwise() {
            return ACCTSID;
        }

        /** 3390-SETUP-INFOMSG-ATTRS. */
        private void setupInfoMsgAttrs(SymbolicMap<CACTUPA> map) {
            map.attribute(INFOMSG, FieldAttribute.BMASB);
            if (stage == Stage.CHANGES_NOT_OK || stage == Stage.CHANGES_OK_NOT_CONFIRMED
                    || stage == Stage.CHANGES_OKAYED_LOCK_ERROR || stage == Stage.CHANGES_OKAYED_BUT_FAILED) {
                map.attribute(FKEY12, FieldAttribute.BMASB);
            }
            if (stage == Stage.CHANGES_OK_NOT_CONFIRMED) {
                map.attribute(FKEY05, FieldAttribute.BMASB).attribute(FKEY12, FieldAttribute.BMASB);
            }
        }
    }
}
