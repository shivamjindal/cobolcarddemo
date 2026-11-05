package com.carddemo.trantype;

import static com.carddemo.online.maps.CTRTUPA.ERRMSG;
import static com.carddemo.online.maps.CTRTUPA.FKEY04;
import static com.carddemo.online.maps.CTRTUPA.FKEY05;
import static com.carddemo.online.maps.CTRTUPA.FKEY12;
import static com.carddemo.online.maps.CTRTUPA.FKEYS;
import static com.carddemo.online.maps.CTRTUPA.INFOMSG;
import static com.carddemo.online.maps.CTRTUPA.TRTYDSC;
import static com.carddemo.online.maps.CTRTUPA.TRTYPCD;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.CHANGES_BACKED_OUT;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.CHANGES_NOT_OK;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.CHANGES_OKAYED_AND_DONE;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.CHANGES_OKAYED_BUT_FAILED;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.CHANGES_OKAYED_LOCK_ERROR;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.CHANGES_OK_NOT_CONFIRMED;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.CONFIRM_DELETE;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.CREATE_NEW_RECORD;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.DELETE_DONE;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.DELETE_FAILED;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.DETAILS_NOT_FETCHED;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.DETAILS_NOT_FOUND;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.SHOW_DETAILS;
import static com.carddemo.trantype.TransactionTypeMaintenanceScreen.ChangeAction.START_DELETE;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.account.Intrinsics;
import com.carddemo.db2.Db2;
import com.carddemo.db2.Fetched;
import com.carddemo.db2.Sqlca;
import com.carddemo.menu.AdminMenuScreen;
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
import com.carddemo.online.maps.CTRTUPA;

