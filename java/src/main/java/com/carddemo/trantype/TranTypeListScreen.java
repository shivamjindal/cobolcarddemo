package com.carddemo.trantype;

import static com.carddemo.online.maps.CTRTLIA.ERRMSG;
import static com.carddemo.online.maps.CTRTLIA.INFOMSG;
import static com.carddemo.online.maps.CTRTLIA.PAGENO;
import static com.carddemo.online.maps.CTRTLIA.TRDESC;
import static com.carddemo.online.maps.CTRTLIA.TRTYPE;

import java.io.Serializable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.db2.Cursor;
import com.carddemo.db2.Db2;
import com.carddemo.db2.Dsntiac;
import com.carddemo.db2.Fetched;
import com.carddemo.db2.Sqlca;
import com.carddemo.identity.SignedOnUser;
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
import com.carddemo.online.SendMode;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.CTRTLIA;
import com.carddemo.users.UserId;
import com.carddemo.users.UserTypeCode;

/**
 * COTRTLIC, seven Db2 transaction types a page, filtered by type code and description. A row marked D is deleted
 * and a row marked U updated with the description typed over it, each confirmed with F10. The list is re-read
 * from Db2 on almost every key, so the COMMAREA keeps only the page's first and last codes and the rows shown.
 */
@Component
public final class TranTypeListScreen extends Screen<CTRTLIA, TranTypeListScreen.Action, TranTypeListScreen.State, NoHandoff> {

    public static final ScreenRef<NoHandoff> REF = ScreenRef.of("COTRTLIC", "CTLI", "tran-type-list", NoHandoff.class);

    static final ProgramName ADD_PROGRAM = ProgramName.of("COTRTUPC");

    private static final int MAX_SCREEN_LINES = 7;
    private static final int DESCRIPTION_LENGTH = 50;
    private static final int INFO_MSG_LENGTH = 45;
    private static final int RETURN_MSG_LENGTH = 75;
    private static final int LONG_MSG_LENGTH = 800;
    private static final int DSNTIAC_LRECL = 72;
    private static final String DSNTIAC_AREA = " ".repeat(720);

    private static final String INFORM_REC_ACTIONS = "Type U to update, D to delete any record";
    private static final String INFORM_DELETE = "Delete HIGHLIGHTED row ? Press F10 to confirm";
    private static final String INFORM_UPDATE = "Update HIGHLIGHTED row. Press F10 to save";
    private static final String INFORM_DELETE_SUCCESS = "HIGHLIGHTED row deleted.Hit Enter to continue";
    private static final String INFORM_UPDATE_SUCCESS = "HIGHLIGHTED row was updated";
    private static final String MESG_NO_RECORDS_FOUND = "No records found for this search condition.";
    private static final String MESG_NO_MORE_RECORDS = "No more pages for these search conditions";

    // Db2's LIKE has no escape character unless the statement names one; Postgres defaults to backslash.
    private static final String FILTERS = """
              AND ((? = '1' AND TR_TYPE = ?) OR (? <> '1'))
              AND ((? = '1' AND TR_DESCRIPTION LIKE TRIM(?) ESCAPE '') OR (? <> '1'))
            """;
    private static final String FORWARD = "SELECT TR_TYPE, TR_DESCRIPTION FROM CARDDEMO.TRANSACTION_TYPE"
            + " WHERE TR_TYPE >= ?" + FILTERS + " ORDER BY TR_TYPE";
    private static final String BACKWARD = "SELECT TR_TYPE, TR_DESCRIPTION FROM CARDDEMO.TRANSACTION_TYPE"
            + " WHERE TR_TYPE < ?" + FILTERS + " ORDER BY TR_TYPE DESC";
    private static final String COUNT = "SELECT COUNT(1) FROM CARDDEMO.TRANSACTION_TYPE WHERE 1 = 1" + FILTERS;

    /** CSSTRPFY folds PF13 to PF24 onto PF1 to PF12. */
    enum Action implements ScreenAction {
        CONTINUE(Key.ENTER, Key.ENTER), ADD(Key.PF2, Key.PF2), EXIT(Key.PF3, Key.PF3),
        PAGE_UP(Key.PF7, Key.PF7), PAGE_DOWN(Key.PF8, Key.PF8), SAVE(Key.PF10, Key.PF10),
        ADD_SHIFTED(Key.PF14, Key.PF2), EXIT_SHIFTED(Key.PF15, Key.PF3), PAGE_UP_SHIFTED(Key.PF19, Key.PF7),
        PAGE_DOWN_SHIFTED(Key.PF20, Key.PF8), SAVE_SHIFTED(Key.PF22, Key.PF10);

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

    /** WS-CA-DELETE-FLAG and WS-CA-UPDATE-FLAG. NOT-REQUESTED and SUCCEEDED are both LOW-VALUES. */
    enum CaFlag { SPACE, REQUESTED, SUCCEEDED }

    /** WS-CA-NEXT-PAGE-IND: 'Y', LOW-VALUES, or the SPACE that INITIALIZE leaves, which is neither. */
    enum NextPage { SPACE, EXISTS, NOT_EXISTS }

    /** WS-CA-EACH-ROW-OUT; a null row is LOW-VALUES. */
    record TypeRow(String code, String description) implements Serializable {}

