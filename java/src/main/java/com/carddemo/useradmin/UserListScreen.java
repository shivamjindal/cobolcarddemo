package com.carddemo.useradmin;

import static com.carddemo.online.maps.COUSR0A.ERRMSG;
import static com.carddemo.online.maps.COUSR0A.PAGENUM;
import static com.carddemo.online.maps.COUSR0A.USRIDIN;

import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.ksds.KeyedBrowse;
import com.carddemo.ksds.KeyedBrowse.StartKey;
import com.carddemo.menu.AdminMenuScreen;
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
import com.carddemo.online.maps.COUSR0A;
import com.carddemo.users.UserId;
import com.carddemo.users.UserRecord;
import com.carddemo.users.UserStore;

/**
 * COUSR00C, ten users a page. The rows are FSET, so every RECEIVE brings back the page on screen,
 * and a selection reads its user id from the row the terminal sent.
 */
@Component
public final class UserListScreen extends Screen<COUSR0A, UserListScreen.Action, UserListScreen.State, NoHandoff> {

    public static final ScreenRef<NoHandoff> REF = ScreenRef.of("COUSR00C", "CU00", "user-list", NoHandoff.class);

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
     * CDEMO-CU00-INFO.
     *
     * @param first    CDEMO-CU00-USRID-FIRST, the id in row 1; "" for SPACES
     * @param last     CDEMO-CU00-USRID-LAST, the id in row 10; "" for SPACES
     * @param page     CDEMO-CU00-PAGE-NUM
     * @param nextPage CDEMO-CU00-NEXT-PAGE-FLG = 'Y'
     */
    record State(String first, String last, long page, boolean nextPage) implements ScreenState {
        /** The admin menu's COMMAREA is shorter, so the extension arrives as spaces. */
        static final State ARRIVED = new State("", "", 0, false);
    }

    private record Row(COUSR0A sel, COUSR0A id, COUSR0A firstName, COUSR0A lastName, COUSR0A type) {}

    private static final List<Row> ROWS = IntStream.rangeClosed(1, 10)
            .mapToObj(n -> new Row(COUSR0A.valueOf("SEL%04d".formatted(n)), COUSR0A.valueOf("USRID%02d".formatted(n)),
                    COUSR0A.valueOf("FNAME%02d".formatted(n)), COUSR0A.valueOf("LNAME%02d".formatted(n)),
                    COUSR0A.valueOf("UTYPE%02d".formatted(n))))
            .toList();

    private final UserStore users;

