package com.carddemo.transaction;

import static com.carddemo.online.maps.COTRN0A.ERRMSG;
import static com.carddemo.online.maps.COTRN0A.PAGENUM;
import static com.carddemo.online.maps.COTRN0A.TRNIDIN;

import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.ksds.KeyedBrowse;
import com.carddemo.ksds.KeyedBrowse.StartKey;
import com.carddemo.menu.MainMenuScreen;
import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.CommonMessages;
import com.carddemo.online.Key;
import com.carddemo.online.NoHandoff;
import com.carddemo.online.Outcome;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.ScreenState;
import com.carddemo.online.SendMode;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.COTRN0A;
import com.carddemo.transactions.TransactionRecord;
import com.carddemo.transactions.TransactionStore;

/**
 * COTRN00C, ten transactions a page. The rows are FSET, so every RECEIVE brings back the page on
 * screen, and a selection reads its tran id from the row the terminal sent.
 */
@Component
public final class TransactionListScreen
        extends Screen<COTRN0A, TransactionListScreen.Action, TransactionListScreen.State, NoHandoff> {

    public static final ScreenRef<NoHandoff> REF =
            ScreenRef.of("COTRN00C", "CT00", "transaction-list", NoHandoff.class);

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
     * CDEMO-CT00-INFO.
     *
     * @param first    CDEMO-CT00-TRNID-FIRST, the id in row 1; "" for SPACES
     * @param last     CDEMO-CT00-TRNID-LAST, the id in row 10; "" for SPACES
     * @param page     CDEMO-CT00-PAGE-NUM
     * @param nextPage CDEMO-CT00-NEXT-PAGE-FLG = 'Y'
     */
    record State(String first, String last, long page, boolean nextPage) implements ScreenState {
        /** The main menu's COMMAREA is shorter, so the extension arrives as spaces. */
        static final State ARRIVED = new State("", "", 0, false);
    }

    private record Row(COTRN0A sel, COTRN0A id, COTRN0A date, COTRN0A desc, COTRN0A amount) {}

    private static final List<Row> ROWS = IntStream.rangeClosed(1, 10)
            .mapToObj(n -> new Row(COTRN0A.valueOf("SEL%04d".formatted(n)), COTRN0A.valueOf("TRNID%02d".formatted(n)),
                    COTRN0A.valueOf("TDATE%02d".formatted(n)), COTRN0A.valueOf("TDESC%02d".formatted(n)),
                    COTRN0A.valueOf("TAMT%03d".formatted(n))))
            .toList();

    private final TransactionStore transactions;

    TransactionListScreen(TransactionStore transactions) {
        super(REF, COTRN0A.MAP, Action.class, State.class, Access.SIGNED_ON);
        this.transactions = transactions;
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return new Task(SymbolicMap.lowValues(COTRN0A.MAP), State.ARRIVED).processEnterKey(arrival.aid());
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COTRN0A> map, State state) {
        Task task = new Task(map, state);
        return switch (action) {
            case CONTINUE -> task.processEnterKey(Key.ENTER);
            case BACK -> transfer(MainMenuScreen.REF.program());
            case BACKWARD -> task.processPf7Key();
            case FORWARD -> task.processPf8Key();
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<COTRN0A> map, State state) {
        Task task = new Task(map, state);
        task.message = CommonMessages.INVALID_KEY;
        return task.send();
    }

    /** {@code TRNIDINI IS NUMERIC} on the space-padded 16-character field. */
    static boolean numericTranId(String raw) {
        if (raw.length() != TRNIDIN.length()) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** One run of the program: its working storage and the COMMAREA extension. */
    private final class Task {
        private final SymbolicMap<COTRN0A> map;
        private String first;
        private String last;
        private long page;
        private boolean nextPage;
        private String message = "";
        private boolean error;
        private boolean endOfFile;
        private SendMode mode = SendMode.ERASE_CURSOR;

        Task(SymbolicMap<COTRN0A> map, State state) {
            this.map = map.cursor(TRNIDIN);
            this.first = state.first();
            this.last = state.last();
            this.page = state.page();
            this.nextPage = state.nextPage();
        }

        Outcome processEnterKey(Key aid) {
            for (Row row : ROWS) {
                if (map.isBlank(row.sel())) {
                    continue;
                }
                if (!map.isBlank(row.id())) {
                    switch (map.text(row.sel())) {
                        case "S", "s" -> {
                            return transfer(TransactionViewScreen.REF, new TransactionSelected(map.text(row.id())));
                        }
                        default -> invalidSelection();
                    }
                }
                break;
            }
            if (!map.isBlank(TRNIDIN) && !numericTranId(map.text(TRNIDIN))) {
                rejectNonNumeric();
                return send();
            }
            StartKey start = map.isBlank(TRNIDIN) ? StartKey.LOW_VALUES : StartKey.at(map.text(TRNIDIN));
            page = 0;
            return processPageForward(start, aid);
        }

        Outcome processPf7Key() {
            StartKey start = first.isEmpty() ? StartKey.LOW_VALUES : StartKey.at(first);
            nextPage = true;
            if (page > 1) {
                return processPageBackward(start, Key.PF7);
            }
            message = "You are already at the top of the page...";
            mode = SendMode.CURSOR;
            return send();
        }

        Outcome processPf8Key() {
            StartKey start = last.isEmpty() ? StartKey.HIGH_VALUES : StartKey.at(last);
            if (nextPage) {
                return processPageForward(start, Key.PF8);
            }
            message = "You are already at the bottom of the page...";
            mode = SendMode.CURSOR;
            return send();
        }

        Outcome processPageForward(StartKey start, Key aid) {
            KeyedBrowse<TransactionRecord> browse = startbrTranFile(start);
            if (error) {
                return send();
            }
            if (skipsFirstRecord(aid)) {
                readnextTranFile(browse);
            }
            if (!endOfFile && !error) {
                ROWS.forEach(this::initializeTranData);
            }
            int idx = 1;
            while (idx < 11 && !endOfFile && !error) {
                TransactionRecord tran = readnextTranFile(browse);
                if (!endOfFile && !error) {
                    populateTranData(idx, tran);
                    idx++;
                }
            }
            if (!endOfFile && !error) {
                page++;
                readnextTranFile(browse);
                nextPage = !endOfFile && !error;
            } else {
                nextPage = false;
                if (idx > 1) {
                    page++;
                }
            }
            if (!browse.end()) {
                return endbrWithoutBrowse();
            }
            map.set(PAGENUM, "%08d".formatted(page));
            map.blank(TRNIDIN);
            return send();
        }

        Outcome processPageBackward(StartKey start, Key aid) {
            KeyedBrowse<TransactionRecord> browse = startbrTranFile(start);
            if (error) {
                return send();
            }
            if (aid != Key.ENTER && aid != Key.PF8) {
                readprevTranFile(browse);
            }
            if (!endOfFile && !error) {
                ROWS.forEach(this::initializeTranData);
            }
            int idx = 10;
            while (idx > 0 && !endOfFile && !error) {
                TransactionRecord tran = readprevTranFile(browse);
                if (!endOfFile && !error) {
                    populateTranData(idx, tran);
                    idx--;
                }
            }
            if (!endOfFile && !error) {
                readprevTranFile(browse);
                if (nextPage) {
                    page = !endOfFile && !error && page > 1 ? page - 1 : 1;
                }
            }
            if (!browse.end()) {
                return endbrWithoutBrowse();
            }
            map.set(PAGENUM, "%08d".formatted(page));
            return send();
        }

        private void populateTranData(int idx, TransactionRecord tran) {
            Row row = ROWS.get(idx - 1);
            map.set(row.id(), tran.id())
                    .set(row.date(), TransactionPicture.listDate(tran.origTs()))
                    .set(row.desc(), tran.desc())
                    .set(row.amount(), TransactionPicture.amount(tran.amount()));
            if (idx == 1) {
                first = tran.id();
            }
            if (idx == 10) {
                last = tran.id();
            }
        }

        private void initializeTranData(Row row) {
            map.blank(row.id(), row.date(), row.desc(), row.amount());
        }

        private KeyedBrowse<TransactionRecord> startbrTranFile(StartKey start) {
            KeyedBrowse<TransactionRecord> browse = transactions.browse();
            switch (browse.start(start)) {
                case NORMAL -> {
                }
                case NOTFND -> {
                    endOfFile = true;
                    message = "You are at the top of the page...";
                }
                case FAILED -> unableToLookup();
            }
            return browse;
        }

        private TransactionRecord readnextTranFile(KeyedBrowse<TransactionRecord> browse) {
            return switch (browse.next()) {
                case KeyedBrowse.Read.Normal<TransactionRecord> normal -> normal.record();
                case KeyedBrowse.Read.EndFile<TransactionRecord> endFile -> {
                    reachedBottom();
                    yield null;
                }
                case KeyedBrowse.Read.Failed<TransactionRecord> failed -> {
                    unableToLookup();
                    yield null;
                }
            };
        }

        private TransactionRecord readprevTranFile(KeyedBrowse<TransactionRecord> browse) {
            return switch (browse.previous()) {
                case KeyedBrowse.Read.Normal<TransactionRecord> normal -> normal.record();
                case KeyedBrowse.Read.EndFile<TransactionRecord> endFile -> {
                    reachedTop();
                    yield null;
                }
                case KeyedBrowse.Read.Failed<TransactionRecord> failed -> {
                    unableToLookup();
                    yield null;
                }
            };
        }

        private void reachedBottom() {
            endOfFile = true;
            message = "You have reached the bottom of the page...";
        }

        @LegacyDefect(id = "COTRN00C-pf8-then-pf7-top-message",
                goldens = {"transaction-list-pf8-then-pf7"},
                behavior = "a backward page that includes the first record still runs the look-ahead READPREV, "
                        + "and that ENDFILE leaves 'You have reached the top of the page...' on page 1",
                fix = "set the top message only when the page did not fill")
        private void reachedTop() {
            endOfFile = true;
            message = "You have reached the top of the page...";
        }

        private void unableToLookup() {
            error = true;
            message = "Unable to lookup transaction...";
        }

        @LegacyDefect(id = "COTRN00C-invalid-selection-resets-page",
                goldens = {"transaction-list-select-invalid"},
                behavior = "a selection other than S sets the message and then ENTER still re-browses from the "
                        + "start, so the page returns to 1 and the typed code stays in the row",
                fix = "redisplay the page that was on screen and clear the bad code")
        private void invalidSelection() {
            message = "Invalid selection. Valid value is S";
        }

        /** ERR-FLG skips the browse, so the received page stays and the typed id is not cleared. */
        private void rejectNonNumeric() {
            error = true;
            message = "Tran ID must be Numeric ...";
            page = 0;
        }

        @LegacyDefect(id = "COTRN00C-pf5-skips-first",
                goldens = {"transaction-view-pf5-list"},
                behavior = "the forward browse skips one record unless EIBAID is ENTER, PF7, or PF3. PF5 from "
                        + "the view arrives with that aid, so page 1 starts at the second transaction",
                fix = "skip a record only for PF8")
        private boolean skipsFirstRecord(Key aid) {
            return aid != Key.ENTER && aid != Key.PF7 && aid != Key.PF3;
        }

        @LegacyDefect(id = "COTRN00C-filter-past-end-abends",
                goldens = {"transaction-list-filter-past-end"},
                behavior = "a Tran ID above every key makes STARTBR NOTFND; ENDBR then runs with no RESP on the "
                        + "browse that never started and the task abends AEIP",
                fix = "run ENDBR only after a successful STARTBR and show 'You are at the top of the page...'")
        private Outcome endbrWithoutBrowse() {
            return abend("AEIP");
        }

        /** SEND-TRNLST-SCREEN. Earlier SENDs in the same task only show what this one overwrites. */
        Outcome send() {
            map.set(ERRMSG, message);
            return show(map, mode, new State(first, last, page, nextPage));
        }
    }
}