    /**
     * WS-THIS-PROGCOMMAREA.
     *
     * @param typeCd        WS-CA-TYPE-CD, null for LOW-VALUES
     * @param typeDesc      WS-CA-TYPE-DESC, null for LOW-VALUES
     * @param rows          WS-CA-SCREEN-ROWS-OUT
     * @param screenNum     WS-CA-SCREEN-NUM, a PIC 9(1)
     * @param lastPageShown CA-LAST-PAGE-SHOWN, else CA-LAST-PAGE-NOT-SHOWN
     * @param signedOn      whether a sign-on filled CDEMO-USER-ID; CTLI typed on a clear screen starts without one
     */
    record State(String typeCd, String typeDesc, List<TypeRow> rows, int rowSelected, String lastTrCode,
            String firstTrCode, int screenNum, boolean lastPageShown, NextPage nextPage, CaFlag delete, CaFlag update,
            boolean signedOn) implements ScreenState {

        /** INITIALIZE WS-THIS-PROGCOMMAREA, SET CA-FIRST-PAGE, SET CA-LAST-PAGE-NOT-SHOWN. */
        static State initial(boolean signedOn) {
            TypeRow[] spaces = new TypeRow[MAX_SCREEN_LINES];
            Arrays.fill(spaces, new TypeRow("", ""));
            return new State("", "", Arrays.asList(spaces), 0, "", "", 1, false, NextPage.SPACE, CaFlag.SPACE,
                    CaFlag.SPACE, signedOn);
        }
    }

    /** WS-EDIT-TYPE-FLAG and WS-EDIT-DESC-FLAG, by the host variable value the cursors compare. */
    enum FilterEdit {
        BLANK(" "), VALID("1"), NOT_OK("0");

        private final String host;

        FilterEdit(String host) {
            this.host = host;
        }
    }

    /** WS-ARRAY-DESCRIPTION-FLGS. */
    enum DescriptionEdit { VALID, NOT_OK, BLANK }

    private record Row(CTRTLIA sel, CTRTLIA type, CTRTLIA desc) {}

    private static final List<Row> ROWS = IntStream.rangeClosed(1, MAX_SCREEN_LINES)
            .mapToObj(n -> new Row(CTRTLIA.valueOf("TRTSEL" + n), CTRTLIA.valueOf("TRTTYP" + n),
                    CTRTLIA.valueOf("TRTYPD" + n)))
            .toList();

    private final Db2 db2;

    TranTypeListScreen(Db2 db2) {
        super(REF, CTRTLIA.MAP, Action.class, State.class, Access.ANYONE);
        this.db2 = db2;
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return new Task(null, State.initial(arrival.user().isPresent()), Key.ENTER).main();
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<CTRTLIA> map, State state) {
        return new Task(map, state, action.aid).main();
    }

    /** PFK-INVALID: the key is taken as ENTER. */
    @Override
    protected Outcome invalidKey(SymbolicMap<CTRTLIA> map, State state) {
        return new Task(map, state, Key.ENTER).main();
    }

    @LegacyDefect(id = "COTRTLIC-no-sign-on",
            goldens = {"tran-type-list-direct-transid", "tran-type-list-direct-transid-exit"},
            behavior = "CTLI typed on a clear screen starts with EIBCALEN = 0, and the program builds its own "
                    + "COMMAREA marked admin instead of sending the user to sign on, so anyone at a terminal can list, "
                    + "update and delete transaction types and reach the admin menu with F3",
            fix = "XCTL to COSGN00C when EIBCALEN = 0")
    private Outcome xctl(boolean signedOn, ProgramName program) {
        return signedOn ? transfer(program)
                : signIn(new SignedOnUser(UserId.SPACES, new UserTypeCode('A'), "", ""), program);
    }

    /** One run of the program: its working storage and WS-THIS-PROGCOMMAREA. */
    private final class Task {
        private final SymbolicMap<CTRTLIA> received;
        private Key aid;

        private String caTypeCd;
        private String caTypeDesc;
        private final TypeRow[] caRows;
        private int caRowSelected;
        private String caLastTrCode;
        private String caFirstTrCode;
        private int caScreenNum;
        private boolean caLastPageShown;
        private NextPage caNextPage;
        private CaFlag caDelete;
        private CaFlag caUpdate;
        private final boolean signedOn;

        private String inTypeCd;
        private String inTypeDesc;
        private final String[] selects = {" ", " ", " ", " ", " ", " ", " "};
        private final boolean[] selectErrors = new boolean[MAX_SCREEN_LINES];
        private final String[] codeIn = new String[MAX_SCREEN_LINES];
        private final String[] descIn = new String[MAX_SCREEN_LINES];