/** COTRTUPC: shows, changes, adds and deletes one row of the Db2 table CARDDEMO.TRANSACTION_TYPE. */
@Component
public final class TransactionTypeMaintenanceScreen extends Screen<CTRTUPA, TransactionTypeMaintenanceScreen.Action,
        TransactionTypeMaintenanceScreen.State, NoHandoff> {

    public static final ScreenRef<NoHandoff> REF = ScreenRef.of("COTRTUPC", "CTTU", "tran-type-maint", NoHandoff.class);

    enum Action implements ScreenAction {
        PROCESS(Key.ENTER, Key.ENTER), EXIT(Key.PF3, Key.PF3), DELETE(Key.PF4, Key.PF4), SAVE(Key.PF5, Key.PF5),
        CANCEL(Key.PF12, Key.PF12),
        /** CSSTRPFY folds PF13 to PF24 onto PF1 to PF12. */
        EXIT_SHIFTED(Key.PF15, Key.PF3), DELETE_SHIFTED(Key.PF16, Key.PF4), SAVE_SHIFTED(Key.PF17, Key.PF5),
        CANCEL_SHIFTED(Key.PF24, Key.PF12);

        private final Key key;
        private final Key aid;

        Action(Key key, Key aid) {
            this.key = key;
            this.aid = aid;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    /**
     * TTUP-CHANGE-ACTION. TTUP-INVALID-SEARCH-KEYS and TTUP-REVIEW-NEW-RECORD are left out: the first is
     * overwritten in the statement after it is set, and the second is never set.
     */
    enum ChangeAction {
        DETAILS_NOT_FETCHED, DETAILS_NOT_FOUND, SHOW_DETAILS, CREATE_NEW_RECORD,
        CONFIRM_DELETE, START_DELETE, DELETE_DONE, DELETE_FAILED,
        CHANGES_NOT_OK, CHANGES_OK_NOT_CONFIRMED, CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED,
        CHANGES_OKAYED_AND_DONE, CHANGES_BACKED_OUT;

        boolean deleteInProgress() {
            return this == CONFIRM_DELETE || this == START_DELETE || this == DELETE_DONE || this == DELETE_FAILED;
        }

        boolean changesMade() {
            return this == CHANGES_NOT_OK || this == CHANGES_OK_NOT_CONFIRMED || changesFailed();
        }

        boolean changesFailed() {
            return this == CHANGES_OKAYED_LOCK_ERROR || this == CHANGES_OKAYED_BUT_FAILED;
        }
    }

    /**
     * WS-THIS-PROGCOMMAREA, plus CDEMO-FROM-PROGRAM as {@code returnTo}. The details are the PIC X bytes, X'00'
     * for LOW-VALUES, because the program tells SPACES and LOW-VALUES apart in them.
     */
    record State(ProgramName returnTo, ChangeAction action, String oldType, String oldDesc, String newType,
            String newDesc) implements ScreenState {}

    /** WS-EDIT-ALPHANUM-ONLY-FLAGS and the flags it is copied to. INITIALIZE leaves them at none of the 88s. */
    private enum Edit { UNSET, VALID, NOT_OK, BLANK }

    private record Row(String type, String description) {}

    private static final int TYPE_LENGTH = 2;
    private static final int DESC_LENGTH = 50;
    private static final int RETURN_MSG_LENGTH = 75;
    private static final int INFO_MSG_LENGTH = 40;
    private static final int SQLERRMC_LENGTH = 70;

    private static final String PROMPT_FOR_SEARCH_KEYS = "Enter transaction type to be maintained";
    private static final String PROMPT_CREATE_NEW_RECORD = "Press F05 to add. F12 to cancel";
    private static final String PROMPT_DELETE_CONFIRM = "Delete this record ? Press F4 to confirm";
    private static final String CONFIRM_DELETE_SUCCESS = "Delete successful.";
    private static final String PROMPT_FOR_CHANGES = "Update transaction type details shown.";
    private static final String PROMPT_FOR_NEWDATA = "Enter new transaction type details.";
    private static final String PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";
    private static final String CONFIRM_UPDATE_SUCCESS = "Changes committed to database";
    private static final String INFORM_FAILURE = "Changes unsuccessful";

    private static final String WS_INVALID_KEY = "Invalid Key pressed.";
    private static final String WS_RECORD_NOT_FOUND = "No record found for this key in database";
    private static final String NO_SEARCH_CRITERIA_RECEIVED = "No input received";
    private static final String NO_CHANGES_DETECTED = "No change detected with respect to values fetched.";
    private static final String COULD_NOT_LOCK_REC_FOR_UPDATE = "Could not lock record for update";
    private static final String WS_UPDATE_WAS_CANCELLED = "Update was cancelled";
    private static final String TABLE_UPDATE_FAILED = "Update of record failed";
    private static final String RECORD_DELETE_FAILED = "Delete of record failed";
    private static final String WS_DELETE_WAS_CANCELLED = "Delete was cancelled";
    private static final String WS_INVALID_KEY_PRESSED = "Invalid key pressed";

    private static final String SELECT_TRANSACTION_TYPE =
            "SELECT TR_TYPE, TR_DESCRIPTION FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = ?";
    private static final String UPDATE_TRANSACTION_TYPE =
            "UPDATE CARDDEMO.TRANSACTION_TYPE SET TR_DESCRIPTION = ? WHERE TR_TYPE = ?";
    private static final String INSERT_TRANSACTION_TYPE =
            "INSERT INTO CARDDEMO.TRANSACTION_TYPE (TR_TYPE, TR_DESCRIPTION) VALUES (?, ?)";
    private static final String DELETE_TRANSACTION_TYPE = "DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = ?";

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getString(1), rs.getString(2));

    private final Db2 db2;

    TransactionTypeMaintenanceScreen(Db2 db2) {
        super(REF, CTRTUPA.MAP, Action.class, State.class, Access.ADMIN);
        this.db2 = db2;
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        State initial = new State(arrival.from().orElse(AdminMenuScreen.REF.program()), DETAILS_NOT_FETCHED,
                spaces(TYPE_LENGTH), spaces(DESC_LENGTH), spaces(TYPE_LENGTH), spaces(DESC_LENGTH));
        return new Task(initial, arrival.aid(), SymbolicMap.lowValues(CTRTUPA.MAP)).firstDisplay();
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<CTRTUPA> map, State state) {
        return new Task(state, action.aid, map).main();
    }

    /** Any key YYYY-STORE-PFKEY does not turn into one 0001-CHECK-PFKEYS names. */
    @Override
    protected Outcome invalidKey(SymbolicMap<CTRTUPA> map, State state) {
        return new Task(state, null, map).main();
    }

    /** One run of 0000-MAIN: the COMMAREA it was given, and its working storage. */
    private final class Task {

        private final Key aid;
        private final SymbolicMap<CTRTUPA> received;
        private final ProgramName returnTo;
        private ChangeAction action;
        private String oldType;
        private String oldDesc;
        private String newType;
        private String newDesc;

        /** CDEMO-PGM-ENTER rather than CDEMO-PGM-REENTER. */
        private boolean entering;
        private String returnMsg = "";
        private String infoMsg = "";
        private boolean inputError;
        private boolean noChangesFound;
        private boolean found;
        private Edit tranFilter = Edit.UNSET;
        private Edit descFlag = Edit.UNSET;
        private String typeIn;
        private String descIn;

        Task(State state, Key aid, SymbolicMap<CTRTUPA> received) {
            this.aid = aid;
            this.received = received;
            this.returnTo = state.returnTo();
            this.action = state.action();
            this.oldType = state.oldType();
            this.oldDesc = state.oldDesc();
            this.newType = state.newType();
            this.newDesc = state.newDesc();
        }

        Outcome main() {
            checkPfkeys();
            if (simulatesInitialEntry()) {
                entering = true;
                action = DETAILS_NOT_FETCHED;
            }
            if (aid == Key.PF3) {
                return transfer(returnTo);
            }
            if (entering) {
                return firstDisplay();
            }
            if (aid == Key.PF4 && action == CONFIRM_DELETE) {
                action = START_DELETE;
                deleteProcessing();
                return sendMap();
            }
            if (aid == Key.PF4 && action == SHOW_DETAILS) {
                action = CONFIRM_DELETE;
                return sendMap();
            }
            if (aid == Key.PF5 && action == DETAILS_NOT_FOUND) {
                action = CREATE_NEW_RECORD;
                return sendMap();
            }
            if (aid == Key.PF5 && action == CHANGES_OK_NOT_CONFIRMED) {
                writeProcessing();
                return sendMap();
            }
            if (aid == Key.PF12 && (action == CHANGES_OK_NOT_CONFIRMED || action == CONFIRM_DELETE
                    || action == SHOW_DETAILS)) {
                found = true;
                return decideAction().orElseGet(this::sendMap);
            }
            if (returnMsgIs(WS_INVALID_KEY_PRESSED)) {
                return sendMap();
            }
            processInputs();
            return decideAction().orElseGet(this::sendMap);
        }

        /** The branch of 0000-MAIN for a first display: INITIALIZE, send, and wait for the search key. */
        Outcome firstDisplay() {
            entering = true;
            action = DETAILS_NOT_FETCHED;
            oldType = spaces(TYPE_LENGTH);
            oldDesc = spaces(DESC_LENGTH);
            newType = spaces(TYPE_LENGTH);
            newDesc = spaces(DESC_LENGTH);
            returnMsg = "";
            infoMsg = "";
            inputError = false;
            noChangesFound = false;
            tranFilter = Edit.UNSET;
            descFlag = Edit.UNSET;
            return sendMap();
        }

        // 0001-CHECK-PFKEYS
        private void checkPfkeys() {
            boolean valid = aid == Key.PF3
                    || (aid == Key.ENTER && action != CONFIRM_DELETE)
                    || (aid == Key.PF4 && (action == SHOW_DETAILS || action == CONFIRM_DELETE))
                    || (aid == Key.PF5 && (action == CHANGES_OK_NOT_CONFIRMED || action == DETAILS_NOT_FOUND
                            || action.deleteInProgress()))
                    || cancelAccepted();
            if (!valid && returnMsgOff()) {
                returnMsg = WS_INVALID_KEY_PRESSED;
            }
        }

        @LegacyDefect(id = "COTRTUPC-f12-invalid-after-failed-edit",
                goldens = {"tran-type-maint-edit-error-f12-rejected"},
                behavior = "after an edit fails validation the F12=Cancel legend is gone and F12 answers "
                        + "'Invalid key pressed', so the only ways out are fixing the edit or F3",
                fix = "accept F12 on TTUP-CHANGES-NOT-OK too and restore the fetched values")
        private boolean cancelAccepted() {
            return aid == Key.PF12 && (action == CHANGES_OK_NOT_CONFIRMED || action == SHOW_DETAILS
                    || action == DETAILS_NOT_FOUND || action == CONFIRM_DELETE || action == CREATE_NEW_RECORD);
        }

        private boolean simulatesInitialEntry() {
            return (aid == Key.PF12 && (action == SHOW_DETAILS || action == CREATE_NEW_RECORD
                    || action == DETAILS_NOT_FOUND))
                    || action == CHANGES_OKAYED_AND_DONE
                    || action.changesFailed()
                    || backedOutWithNothingFetched()
                    || action == DELETE_DONE
                    || action == DELETE_FAILED;
        }

        @LegacyDefect(id = "COTRTUPC-cancelled-add-ignores-search",
                goldens = {"tran-type-maint-add-cancel-then-search-ignored"},
                behavior = "after F12 cancels an add, the screen prompts for a type code with the cursor on it, "
                        + "but the next ENTER starts over at the empty first display and ignores the typed code",
                fix = "after a cancelled add go straight to TTUP-DETAILS-NOT-FETCHED, so the next ENTER searches")
        private boolean backedOutWithNothingFetched() {
            String oldDetails = oldType + oldDesc;
            return action == CHANGES_BACKED_OUT && (isLowValues(oldDetails) || isSpaces(oldDetails));
        }

        // 1000-PROCESS-INPUTS
        private void processInputs() {
            receiveMap();
            storeMapInNew();
            editMapInputs();
        }

        // 1100-RECEIVE-MAP
        private void receiveMap() {
            typeIn = inputArea(TRTYPCD);
            descIn = inputArea(TRTYDSC);
        }

        private String inputArea(CTRTUPA field) {
            return received.isLowValues(field) ? lowValues(field.length()) : pic(received.text(field), field.length());
        }

        // 1150-STORE-MAP-IN-NEW
        private void storeMapInNew() {
            if (action == DETAILS_NOT_FOUND && aid != Key.PF5 && same(trim(typeIn), newType)) {
                return;
            }
            newType = same(typeIn, "*") || isSpaces(typeIn) ? lowValues(TYPE_LENGTH) : pic(trim(typeIn), TYPE_LENGTH);
            newDesc = same(descIn, "*") || isSpaces(descIn) ? lowValues(DESC_LENGTH) : pic(trim(descIn), DESC_LENGTH);
        }

        // 1200-EDIT-MAP-INPUTS
        private void editMapInputs() {
            inputError = false;
            if (action == DETAILS_NOT_FOUND && same(trim(typeIn), newType)) {
                searchRetypedCode();
                return;
            }
            if (action != CREATE_NEW_RECORD && action != CHANGES_OK_NOT_CONFIRMED) {
                editTrantype();
                if (tranFilter == Edit.BLANK) {
                    if (returnMsgOff()) {
                        returnMsg = NO_SEARCH_CRITERIA_RECEIVED;
                    }
                    action = DETAILS_NOT_FETCHED;
                    return;
                }
                if (tranFilter == Edit.NOT_OK) {
                    // TTUP-INVALID-SEARCH-KEYS is set here and overwritten by the next statement.
                    action = DETAILS_NOT_FETCHED;
                    return;
                }
                if (action == DETAILS_NOT_FETCHED) {
                    return;
                }
            }
            tranFilter = Edit.VALID;
            compareOldNew();
            if (noChangesFound || action == CHANGES_OK_NOT_CONFIRMED || action == CHANGES_OKAYED_AND_DONE) {
                descFlag = Edit.VALID;
                return;
            }
            action = CHANGES_NOT_OK;
            descFlag = editAlphanumReqd("Transaction Desc", newDesc);
            if (!inputError) {
                action = CHANGES_OK_NOT_CONFIRMED;
            }
        }

        @LegacyDefect(id = "COTRTUPC-retyped-code-skips-edit",
                goldens = {"tran-type-maint-notfound-non-numeric-insert"},
                behavior = "a type code typed over the not-found screen is searched without 1210-EDIT-TRANTYPE, "
                        + "so a non-numeric code such as 'AB' is accepted and can be added with F5",
                fix = "run 1210-EDIT-TRANTYPE on every newly typed code")
        private void searchRetypedCode() {
            if (aid != Key.PF5) {
                action = DETAILS_NOT_FETCHED;
            }
            tranFilter = Edit.VALID;
        }

        // 1205-COMPARE-OLD-NEW
        private void compareOldNew() {
            noChangesFound = true;
            if (sameAsFetched()) {
                if (returnMsgOff()) {
                    returnMsg = NO_CHANGES_DETECTED;
                }
            } else if (returnMsgOff()) {
                noChangesFound = false;
            }
        }

        @LegacyDefect(id = "COTRTUPC-case-only-change-is-no-change",
                goldens = {"tran-type-maint-edit-case-only"},
                behavior = "a description that differs from the stored one only in letter case counts as "
                        + "'No change detected', so it cannot be saved",
                fix = "compare the descriptions as typed")
        private boolean sameAsFetched() {
            String newTrimmed = trim(newDesc);
            String oldTrimmed = trim(oldDesc);
            return upperCase(newType).equals(upperCase(oldType))
                    && same(upperCase(newTrimmed), upperCase(oldTrimmed))
                    && newTrimmed.length() == oldTrimmed.length();
        }

        // 1210-EDIT-TRANTYPE
        private void editTrantype() {
            tranFilter = editNumReqd("Tran Type code", newType);
            if (tranFilter == Edit.VALID) {
                newType = editedTypeCode(newType);
            }
        }

        @LegacyDefect(id = "COTRTUPC-negative-type-code",
                goldens = {"tran-type-maint-search-negative"},
                behavior = "'-1' passes the numeric edit and COMPUTE into the unsigned PIC 9(2) drops the sign, "
                        + "so it fetches type 01",
                fix = "reject a sign or a decimal point in the type code")
        private String editedTypeCode(String text) {
            return "%02d".formatted(Intrinsics.unsigned(Intrinsics.numval(text), TYPE_LENGTH));
        }

        // 1230-EDIT-ALPHANUM-REQD
        private Edit editAlphanumReqd(String name, String text) {
            if (isLowValues(text) || isSpaces(text) || trim(text).isEmpty()) {
                inputError = true;
                if (returnMsgOff()) {
                    returnMsg = string(name + " must be supplied.");
                }
                return Edit.BLANK;
            }
            StringBuilder rest = new StringBuilder(text.length());
            for (char c : text.toCharArray()) {
                rest.append(isAsciiLetterOrDigit(c) ? ' ' : c);
            }
            if (!trim(rest.toString()).isEmpty()) {
                inputError = true;
                if (returnMsgOff()) {
                    returnMsg = string(name + " can have numbers or alphabets only.");
                }
                return Edit.NOT_OK;
            }
            return Edit.VALID;
        }

        // 1245-EDIT-NUM-REQD
        private Edit editNumReqd(String name, String text) {
            if (isLowValues(text) || isSpaces(text) || trim(text).isEmpty()) {
                inputError = true;
                if (returnMsgOff()) {
                    returnMsg = string(name + " must be supplied.");
                }
                return Edit.BLANK;
            }
            if (!Intrinsics.testNumval(text)) {
                inputError = true;
                if (returnMsgOff()) {
                    returnMsg = string(name + " must be numeric.");
                }
                return Edit.NOT_OK;
            }
            if (Intrinsics.numval(text).compareTo(BigDecimal.ZERO) == 0) {
                inputError = true;
                if (returnMsgOff()) {
                    returnMsg = string(name + " must not be zero.");
                }
                return Edit.NOT_OK;
            }
            return Edit.VALID;
        }

        // 2000-DECIDE-ACTION
        private Optional<Outcome> decideAction() {
            if (action == DETAILS_NOT_FETCHED || aid == Key.PF12) {
                if (tranFilter == Edit.VALID) {
                    returnMsg = "";
                    readTrantype();
                    action = found ? SHOW_DETAILS : DETAILS_NOT_FOUND;
                } else if (action == CONFIRM_DELETE) {
                    returnMsg = WS_DELETE_WAS_CANCELLED;
                    action = DETAILS_NOT_FETCHED;
                } else if (action == CHANGES_OK_NOT_CONFIRMED) {
                    returnMsg = WS_UPDATE_WAS_CANCELLED;
                    action = CHANGES_BACKED_OUT;
                } else {
                    action = DETAILS_NOT_FETCHED;
                }
            } else if (action == SHOW_DETAILS) {
                if (!(inputError || returnMsgIs(NO_CHANGES_DETECTED) || returnMsgIs(WS_INVALID_KEY))) {
                    action = CHANGES_OK_NOT_CONFIRMED;
                }
            } else if (action == CHANGES_BACKED_OUT) {
                action = CHANGES_NOT_OK;
            } else if (aid == Key.PF5 && action == DETAILS_NOT_FOUND) {
                action = CREATE_NEW_RECORD;
            } else if (action == CHANGES_OKAYED_AND_DONE) {
                action = SHOW_DETAILS;
            } else if (action != CHANGES_NOT_OK && action != CHANGES_OK_NOT_CONFIRMED) {
                return Optional.of(abendRoutine("UNEXPECTED DATA SCENARIO"));
            }
            return Optional.empty();
        }

        // 3000-SEND-MAP
        private Outcome sendMap() {
            SymbolicMap<CTRTUPA> out = SymbolicMap.lowValues(CTRTUPA.MAP);
            setupScreenVars(out);
            setupInfomsg(out);
            setupScreenAttrs(out);
            setupInfomsgAttrs(out);
            setupPfkeyAttrs(out);
            return show(out, new State(returnTo, action, oldType, oldDesc, newType, newDesc));
        }

        // 3200-SETUP-SCREEN-VARS
        private void setupScreenVars(SymbolicMap<CTRTUPA> out) {
            if (entering) {
                return;
            }
            switch (action) {
                case DETAILS_NOT_FETCHED -> {
                }
                case CHANGES_NOT_OK, CHANGES_OK_NOT_CONFIRMED, CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED,
                        DETAILS_NOT_FOUND, CREATE_NEW_RECORD, CHANGES_OKAYED_AND_DONE -> {
                    out.set(TRTYPCD, newType);
                    out.set(TRTYDSC, newDesc);
                }
                default -> {
                    newType = spaces(TYPE_LENGTH);
                    newDesc = spaces(DESC_LENGTH);
                    descFlag = Edit.VALID;
                    out.set(TRTYPCD, oldType);
                    out.set(TRTYDSC, oldDesc);
                }
            }
        }

        // 3250-SETUP-INFOMSG
        private void setupInfomsg(SymbolicMap<CTRTUPA> out) {
            infoMsg = entering ? PROMPT_FOR_SEARCH_KEYS : switch (action) {
                case DETAILS_NOT_FETCHED -> PROMPT_FOR_SEARCH_KEYS;
                case DETAILS_NOT_FOUND -> PROMPT_CREATE_NEW_RECORD;
                case SHOW_DETAILS -> infoForShownDetails();
                case CHANGES_BACKED_OUT -> oldTypeBlank() ? PROMPT_FOR_SEARCH_KEYS : PROMPT_FOR_CHANGES;
                case CHANGES_NOT_OK -> PROMPT_FOR_CHANGES;
                case CONFIRM_DELETE -> PROMPT_DELETE_CONFIRM;
                case DELETE_FAILED, CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED -> INFORM_FAILURE;
                case DELETE_DONE -> CONFIRM_DELETE_SUCCESS;
                case CREATE_NEW_RECORD -> PROMPT_FOR_NEWDATA;
                case CHANGES_OK_NOT_CONFIRMED -> PROMPT_FOR_CONFIRMATION;
                case CHANGES_OKAYED_AND_DONE -> CONFIRM_UPDATE_SUCCESS;
                case START_DELETE -> isSpaces(infoMsg) ? PROMPT_FOR_SEARCH_KEYS : infoMsg;
            };
            int length = trim(infoMsg).length();
            int mid = (INFO_MSG_LENGTH - length) / 2 + 1;
            out.set(INFOMSG, " ".repeat(mid - 1) + infoMsg.substring(0, length));
            out.set(ERRMSG, returnMsg);
        }

        @LegacyDefect(id = "COTRTUPC-fetched-type-shows-search-prompt",
                goldens = {"tran-type-maint-search-found", "tran-type-maint-search-single-digit",
                        "tran-type-maint-search-negative", "tran-type-maint-edit-no-change",
                        "tran-type-maint-edit-case-only", "tran-type-maint-found-f5-invalid"},
                behavior = "a fetched type opens its description for edit but the info line says 'Enter transaction "
                        + "type to be maintained': TTUP-SHOW-DETAILS shares the WHEN of the search prompt",
                fix = "show FOUND-TRANTYPE-DATA or PROMPT-FOR-CHANGES for TTUP-SHOW-DETAILS")
        private String infoForShownDetails() {
            return PROMPT_FOR_SEARCH_KEYS;
        }

        // 3300-SETUP-SCREEN-ATTRS
        private void setupScreenAttrs(SymbolicMap<CTRTUPA> out) {
            out.attribute(TRTYPCD, FieldAttribute.BMPRF).attribute(TRTYDSC, FieldAttribute.BMPRF)
                    .attribute(INFOMSG, FieldAttribute.BMPRF);
            switch (action) {
                case DETAILS_NOT_FETCHED, DETAILS_NOT_FOUND -> out.attribute(TRTYPCD, FieldAttribute.BMFSE);
                case CHANGES_BACKED_OUT -> {
                    if (oldTypeBlank()) {
                        out.attribute(TRTYPCD, FieldAttribute.BMFSE);
                    } else {
                        unprotectFewAttrs(out);
                    }
                }
                case SHOW_DETAILS, CHANGES_NOT_OK, CREATE_NEW_RECORD -> unprotectFewAttrs(out);
                case CHANGES_OK_NOT_CONFIRMED, CHANGES_OKAYED_AND_DONE, CONFIRM_DELETE, START_DELETE, DELETE_DONE,
                        DELETE_FAILED -> {
                }
                case CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED -> out.attribute(TRTYPCD, FieldAttribute.BMFSE);
            }
            if (action == DETAILS_NOT_FETCHED || action == DETAILS_NOT_FOUND || tranFilter == Edit.NOT_OK
                    || tranFilter == Edit.BLANK || action == CHANGES_OKAYED_AND_DONE
                    || (action == CHANGES_BACKED_OUT && oldTypeBlank())) {
                out.cursor(TRTYPCD);
            } else if (action == CREATE_NEW_RECORD || returnMsgIs(NO_CHANGES_DETECTED) || descFlag == Edit.NOT_OK
                    || descFlag == Edit.BLANK || action.changesMade() || action == CHANGES_BACKED_OUT
                    || action == SHOW_DETAILS) {
                out.cursor(TRTYDSC);
            } else {
                out.cursor(TRTYPCD);
            }
            if (tranFilter == Edit.NOT_OK || action == DELETE_FAILED) {
                out.color(TRTYPCD, Color.RED);
            }
            if (tranFilter == Edit.BLANK && !entering) {
                out.set(TRTYPCD, "*").color(TRTYPCD, Color.RED);
            }
            if (action == DETAILS_NOT_FETCHED || action == DETAILS_NOT_FOUND || tranFilter == Edit.BLANK
                    || tranFilter == Edit.NOT_OK) {
                return;
            }
            // CSSETATY for the description
            if ((descFlag == Edit.NOT_OK || descFlag == Edit.BLANK) && !entering) {
                out.color(TRTYDSC, Color.RED);
                if (descFlag == Edit.BLANK) {
                    out.set(TRTYDSC, "*");
                }
            }
        }

        // 3320-UNPROTECT-FEW-ATTRS
        private void unprotectFewAttrs(SymbolicMap<CTRTUPA> out) {
            out.attribute(TRTYDSC, FieldAttribute.BMFSE).attribute(INFOMSG, FieldAttribute.BMPRF);
        }

        // 3390-SETUP-INFOMSG-ATTRS
        private void setupInfomsgAttrs(SymbolicMap<CTRTUPA> out) {
            out.attribute(INFOMSG, isSpaces(infoMsg) ? FieldAttribute.BMDAR : FieldAttribute.BMASB);
        }

        // 3391-SETUP-PFKEY-ATTRS
        private void setupPfkeyAttrs(SymbolicMap<CTRTUPA> out) {
            out.attribute(FKEYS, legendAttribute());
            if (action == SHOW_DETAILS || action == CONFIRM_DELETE) {
                out.attribute(FKEY04, FieldAttribute.BMASB);
            }
            if (action == CHANGES_OK_NOT_CONFIRMED || action == DETAILS_NOT_FOUND) {
                out.attribute(FKEY05, FieldAttribute.BMASB);
            }
            if (action == CHANGES_OK_NOT_CONFIRMED || action == SHOW_DETAILS || action == DETAILS_NOT_FOUND
                    || action == CONFIRM_DELETE || action == CREATE_NEW_RECORD) {
                out.attribute(FKEY12, FieldAttribute.BMASB);
            }
        }

        @LegacyDefect(id = "COTRTUPC-delete-confirm-legend-dark-unprotected",
                goldens = {"tran-type-maint-found-f4-confirm", "tran-type-maint-delete-confirm-enter-rejected"},
                behavior = "on the delete confirmation the ENTER=Process F3=Exit legend gets DFHBMDAR, which hides it "
                        + "but also unprotects it, so the user can type into the legend",
                fix = "use a protected dark attribute for the legend")
        private FieldAttribute legendAttribute() {
            return action == CONFIRM_DELETE ? FieldAttribute.BMDAR : FieldAttribute.BMASB;
        }

        // 9000-READ-TRANTYPE
        private void readTrantype() {
            oldType = spaces(TYPE_LENGTH);
            oldDesc = spaces(DESC_LENGTH);
            infoMsg = "";
            getTransactionType().ifPresent(this::storeFetchedData);
        }

        // 9100-GET-TRANSACTION-TYPE
        private Optional<Row> getTransactionType() {
            Fetched<Row> fetched = db2.selectInto(SELECT_TRANSACTION_TYPE, ROW, newType);
            Sqlca sqlca = fetched.sqlca();
            if (sqlca.sqlcode() == 0) {
                found = true;
            } else if (sqlca.sqlcode() == Sqlca.NOT_FOUND) {
                inputError = true;
                tranFilter = Edit.NOT_OK;
                if (returnMsgOff()) {
                    returnMsg = WS_RECORD_NOT_FOUND;
                }
            } else if (sqlca.sqlcode() < 0) {
                inputError = true;
                tranFilter = Edit.NOT_OK;
                if (returnMsgOff()) {
                    returnMsg = string("Error accessing: TRANSACTION_TYPE table. SQLCODE:" + sqlca.sqlcodeEdited() + ":"
                            + sqlerrm(sqlca));
                }
            }
            return tranFilter == Edit.NOT_OK ? Optional.empty() : fetched.row();
        }

        // 9500-STORE-FETCHED-DATA
        private void storeFetchedData(Row row) {
            oldType = pic(row.type(), TYPE_LENGTH);
            oldDesc = pic(row.description(), DESC_LENGTH);
        }

        // 9600-WRITE-PROCESSING
        private void writeProcessing() {
            String description = descriptionHostVariable();
            Sqlca sqlca = db2.execute(UPDATE_TRANSACTION_TYPE, description, newType);
            String dispSqlcode = sqlca.sqlcodeEdited();
            if (sqlca.sqlcode() == 0) {
                db2.syncpoint();
            } else if (sqlca.sqlcode() == Sqlca.NOT_FOUND) {
                insertRecord(description, dispSqlcode);
            } else if (sqlca.sqlcode() == -911) {
                inputError = true;
                if (returnMsgOff()) {
                    returnMsg = COULD_NOT_LOCK_REC_FOR_UPDATE;
                }
            } else if (sqlca.sqlcode() < 0) {
                updateFailed(sqlca, dispSqlcode);
            }
            if (returnMsgIs(COULD_NOT_LOCK_REC_FOR_UPDATE)) {
                action = CHANGES_OKAYED_LOCK_ERROR;
            } else if (returnMsgIs(TABLE_UPDATE_FAILED)) {
                action = CHANGES_OKAYED_BUT_FAILED;
            } else {
                action = CHANGES_OKAYED_AND_DONE;
            }
        }

        @LegacyDefect(id = "COTRTUPC-description-padded-to-50",
                goldens = {"tran-type-maint-edit-save", "tran-type-maint-cancelled-then-save",
                        "tran-type-maint-add-save", "tran-type-maint-notfound-non-numeric-insert"},
                behavior = "DCL-TR-DESCRIPTION-LEN is set to the length of the 50-byte field rather than of the "
                        + "trimmed text, so every saved VARCHAR description is padded with blanks to 50",
                fix = "set the length to that of the trimmed description")
        private String descriptionHostVariable() {
            return pic(trim(newDesc), DESC_LENGTH);
        }

        @LegacyDefect(id = "COTRTUPC-failed-save-reported-committed",
                behavior = "an UPDATE that fails with a negative SQLCODE other than -911 sets TABLE-UPDATE-FAILED, "
                        + "but the STRING of the SQL error overwrites that message, so the state becomes "
                        + "TTUP-CHANGES-OKAYED-AND-DONE and the info line says 'Changes committed to database'",
                fix = "decide the state from the SQLCODE rather than from the message text")
        private void updateFailed(Sqlca sqlca, String dispSqlcode) {
            returnMsg = TABLE_UPDATE_FAILED;
            returnMsg = string("Error updating: TRANSACTION_TYPE Table. SQLCODE:" + dispSqlcode + ":" + sqlerrm(sqlca));
        }

        // 9700-INSERT-RECORD
        @LegacyDefect(id = "COTRTUPC-insert-error-shows-update-sqlcode",
                behavior = "a failed INSERT reports the SQLCODE of the UPDATE before it, always 100, next to the "
                        + "INSERT's own message, and like a failed UPDATE it is then reported as committed",
                fix = "move the INSERT's SQLCODE to WS-DISP-SQLCODE and decide the state from it")
        private void insertRecord(String description, String dispSqlcode) {
            Sqlca sqlca = db2.execute(INSERT_TRANSACTION_TYPE, newType, description);
            if (sqlca.sqlcode() == 0) {
                db2.syncpoint();
            } else {
                returnMsg = TABLE_UPDATE_FAILED;
                returnMsg = string("Error inserting record into: TRANSACTION_TYPE Table. SQLCODE:" + dispSqlcode + ":"
                        + sqlerrm(sqlca));
            }
        }

        // 9800-DELETE-PROCESSING
        private void deleteProcessing() {
            Sqlca sqlca = db2.execute(DELETE_TRANSACTION_TYPE, oldType);
            String dispSqlcode = sqlca.sqlcodeEdited();
            if (sqlca.sqlcode() == 0) {
                action = DELETE_DONE;
                db2.syncpoint();
            } else if (sqlca.sqlcode() == -532) {
                childRowsBlockDelete(sqlca, dispSqlcode);
            } else {
                returnMsg = RECORD_DELETE_FAILED;
                action = DELETE_FAILED;
                returnMsg = string("Delete failed with message:SQLCODE :" + dispSqlcode + ":" + sqlerrm(sqlca));
            }
        }

        @LegacyDefect(id = "COTRTUPC-fk-delete-left-in-progress",
                goldens = {"tran-type-maint-delete-with-children"},
                behavior = "a delete rejected with -532 because categories still refer to the type never sets "
                        + "TTUP-DELETE-FAILED, so the state stays TTUP-START-DELETE and the next ENTER falls to "
                        + "2000-DECIDE-ACTION's WHEN OTHER and abends 9999",
                fix = "set TTUP-DELETE-FAILED on -532 as on every other failure")
        private void childRowsBlockDelete(Sqlca sqlca, String dispSqlcode) {
            returnMsg = RECORD_DELETE_FAILED;
            returnMsg = string("Please delete associated child records first:SQLCODE :" + dispSqlcode + ":"
                    + sqlerrm(sqlca) + sqlerrm(sqlca));
        }

        /** ABEND-ROUTINE: SEND FROM(ABEND-DATA) ERASE, then ABEND ABCODE('9999'). */
        private Outcome abendRoutine(String message) {
            String abendData = "9999" + pic(REF.program().value(), 8) + spaces(50) + pic(message, 72);
            return sendTextAndAbend(abendData, "9999");
        }

        private boolean returnMsgOff() {
            return isSpaces(returnMsg);
        }

        private boolean returnMsgIs(String value) {
            return same(returnMsg, value);
        }

        private boolean oldTypeBlank() {
            return isLowValues(oldType) || isSpaces(oldType);
        }
    }

    @LegacyDefect(id = "COTRTUPC-sqlerrm-length-bytes-in-message",
            behavior = "the SQL error messages STRING the whole SQLERRM group, so its binary SQLERRML shows on the "
                    + "screen as two bytes before the message tokens",
            fix = "STRING SQLERRMC(1:SQLERRML), or DSNTIAR's formatted text")
    private static String sqlerrm(Sqlca sqlca) {
        int length = sqlca.sqlerrml();
        return "" + (char) (length >> 8) + (char) (length & 0xff) + pic(sqlca.sqlerrmc(), SQLERRMC_LENGTH);
    }

    /** STRING ... DELIMITED BY SIZE INTO WS-RETURN-MSG, which stops at the end of the field. */
    private static String string(String text) {
        return text.length() > RETURN_MSG_LENGTH ? text.substring(0, RETURN_MSG_LENGTH) : text;
    }

    /** MOVE text TO a PIC X(length). */
    private static String pic(String text, int length) {
        return text.length() >= length ? text.substring(0, length) : text + spaces(length - text.length());
    }

    /** FUNCTION TRIM: leading and trailing spaces, not LOW-VALUES. */
    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == ' ') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(start, end);
    }

    /** An alphanumeric comparison: the shorter operand is padded with spaces. */
    private static boolean same(String a, String b) {
        int length = Math.max(a.length(), b.length());
        return pic(a, length).equals(pic(b, length));
    }

    /** FUNCTION UPPER-CASE, which GnuCOBOL applies to ASCII letters only. */
    private static String upperCase(String text) {
        StringBuilder upper = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            upper.append(c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c);
        }
        return upper.toString();
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isSpaces(String text) {
        return text.chars().allMatch(c -> c == ' ');
    }

    private static boolean isLowValues(String text) {
        return !text.isEmpty() && text.chars().allMatch(c -> c == 0);
    }

    private static String spaces(int length) {
        return " ".repeat(length);
    }

    private static String lowValues(int length) {
        return "\0".repeat(length);
    }
}