    UserListScreen(UserStore users) {
        super(REF, COUSR0A.MAP, Action.class, State.class, Access.ADMIN);
        this.users = users;
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return new Task(SymbolicMap.lowValues(COUSR0A.MAP), State.ARRIVED).processEnterKey(arrival.aid());
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COUSR0A> map, State state) {
        Task task = new Task(map, state);
        return switch (action) {
            case CONTINUE -> task.processEnterKey(Key.ENTER);
            case BACK -> transfer(AdminMenuScreen.REF.program());
            case BACKWARD -> task.processPf7Key();
            case FORWARD -> task.processPf8Key();
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<COUSR0A> map, State state) {
        Task task = new Task(map, state);
        task.message = CommonMessages.INVALID_KEY;
        return task.send();
    }

    /** One run of the program: its working storage and the COMMAREA extension. */
    private final class Task {
        private final SymbolicMap<COUSR0A> map;
        private String first;
        private String last;
        private long page;
        private boolean nextPage;
        private String message = "";
        private boolean error;
        private boolean endOfFile;
        private SendMode mode = SendMode.ERASE_CURSOR;

        Task(SymbolicMap<COUSR0A> map, State state) {
            this.map = map.cursor(USRIDIN);
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
                    UserSelected selected = new UserSelected(UserId.of(map.text(row.id())));
                    switch (map.text(row.sel())) {
                        case "U", "u" -> {
                            return transfer(UserUpdateScreen.REF, selected);
                        }
                        case "D", "d" -> {
                            return transfer(UserDeleteScreen.REF, selected);
                        }
                        default -> {
                            message = "Invalid selection. Valid values are U and D";
                        }
                    }
                }
                break;
            }
            StartKey start = map.isBlank(USRIDIN) ? StartKey.LOW_VALUES : StartKey.at(map.text(USRIDIN));
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
            KeyedBrowse<UserRecord> browse = startbrUserSecFile(start);
            if (error) {
                return send();
            }
            if (aid != Key.ENTER && aid != Key.PF7 && aid != Key.PF3) {
                readnextUserSecFile(browse);
            }
            if (!endOfFile && !error) {
                ROWS.forEach(this::initializeUserData);
            }
            int idx = 1;
            while (idx < 11 && !endOfFile && !error) {
                UserRecord user = readnextUserSecFile(browse);
                if (!endOfFile && !error) {
                    populateUserData(idx, user);
                    idx++;
                }
            }
            if (!endOfFile && !error) {
                page++;
                readnextUserSecFile(browse);
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
            map.blank(USRIDIN);
            return send();
        }

        Outcome processPageBackward(StartKey start, Key aid) {
            KeyedBrowse<UserRecord> browse = startbrUserSecFile(start);
            if (error) {
                return send();
            }
            if (aid != Key.ENTER && aid != Key.PF8) {
                readprevUserSecFile(browse);
            }
            if (!endOfFile && !error) {
                ROWS.forEach(this::initializeUserData);
            }
            int idx = 10;
            while (idx > 0 && !endOfFile && !error) {
                UserRecord user = readprevUserSecFile(browse);
                if (!endOfFile && !error) {
                    populateUserData(idx, user);
                    idx--;
                }
            }
            if (!endOfFile && !error) {
                readprevUserSecFile(browse);
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

        private void populateUserData(int idx, UserRecord user) {
            Row row = ROWS.get(idx - 1);
            map.set(row.id(), user.id().value())
                    .set(row.firstName(), user.firstName())
                    .set(row.lastName(), user.lastName())
                    .set(row.type(), user.type().toString());
            if (idx == 1) {
                first = user.id().value();
            }
            if (idx == 10) {
                last = user.id().value();
            }
        }

        private void initializeUserData(Row row) {
            map.blank(row.id(), row.firstName(), row.lastName(), row.type());
        }

        private KeyedBrowse<UserRecord> startbrUserSecFile(StartKey start) {
            KeyedBrowse<UserRecord> browse = users.browse();
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

        private UserRecord readnextUserSecFile(KeyedBrowse<UserRecord> browse) {
            return switch (browse.next()) {
                case KeyedBrowse.Read.Normal<UserRecord> normal -> normal.record();
                case KeyedBrowse.Read.EndFile<UserRecord> endFile -> {
                    reachedBottom();
                    yield null;
                }
                case KeyedBrowse.Read.Failed<UserRecord> failed -> {
                    unableToLookup();
                    yield null;
                }
            };
        }

        private UserRecord readprevUserSecFile(KeyedBrowse<UserRecord> browse) {
            return switch (browse.previous()) {
                case KeyedBrowse.Read.Normal<UserRecord> normal -> normal.record();
                case KeyedBrowse.Read.EndFile<UserRecord> endFile -> {
                    endOfFile = true;
                    message = "You have reached the top of the page...";
                    yield null;
                }
                case KeyedBrowse.Read.Failed<UserRecord> failed -> {
                    unableToLookup();
                    yield null;
                }
            };
        }

        @LegacyDefect(id = "COUSR00C-selection-message-lost",
                goldens = {"user-list-select-invalid", "user-list-select-invalid-mid-list"},
                behavior = "READNEXT's ENDFILE branch overwrites WS-MESSAGE, so 'Invalid selection' is lost "
                        + "whenever the page reaches the last user; the mid-list golden keeps it",
                fix = "set the bottom-of-page message only when WS-MESSAGE is blank")
        private void reachedBottom() {
            endOfFile = true;
            message = "You have reached the bottom of the page...";
        }

        private void unableToLookup() {
            error = true;
            message = "Unable to lookup User...";
        }

        @LegacyDefect(id = "COUSR00C-endbr-after-notfnd",
                behavior = "a search id after the last user, which includes every lowercase id, makes STARTBR "
                        + "NOTFND; the program still runs ENDBR without RESP and the task abends AEIP",
                fix = "run ENDBR only after a successful STARTBR and show 'You are at the top of the page...'")
        private Outcome endbrWithoutBrowse() {
            return abend("AEIP");
        }

        /** SEND-USRLST-SCREEN. Earlier SENDs in the same task only show what this one overwrites. */
        Outcome send() {
            map.set(ERRMSG, message);
            return show(map, mode, new State(first, last, page, nextPage));
        }
    }
}