        private boolean inputError;
        private FilterEdit typeFlag = FilterEdit.BLANK;
        private FilterEdit descFlag = FilterEdit.BLANK;
        private String typeCdFilter = "";
        private String typeDescFilter = "";
        private boolean typeFilterChanged;
        private boolean descFilterChanged;
        private boolean rowSelectionChanged;
        private boolean badActionsSelected;
        private DescriptionEdit descriptionEdit = DescriptionEdit.VALID;
        private boolean changesHaveOccurred;
        private boolean deleted;
        private boolean updateCompleted;
        private boolean protectSelectRows;
        private int iSelected;
        private int actionsRequested;
        private int deletesRequested;
        private int updatesRequested;
        private int validActionsSelected;
        private long recordsCount;
        private boolean db2Error;
        private String returnMsg = "";
        private String longMsg = "";
        private String infoMsg = "";
        private SymbolicMap<CTRTLIA> out;

        Task(SymbolicMap<CTRTLIA> received, State state, Key aid) {
            this.received = received;
            this.aid = aid;
            this.caTypeCd = state.typeCd();
            this.caTypeDesc = state.typeDesc();
            this.caRows = state.rows().toArray(TypeRow[]::new);
            this.caRowSelected = state.rowSelected();
            this.caLastTrCode = state.lastTrCode();
            this.caFirstTrCode = state.firstTrCode();
            this.caScreenNum = state.screenNum();
            this.caLastPageShown = state.lastPageShown();
            this.caNextPage = state.nextPage();
            this.caDelete = state.delete();
            this.caUpdate = state.update();
            this.signedOn = state.signedOn();
        }

        /** 0000-MAIN, from the RECEIVE on: the COMMAREA set-up before it is {@link State#initial} and enter. */
        Outcome main() {
            if (received != null) {
                receiveScreen();
                editInputs();
            }
            boolean pfkValid = aid == Key.ENTER || aid == Key.PF2 || aid == Key.PF3 || aid == Key.PF7
                    || aid == Key.PF8 || aid == Key.PF10 && (caDelete == CaFlag.REQUESTED || caUpdate == CaFlag.REQUESTED);
            if (!pfkValid) {
                aid = Key.ENTER;
            }
            if (aid == Key.PF3) {
                db2.syncpoint();
                return xctl(signedOn, AdminMenuScreen.REF.program());
            }
            if (aid == Key.PF2) {
                return xctl(signedOn, ADD_PROGRAM);
            }
            if (aid != Key.PF8) {
                caLastPageShown = false;
            }
            if (aid == Key.PF10 && !confirmed()) {
                aid = Key.ENTER;
            }
            primingQuery();
            if (db2Error) {
                return end(longMsg);
            }
            if (inputError) {
                rejectedInput();
            } else if (aid == Key.PF7 && caScreenNum == 1) {
                readForward(caFirstTrCode);
                sendMap();
            } else if (aid == Key.PF8 && caNextPage == NextPage.EXISTS) {
                caScreenNum = screenNumAfter(caScreenNum, +1);
                readForward(caLastTrCode);
                Arrays.fill(selects, " ");
                sendMap();
            } else if (aid == Key.PF7) {
                caScreenNum = screenNumAfter(caScreenNum, -1);
                readBackwards(caFirstTrCode);
                Arrays.fill(selects, " ");
                sendMap();
            } else if (aid == Key.ENTER && deletesRequested > 0) {
                readIfFiltersOk();
                sendMap();
            } else if (aid == Key.PF10 && deletesRequested > 0) {
                deleteRecord();
                deleted = caDelete == CaFlag.SUCCEEDED;
                sendMap();
                if (deleted) {
                    return deletedRowStays();
                }
            } else if (aid == Key.ENTER && updatesRequested > 0) {
                readIfFiltersOk();
                sendMap();
            } else if (aid == Key.PF10 && updatesRequested > 0) {
                updateRecord();
                updateCompleted = caUpdate == CaFlag.SUCCEEDED;
                readForward(caFirstTrCode);
                sendMap();
            } else {
                readForward(caFirstTrCode);
                sendMap();
            }
            return show(out, SendMode.ERASE_CURSOR, commarea());
        }

        @LegacyDefect(id = "COTRTLIC-switch-to-delete-without-confirm",
                goldens = {"tran-type-list-update-switched-to-delete"},
                behavior = "F10 confirms whatever is selected now while either request flag is set and the row and "
                        + "filters are unchanged, so a row shown for update, changed to D, is deleted by F10 without "
                        + "the delete confirmation",
                fix = "keep F10 only when the pending request matches the action now selected on the row")
        private boolean confirmed() {
            return (caDelete == CaFlag.REQUESTED || caUpdate == CaFlag.REQUESTED)
                    && !typeFilterChanged && !descFilterChanged && !rowSelectionChanged;
        }

        @LegacyDefect(id = "COTRTLIC-rejected-filter-keeps-rows",
                goldens = {"tran-type-list-type-filter-non-numeric", "tran-type-list-type-filter-no-match",
                        "tran-type-list-desc-filter-lowercase", "tran-type-list-combined-filters-no-match"},
                behavior = "a rejected filter skips the read but has already cleared the paging variables, so the "
                        + "previous rows stay on screen, protected, above page number 0",
                fix = "clear the rows when a filter is rejected, or keep the paging variables until the filter is accepted")
        private void rejectedInput() {
            readIfFiltersOk();
            sendMap();
        }

        private void readIfFiltersOk() {
            if (typeFlag != FilterEdit.NOT_OK && descFlag != FilterEdit.NOT_OK) {
                readForward(caFirstTrCode);
            }
        }

        @LegacyDefect(id = "COTRTLIC-deleted-row-stays",
                goldens = {"tran-type-list-delete-confirm"},
                behavior = "the screen after a delete is built before the list is re-read, so the deleted row stays "
                        + "on it until the next ENTER",
                fix = "re-read the page after the delete and before SEND MAP")
        private Outcome deletedRowStays() {
            return show(out, SendMode.ERASE_CURSOR, State.initial(signedOn));
        }

        private State commarea() {
            return new State(caTypeCd, caTypeDesc, Arrays.asList(caRows.clone()), caRowSelected, caLastTrCode,
                    caFirstTrCode, caScreenNum, caLastPageShown, caNextPage, caDelete, caUpdate, signedOn);
        }

        /** 1100-RECEIVE-SCREEN. */
        private void receiveScreen() {
            inTypeCd = value(received, TRTYPE);
            inTypeDesc = value(received, TRDESC);
            for (int i = 0; i < MAX_SCREEN_LINES; i++) {
                Row row = ROWS.get(i);
                selects[i] = value(received, row.sel());
                codeIn[i] = value(received, row.type());
                String typed = value(received, row.desc());
                descIn[i] = typed == null || typed.isEmpty() || typed.equals("*") ? null : trim(typed);
            }
        }

        /** 1200-EDIT-INPUTS. */
        private void editInputs() {
            inputError = false;
            protectSelectRows = false;
            editArray();
            editDesc();
            editTypeCd();
            crossEdits();
        }

        @LegacyDefect(id = "COTRTLIC-selection-survives-filter-change",
                behavior = "1210-EDIT-ARRAY means to drop the selections when a filter changed, but it runs before the "
                        + "filter edits set those flags, so a D or U typed with a new filter is acted on against the "
                        + "rows of the old page",
                fix = "edit the filters before the selections")
        private void editArray() {
            int noActions = 0;
            for (String select : selects) {
                if (select == null || select.isEmpty()) {
                    noActions++;
                } else if (select.equals("D")) {
                    deletesRequested++;
                } else if (select.equals("U")) {
                    updatesRequested++;
                }
            }
            actionsRequested = MAX_SCREEN_LINES - noActions;
            validActionsSelected = deletesRequested + updatesRequested;
            boolean moreThan1Action = actionsRequested >= 2;
            iSelected = 0;
            badActionsSelected = false;
            for (int i = MAX_SCREEN_LINES; i > 0; i--) {
                String select = selects[i - 1];
                if ("D".equals(select) || "U".equals(select)) {
                    iSelected = i;
                    if (moreThan1Action) {
                        selectErrors[i - 1] = true;
                        badActionsSelected = true;
                    }
                    if (select.equals("U")) {
                        editArrayDesc(i);
                    }
                } else if (select != null && !select.isEmpty()) {
                    inputError = true;
                    selectErrors[i - 1] = true;
                    badActionsSelected = true;
                    returnMsg = "Action code selected is invalid";
                }
            }
            rowSelectionChanged = iSelected != caRowSelected;
            if (rowSelectionChanged) {
                caRowSelected = iSelected;
            }
            if (moreThan1Action) {
                inputError = true;
                returnMsg = "Please select only 1 action";
            }
        }

        @LegacyDefect(id = "COTRTLIC-case-only-change",
                goldens = {"tran-type-list-select-update", "tran-type-list-update-case-only",
                        "tran-type-list-update-unchanged-save"},
                behavior = "a description that differs only in case is reported as 'No change detected', yet the "
                        + "update is still pending and F10 writes the typed text",
                fix = "compare the descriptions exactly, or drop the pending update when no change is detected")
        private void editArrayDesc(int i) {
            changesHaveOccurred = false;
            String typed = descIn[i - 1];
            String current = caRows[i - 1] == null ? null : caRows[i - 1].description();
            if (upperTrim(typed).equals(upperTrim(current))) {
                returnMsg = "No change detected with respect to database values.";
                return;
            }
            changesHaveOccurred = true;
            descriptionEdit = editAlphanumReqd("Transaction Desc", typed);
        }

        /** 1240-EDIT-ALPHANUM-REQD. */
        private DescriptionEdit editAlphanumReqd(String variableName, String value) {
            if (value == null || value.isBlank()) {
                inputError = true;
                if (returnMsg.isEmpty()) {
                    returnMsg = variableName + " must be supplied.";
                }
                return DescriptionEdit.BLANK;
            }
            if (!value.chars().allMatch(c -> c == ' ' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9')) {
                inputError = true;
                if (returnMsg.isEmpty()) {
                    returnMsg = variableName + " can have numbers or alphabets only.";
                }
                return DescriptionEdit.NOT_OK;
            }
            return DescriptionEdit.VALID;
        }

        /** 1220-EDIT-TYPECD and its exit paragraph, which every path runs. */
        private void editTypeCd() {
            typeFlag = FilterEdit.BLANK;
            if (inTypeCd == null || inTypeCd.isEmpty() || inTypeCd.equals("00")) {
                typeCdFilter = "00";
            } else if (!inTypeCd.matches("[0-9]{2}")) {
                inputError = true;
                typeFlag = FilterEdit.NOT_OK;
                protectSelectRows = true;
                returnMsg = "TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER";
            } else {
                typeCdFilter = inTypeCd;
                typeFlag = FilterEdit.VALID;
            }
            boolean unchanged = equal(inTypeCd, caTypeCd) || typeFlag == FilterEdit.BLANK
                    && (caTypeCd == null || caTypeCd.isEmpty() || caTypeCd.equals("00"));
            typeFilterChanged = !unchanged;
            if (typeFilterChanged) {
                initializePagingVariables();
                caTypeCd = inTypeCd;
            }
        }

        /** 1230-EDIT-DESC and its exit paragraph. */
        private void editDesc() {
            descFlag = FilterEdit.BLANK;
            if (inTypeDesc != null && !inTypeDesc.isEmpty()) {
                descFlag = FilterEdit.VALID;
                typeDescFilter = "%" + trim(inTypeDesc) + "%";
            }
            boolean unchanged = equal(inTypeDesc, caTypeDesc) || descFlag == FilterEdit.BLANK
                    && (caTypeDesc == null || caTypeDesc.isEmpty());
            descFilterChanged = !unchanged;
            if (descFilterChanged) {
                initializePagingVariables();
                caTypeDesc = inTypeDesc;
            }
        }

        /** INITIALIZE WS-CA-PAGING-VARIABLES: a PIC 9(1) of 0 is also CA-LAST-PAGE-SHOWN. */
        private void initializePagingVariables() {
            caLastTrCode = "";
            caFirstTrCode = "";
            caScreenNum = 0;
            caLastPageShown = true;
            caNextPage = NextPage.SPACE;
        }

        /** 1290-CROSS-EDITS. */
        private void crossEdits() {
            if (typeFlag != FilterEdit.VALID && descFlag != FilterEdit.VALID) {
                return;
            }
            checkFilters();
            if (recordsCount == 0) {
                inputError = true;
                if (typeFlag == FilterEdit.VALID) {
                    typeFlag = FilterEdit.NOT_OK;
                }
                if (descFlag == FilterEdit.VALID) {
                    descFlag = FilterEdit.NOT_OK;
                }
                protectSelectRows = true;
                returnMsg = "No Records found for these filter conditions";
            }
        }

        private Object[] filterHostVariables() {
            return new Object[] {typeFlag.host, typeCdFilter, typeFlag.host, descFlag.host, typeDescFilter, descFlag.host};
        }

        private Object[] withStartKey(String startKey) {
            Object[] filters = filterHostVariables();
            Object[] all = new Object[filters.length + 1];
            all[0] = startKey;
            System.arraycopy(filters, 0, all, 1, filters.length);
            return all;
        }

        /** 2000-SEND-MAP, up to the SEND. */
        private void sendMap() {
            screenInit();
            setupArrayAttribs();
            screenArrayInit();
            setupScreenAttrs();
            setupMessage();
        }

        /** 2100-SCREEN-INIT. DFHBMDAR is not a color, so the terminal ignores it. */
        private void screenInit() {
            out = SymbolicMap.lowValues(CTRTLIA.MAP);
            out.set(PAGENO, String.valueOf(caScreenNum));
            infoMsg = "";
            out.set(INFOMSG, infoMsg);
        }

        /** 2200-SETUP-ARRAY-ATTRIBS. */
        private void setupArrayAttribs() {
            for (int i = MAX_SCREEN_LINES; i > 0; i--) {
                Row row = ROWS.get(i - 1);
                String select = selects[i - 1];
                out.attribute(row.desc(), FieldAttribute.BMPRF);
                if (caRows[i - 1] == null || protectSelectRows) {
                    out.attribute(row.sel(), FieldAttribute.BMPRO);
                    continue;
                }
                if (selectErrors[i - 1]) {
                    out.color(row.sel(), Color.RED).cursor(row.sel());
                }
                if ("D".equals(select) && validActionsSelected == 1 && !badActionsSelected) {
                    out.color(row.type(), Color.NEUTR).color(row.desc(), Color.NEUTR).cursor(row.sel());
                }
                if ("U".equals(select) && validActionsSelected == 1 && !badActionsSelected) {
                    out.color(row.type(), Color.NEUTR);
                    if (updateCompleted) {
                        out.cursor(row.sel()).color(row.desc(), Color.NEUTR);
                    } else {
                        out.cursor(row.desc()).attribute(row.desc(), FieldAttribute.BMFSE);
                        if (descriptionEdit != DescriptionEdit.VALID) {
                            out.color(row.desc(), Color.RED);
                        }
                    }
                }
                out.attribute(row.sel(), FieldAttribute.BMFSE);
            }
        }

        /** 2300-SCREEN-ARRAY-INIT. */
        private void screenArrayInit() {
            for (int i = 1; i <= MAX_SCREEN_LINES; i++) {
                TypeRow ca = caRows[i - 1];
                if (ca == null) {
                    continue;
                }
                Row row = ROWS.get(i - 1);
                boolean onlyValid = validActionsSelected == 1 && !badActionsSelected;
                if ("D".equals(selects[i - 1]) && onlyValid) {
                    if (deleted) {
                        selects[i - 1] = " ";
                    } else {
                        caDelete = CaFlag.REQUESTED;
                    }
                }
                out.set(row.type(), ca.code());
                if ("U".equals(selects[i - 1]) && onlyValid) {
                    if (updateCompleted) {
                        selects[i - 1] = " ";
                    } else {
                        caUpdate = CaFlag.REQUESTED;
                    }
                    if (changesHaveOccurred) {
                        move(out, row.desc(), descriptionEdit == DescriptionEdit.BLANK ? "*" : descIn[i - 1]);
                    } else {
                        out.set(row.desc(), ca.description());
                    }
                } else {
                    out.set(row.desc(), ca.description());
                }
                move(out, row.sel(), selects[i - 1]);
            }
        }

        /**
         * 2400-SETUP-SCREEN-ATTRS. On a first display the filter EVALUATE is skipped: its WHEN OTHER only moves
         * LOW-VALUES and DFHBMFSE, which the ERASE already gives.
         */
        private void setupScreenAttrs() {
            if (received != null) {
                if (actionsRequested > 0) {
                    move(out, TRTYPE, inTypeCd);
                    out.attribute(TRTYPE, FieldAttribute.BMASF).color(TRTYPE, Color.BLUE);
                } else if (typeFlag == FilterEdit.VALID || typeFlag == FilterEdit.NOT_OK) {
                    move(out, TRTYPE, inTypeCd);
                    out.attribute(TRTYPE, FieldAttribute.BMFSE);
                }
                if (actionsRequested > 0) {
                    move(out, TRDESC, inTypeDesc);
                    out.attribute(TRDESC, FieldAttribute.BMASF).color(TRDESC, Color.BLUE);
                } else if (descFlag == FilterEdit.VALID || descFlag == FilterEdit.NOT_OK) {
                    move(out, TRDESC, inTypeDesc);
                    out.attribute(TRDESC, FieldAttribute.BMFSE);
                }
            }
            if (typeFlag == FilterEdit.NOT_OK) {
                out.color(TRTYPE, Color.RED).cursor(TRTYPE);
            }
            if (descFlag == FilterEdit.NOT_OK) {
                out.color(TRDESC, Color.RED).cursor(TRDESC);
            }
            if (!inputError && (actionsRequested == 0 || aid == Key.PF7 || aid == Key.PF8)) {
                out.cursor(TRTYPE);
            }
        }

        /**
         * 2500-SETUP-MESSAGE. 2100 has just cleared WS-INFO-MSG, so its WHEN WS-NO-INFO-MESSAGE always matches and
         * the branches after it never run.
         */
        private void setupMessage() {
            boolean infoMessageOff = infoMsg.isEmpty();
            if (deleted) {
                infoMsg = INFORM_DELETE_SUCCESS;
            } else if (updateCompleted) {
                infoMsg = INFORM_UPDATE_SUCCESS;
            } else if (typeFlag == FilterEdit.NOT_OK || descFlag == FilterEdit.NOT_OK) {
                // no message
            } else if (aid == Key.ENTER && deletesRequested > 0 && actionsRequested == 1 && validActionsSelected == 1) {
                if (infoMessageOff && !typeFilterChanged && !descFilterChanged) {
                    infoMsg = INFORM_DELETE;
                }
            } else if (aid == Key.ENTER && updatesRequested > 0 && actionsRequested == 1 && validActionsSelected == 1) {
                if (infoMessageOff && !typeFilterChanged && !descFilterChanged) {
                    infoMsg = INFORM_UPDATE;
                }
            } else if (aid == Key.PF7 && caScreenNum == 1) {
                noPreviousPages();
            } else if (aid == Key.PF8 && caNextPage == NextPage.NOT_EXISTS && caLastPageShown) {
                returnMsg = "No more pages to display";
            } else if (aid == Key.PF8 && caNextPage == NextPage.NOT_EXISTS) {
                infoMsg = INFORM_REC_ACTIONS;
                caLastPageShown = true;
            } else {
                infoMsg = INFORM_REC_ACTIONS;
            }
            out.set(ERRMSG, returnMsg);
            if (!infoMsg.isEmpty() && !returnMsg.equals(MESG_NO_RECORDS_FOUND)) {
                out.set(INFOMSG, " ".repeat((INFO_MSG_LENGTH - infoMsg.length()) / 2) + infoMsg)
                        .color(INFOMSG, Color.NEUTR);
            }
        }

        @LegacyDefect(id = "COTRTLIC-page-up-to-first-page",
                goldens = {"tran-type-list-page-up"},
                behavior = "the message is chosen after the page is read, so F7 that pages back to page 1 reports "
                        + "'No previous pages to display' above the page it just showed",
                fix = "test for the first page before paging back")
        private void noPreviousPages() {
            returnMsg = "No previous pages to display";
        }

        /** 8000-READ-FORWARD. */
        private void readForward(String startKey) {
            Arrays.fill(caRows, null);
            Cursor<TypeRow> cursor = db2.declare("C-TR-TYPE-FORWARD", FORWARD, TranTypeListScreen::typeRow);
            Sqlca opened = cursor.open(withStartKey(startKey));
            if (opened.sqlcode() != 0) {
                db2Error = true;
                formatIfMessageOff("C-TR-TYPE-FORWARD Open", opened);
                return;
            }
            int rowNumber = 0;
            caNextPage = NextPage.EXISTS;
            while (true) {
                Fetched<TypeRow> fetched = cursor.fetch();
                int sqlcode = fetched.sqlca().sqlcode();
                if (sqlcode == 0) {
                    TypeRow row = fetched.row().orElseThrow();
                    rowNumber++;
                    caRows[rowNumber - 1] = row;
                    if (rowNumber == 1) {
                        caFirstTrCode = row.code();
                        if (caScreenNum == 0) {
                            caScreenNum++;
                        }
                    }
                    if (rowNumber == MAX_SCREEN_LINES) {
                        caLastTrCode = row.code();
                        Fetched<TypeRow> next = cursor.fetch();
                        int nextCode = next.sqlca().sqlcode();
                        if (nextCode == 0) {
                            caNextPage = NextPage.EXISTS;
                            caLastTrCode = next.row().orElseThrow().code();
                        } else if (nextCode == Sqlca.NOT_FOUND) {
                            caNextPage = NextPage.NOT_EXISTS;
                            if (returnMsg.isEmpty() && aid == Key.PF8) {
                                returnMsg = MESG_NO_MORE_RECORDS;
                            }
                        } else {
                            formatIfMessageOff("C-TR-TYPE-FORWARD fetch", next.sqlca());
                        }
                        break;
                    }
                } else if (sqlcode == Sqlca.NOT_FOUND) {
                    caNextPage = NextPage.NOT_EXISTS;
                    caLastTrCode = "";
                    if (returnMsg.isEmpty() && aid == Key.PF8) {
                        returnMsg = MESG_NO_MORE_RECORDS;
                    }
                    if (caScreenNum == 1 && rowNumber == 0) {
                        returnMsg = MESG_NO_RECORDS_FOUND;
                    }
                    break;
                } else {
                    db2Error = true;
                    formatIfMessageOff("C-TR-TYPE-FORWARD close", fetched.sqlca());
                    break;
                }
            }
            Sqlca closed = cursor.close();
            if (closed.sqlcode() != 0) {
                db2Error = true;
                formatIfMessageOff("C-TR-TYPE-FORWARD close", closed);
            }
        }

        @LegacyDefect(id = "COTRTLIC-page-up-short-page",
                behavior = "8100-READ-BACKWARDS treats +100 as an error, so F7 onto a page with fewer than seven "
                        + "earlier rows shows 'Error on fetch Cursor C-TR-TYPE-BACKWARD' over a partly filled page",
                fix = "treat +100 as the end of the page and read forward from the first row")
        private void readBackwards(String startKey) {
            Arrays.fill(caRows, null);
            caLastTrCode = caFirstTrCode;
            int rowNumber = MAX_SCREEN_LINES;
            caNextPage = NextPage.EXISTS;
            Cursor<TypeRow> cursor = db2.declare("C-TR-TYPE-BACKWARD", BACKWARD, TranTypeListScreen::typeRow);
            Sqlca opened = cursor.open(withStartKey(startKey));
            if (opened.sqlcode() != 0) {
                db2Error = true;
                formatIfMessageOff("C-TR-TYPE-BACKWARD Open", opened);
            }
            while (true) {
                Fetched<TypeRow> fetched = cursor.fetch();
                if (fetched.sqlca().sqlcode() == 0) {
                    TypeRow row = fetched.row().orElseThrow();
                    caRows[rowNumber - 1] = row;
                    rowNumber--;
                    if (rowNumber == 0) {
                        caFirstTrCode = row.code();
                        break;
                    }
                } else {
                    db2Error = true;
                    formatIfMessageOff("Error on fetch Cursor C-TR-TYPE-BACKWARD", fetched.sqlca());
                    break;
                }
            }
            Sqlca closed = cursor.close();
            if (closed.sqlcode() != 0) {
                db2Error = true;
                formatIfMessageOff("C-TR-TYPE-BACKWARD close", closed);
            }
        }

        /** 9100-CHECK-FILTERS. */
        private void checkFilters() {
            Fetched<Long> count = db2.selectInto(COUNT, (rs, n) -> rs.getLong(1), filterHostVariables());
            if (count.sqlca().sqlcode() == 0) {
                recordsCount = count.row().orElseThrow();
                return;
            }
            inputError = true;
            formatIfMessageOff("Error reading TRANSACTION_TYPE table ", count.sqlca());
        }

        @LegacyDefect(id = "COTRTLIC-description-padded",
                goldens = {"tran-type-list-update-save", "tran-type-list-update-case-only",
                        "tran-type-list-update-unchanged-save"},
                behavior = "the VARCHAR length is set to the length of the 50-byte field instead of the trimmed text, "
                        + "so every saved description is padded with blanks to 50",
                fix = "set DCL-TR-DESCRIPTION-LEN to the length of the trimmed description")
        private void updateRecord() {
            String typed = descIn[iSelected - 1];
            String description = typed == null ? "\0".repeat(DESCRIPTION_LENGTH)
                    : padded(trim(typed), DESCRIPTION_LENGTH);
            Sqlca sqlca = db2.execute("UPDATE CARDDEMO.TRANSACTION_TYPE SET TR_DESCRIPTION = ? WHERE TR_TYPE = ?",
                    description, codeIn[iSelected - 1]);
            int sqlcode = sqlca.sqlcode();
            if (sqlcode == 0) {
                db2.syncpoint();
                caUpdate = CaFlag.SUCCEEDED;
            } else if (sqlcode == Sqlca.NOT_FOUND) {
                caUpdate = CaFlag.REQUESTED;
                formatIfMessageOff("Record not found. Deleted by others ? ", sqlca);
            } else if (sqlcode == -911) {
                caUpdate = CaFlag.REQUESTED;
                inputError = true;
                formatIfMessageOff("Deadlock. Someone else updating ?", sqlca);
            } else if (sqlcode < 0) {
                caUpdate = CaFlag.REQUESTED;
                formatIfMessageOff("Update failed with", sqlca);
            }
        }

        /** 9300-DELETE-RECORD. */
        private void deleteRecord() {
            Sqlca sqlca = db2.execute("DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = ?", codeIn[iSelected - 1]);
            if (sqlca.sqlcode() == 0) {
                db2.syncpoint();
                caDelete = CaFlag.SUCCEEDED;
            } else if (sqlca.sqlcode() == -532) {
                caDelete = CaFlag.REQUESTED;
                formatIfMessageOff("Please delete associated child records first:", sqlca);
            } else {
                formatIfMessageOff("Delete failed with message:", sqlca);
            }
        }

        /** 9998-PRIMING-QUERY. */
        private void primingQuery() {
            Fetched<Integer> fetched = db2.selectInto("SELECT 1 FROM SYSIBM.SYSDUMMY1 FETCH FIRST 1 ROW ONLY",
                    (rs, n) -> rs.getInt(1));
            if (fetched.sqlca().sqlcode() != 0) {
                db2Error = true;
                formatDb2Message("Db2 access failure. ", fetched.sqlca());
            }
        }

        private void formatIfMessageOff(String action, Sqlca sqlca) {
            if (returnMsg.isEmpty()) {
                formatDb2Message(action, sqlca);
            }
        }

        private void formatDb2Message(String action, Sqlca sqlca) {
            String[] messages = db2Message(action, sqlca);
            longMsg = messages[0];
            returnMsg = messages[1];
        }
    }

    @LegacyDefect(id = "COTRTLIC-dsntiac-message-length",
            goldens = {"tran-type-list-delete-child-records"},
            behavior = "INITIALIZE WS-MISC-STORAGE zeroes WS-DSNTIAC-MESG-LEN before each call, so DSNTIAC always "
                    + "returns 12 and every Db2 error shows 'DSNTIAC CD12' instead of the Db2 message",
            fix = "set WS-DSNTIAC-MESG-LEN to 720 before the CALL")
    static String[] db2Message(String action, Sqlca sqlca) {
        Dsntiac.Formatted formatted = Dsntiac.format(sqlca, 0, DSNTIAC_LRECL, DSNTIAC_AREA);
        String text = formatted.returnCode() == 0 ? formatted.area()
                : "DSNTIAC CD" + "%02d".formatted(formatted.returnCode() % 100);
        String longMsg = action.strip() + " SQLCODE:" + sqlca.sqlcodeEdited() + " " + text;
        return new String[] {cut(longMsg, LONG_MSG_LENGTH), cut(longMsg, RETURN_MSG_LENGTH)};
    }

    /** ADD 1 TO or SUBTRACT 1 FROM WS-CA-SCREEN-NUM, a PIC 9(1) without ON SIZE ERROR. */
    @LegacyDefect(id = "COTRTLIC-screen-number-wraps",
            behavior = "the page number is a PIC 9(1): F8 past page 9 shows page 0, and F7 from page 0 shows page 1",
            fix = "widen WS-CA-SCREEN-NUM and PAGENO's source field")
    static int screenNumAfter(int screenNum, int delta) {
        return Math.abs(screenNum + delta) % 10;
    }

    private static TypeRow typeRow(ResultSet rs, int n) throws SQLException {
        return new TypeRow(rs.getString(1), rs.getString(2));
    }

    /** MOVE value TO xO, where a null value is LOW-VALUES. */
    private static void move(SymbolicMap<CTRTLIA> map, CTRTLIA field, String value) {
        if (value == null) {
            map.lowValue(field);
        } else {
            map.set(field, value);
        }
    }

    /** The received field, null for LOW-VALUES. */
    private static String value(SymbolicMap<CTRTLIA> map, CTRTLIA field) {
        return map.isLowValues(field) ? null : map.text(field);
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** FUNCTION TRIM: leading and trailing blanks only. */
    private static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    /** FUNCTION UPPER-CASE(FUNCTION TRIM(x)); LOW-VALUES trim to themselves and never equal text. */
    private static String upperTrim(String value) {
        return value == null ? "\0" : trim(value).toUpperCase(Locale.ROOT);
    }

    private static String padded(String value, int length) {
        return value.length() >= length ? value.substring(0, length) : value + " ".repeat(length - value.length());
    }

    private static String cut(String value, int length) {
        return value.length() > length ? value.substring(0, length) : value;
    }
}
