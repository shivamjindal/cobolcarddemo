package com.carddemo.card;

import static com.carddemo.online.maps.CCRDLIA.ACCTSID;
import static com.carddemo.online.maps.CCRDLIA.CARDSID;
import static com.carddemo.online.maps.CCRDLIA.ERRMSG;
import static com.carddemo.online.maps.CCRDLIA.INFOMSG;
import static com.carddemo.online.maps.CCRDLIA.PAGENO;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.accounts.CardRecord;
import com.carddemo.accounts.CardStore;
import com.carddemo.ksds.KeyedBrowse;
import com.carddemo.ksds.KeyedBrowse.StartKey;
import com.carddemo.menu.MainMenuScreen;
import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.Color;
import com.carddemo.online.FieldAttribute;
import com.carddemo.online.Key;
import com.carddemo.online.NoHandoff;
import com.carddemo.online.Outcome;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.ScreenState;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.CCRDLIA;

/** COCRDLIC. Seven cards a page, optional account and card filters, S for detail and U for update. */
@Component
public final class CardListScreen extends Screen<CCRDLIA, CardListScreen.Action, CardListScreen.State, NoHandoff> {

    public static final ScreenRef<NoHandoff> REF = ScreenRef.of("COCRDLIC", "CCLI", "card-list", NoHandoff.class);

    static final int PAGE_SIZE = 7;

    private static final String INFO = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";
    private static final String NO_PREVIOUS = "NO PREVIOUS PAGES TO DISPLAY";
    private static final String NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";
    private static final String NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";
    private static final String NO_RECORDS = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";
    private static final String ACCT_FILTER = "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";
    private static final String CARD_FILTER = "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";
    private static final String ONE_ONLY = "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";
    private static final String BAD_ACTION = "INVALID ACTION CODE";

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
     * WS-THIS-PROGCOMMAREA plus the account and card filters the base commarea carries.
     *
     * @param firstCard WS-CA-FIRST-CARD-NUM, "" when the page has not started
     * @param lastCard  WS-CA-LAST-CARD-NUM, the card a forward page starts at
     * @param page      WS-CA-SCREEN-NUM
     * @param nextPage  WS-CA-NEXT-PAGE-IND = 'Y'
     */
    record State(String firstCard, String lastCard, int page, boolean nextPage, boolean lastPageShown,
            String accountFilter, String cardFilter, List<Listed> rows) implements ScreenState {

        static State fresh() {
            return new State("", "", 1, false, false, "", "", emptyRows());
        }

        private static List<Listed> emptyRows() {
            return new ArrayList<>(Collections.nCopies(PAGE_SIZE, Listed.NONE));
        }
    }

    /** One of the seven WS-SCREEN-ROWS. An empty card number is the row's LOW-VALUES. */
    record Listed(String account, String card, String status) implements Serializable {

        static final Listed NONE = new Listed("", "", "");

        boolean present() {
            return !card.isEmpty();
        }

        static Listed of(CardRecord record) {
            return new Listed("%011d".formatted(record.acctId()), record.num(), record.activeStatus());
        }
    }

    private record Line(CCRDLIA sel, CCRDLIA acct, CCRDLIA card, CCRDLIA status) {}

    private static final List<Line> LINES = IntStream.rangeClosed(1, PAGE_SIZE)
            .mapToObj(n -> new Line(CCRDLIA.valueOf("CRDSEL" + n), CCRDLIA.valueOf("ACCTNO" + n),
                    CCRDLIA.valueOf("CRDNUM" + n), CCRDLIA.valueOf("CRDSTS" + n)))
            .toList();

    private final CardStore cards;

    CardListScreen(CardStore cards) {
        super(REF, CCRDLIA.MAP, Action.class, State.class, Access.SIGNED_ON);
        this.cards = cards;
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        Task task = new Task(State.fresh());
        task.readForward("");
        return task.paint(Key.ENTER);
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<CCRDLIA> map, State state) {
        Task task = new Task(state);
        task.take(map);
        return switch (action) {
            case BACK -> transfer(MainMenuScreen.REF.program());
            case BACKWARD -> task.page(Key.PF7);
            case FORWARD -> task.page(Key.PF8);
            case CONTINUE -> task.page(Key.ENTER);
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<CCRDLIA> map, State state) {
        return invalidKeyActsAsEnter(map, state);
    }

    @LegacyDefect(id = "COCRDLIC-invalid-key-acts-as-enter",
            goldens = {"card-list-invalid-key-acts-as-enter"},
            behavior = "a key other than ENTER, PF3, PF7 or PF8 is forced to ENTER and the typed filter is applied",
            fix = "reject the key and leave the page as it is")
    private Outcome invalidKeyActsAsEnter(SymbolicMap<CCRDLIA> map, State state) {
        Task task = new Task(state);
        task.take(map);
        return task.page(Key.ENTER);
    }

    private final class Task {
        private String firstCard;
        private String lastCard;
        private int page;
        private boolean nextPage;
        private boolean lastPageShown;
        private final List<Listed> rows;
        private FixedDigits.Value account = FixedDigits.Value.blank();
        private FixedDigits.Value card = FixedDigits.Value.blank();
        private final String[] selections = new String[PAGE_SIZE];
        private final boolean[] rowError = new boolean[PAGE_SIZE];
        private int selected = -1;
        private boolean inputError;
        private boolean protectSelections;
        private String error = "";

        Task(State state) {
            firstCard = state.firstCard();
            lastCard = state.lastCard();
            page = state.page();
            nextPage = state.nextPage();
            lastPageShown = state.lastPageShown();
            rows = new ArrayList<>(state.rows());
            Arrays.fill(selections, "");
        }

        void take(SymbolicMap<CCRDLIA> map) {
            account = FixedDigits.read(map.text(ACCTSID), 11);
            card = FixedDigits.read(map.text(CARDSID), 16);
            for (int i = 0; i < PAGE_SIZE; i++) {
                selections[i] = map.text(LINES.get(i).sel());
            }
            edit();
        }

        Outcome page(Key aid) {
            if (aid != Key.PF8) {
                lastPageShown = false;
            }
            if (inputError) {
                if (filtersAcceptable()) {
                    readForward("");
                }
                return paint(aid);
            }
            if (aid == Key.PF8 && nextPage) {
                page++;
                readForward(lastCard);
                return paint(aid);
            }
            if (aid == Key.PF7 && page > 1) {
                page--;
                readBackward(firstCard);
                return paint(aid);
            }
            if (aid == Key.PF7) {
                readForward(firstCard);
                return paint(aid);
            }
            if (selected >= 0 && "S".equals(selections[selected])) {
                Listed row = rows.get(selected);
                return transfer(CardDetailScreen.REF, new CardSelected(Long.parseLong(row.account()), row.card()));
            }
            if (selected >= 0 && "U".equals(selections[selected])) {
                Listed row = rows.get(selected);
                return transfer(CardUpdateScreen.REF, new CardSelected(Long.parseLong(row.account()), row.card()));
            }
            readForward(firstCard);
            return paint(aid);
        }

        private void edit() {
            if (account.kind() == FixedDigits.Kind.INVALID) {
                inputError = true;
                protectSelections = true;
                error = ACCT_FILTER;
            }
            if (card.kind() == FixedDigits.Kind.INVALID) {
                inputError = true;
                protectSelections = true;
                if (error.isEmpty()) {
                    error = CARD_FILTER;
                }
            }
            if (inputError) {
                return;
            }
            int chosen = 0;
            for (String selection : selections) {
                if ("S".equals(selection) || "U".equals(selection)) {
                    chosen++;
                }
            }
            if (chosen > 1) {
                inputError = true;
                error = ONE_ONLY;
            }
            for (int i = 0; i < PAGE_SIZE; i++) {
                String selection = selections[i];
                if ("S".equals(selection) || "U".equals(selection)) {
                    selected = i;
                    if (chosen > 1) {
                        rowError[i] = true;
                    }
                } else if (!selection.isEmpty()) {
                    inputError = true;
                    rowError[i] = true;
                    if (error.isEmpty()) {
                        error = BAD_ACTION;
                    }
                }
            }
        }

        @LegacyDefect(id = "COCRDLIC-bad-filter-keeps-page",
                goldens = {"card-list-account-not-numeric", "card-list-card-not-numeric"},
                behavior = "a non-numeric filter skips the browse, so the previous page stays on screen and every "
                        + "selection field stays protected",
                fix = "drop the typed filter and leave the selection fields unprotected")
        private boolean filtersAcceptable() {
            return account.kind() != FixedDigits.Kind.INVALID && card.kind() != FixedDigits.Kind.INVALID;
        }

        private void readForward(String startCard) {
            rows.clear();
            rows.addAll(State.fresh().rows());
            KeyedBrowse<CardRecord> browse = cards.browse();
            String lastRead = "";
            int counter = 0;
            nextPage = true;
            if (browse.start(startOf(startCard)) == KeyedBrowse.Start.NORMAL) {
                boolean reading = true;
                while (reading) {
                    switch (browse.next()) {
                        case KeyedBrowse.Read.Normal<CardRecord> normal -> {
                            CardRecord record = normal.record();
                            lastRead = record.num();
                            if (included(record)) {
                                counter++;
                                rows.set(counter - 1, Listed.of(record));
                                if (counter == 1) {
                                    firstCard = record.num();
                                    if (page == 0) {
                                        page++;
                                    }
                                }
                            }
                            if (counter == PAGE_SIZE) {
                                reading = false;
                                lastCard = record.num();
                                switch (browse.next()) {
                                    case KeyedBrowse.Read.Normal<CardRecord> peek -> {
                                        nextPage = true;
                                        lastCard = peek.record().num();
                                    }
                                    case KeyedBrowse.Read.EndFile<CardRecord> end -> noteEnd(false);
                                    case KeyedBrowse.Read.Failed<CardRecord> failed -> fileError();
                                }
                            }
                        }
                        case KeyedBrowse.Read.EndFile<CardRecord> end -> {
                            reading = false;
                            lastCard = lastRead;
                            noteEnd(page == 1 && counter == 0);
                        }
                        case KeyedBrowse.Read.Failed<CardRecord> failed -> {
                            reading = false;
                            fileError();
                        }
                    }
                }
            } else {
                fileError();
            }
            browse.end();
        }

        private void readBackward(String startCard) {
            rows.clear();
            rows.addAll(State.fresh().rows());
            lastCard = firstCard;
            KeyedBrowse<CardRecord> browse = cards.browse();
            nextPage = true;
            int counter = PAGE_SIZE + 1;
            if (browse.start(startOf(startCard)) != KeyedBrowse.Start.NORMAL) {
                fileError();
                browse.end();
                return;
            }
            switch (browse.previous()) {
                case KeyedBrowse.Read.Normal<CardRecord> normal -> counter = PAGE_SIZE;
                default -> {
                    fileError();
                    browse.end();
                    return;
                }
            }
            boolean reading = true;
            while (reading) {
                switch (browse.previous()) {
                    case KeyedBrowse.Read.Normal<CardRecord> normal -> {
                        CardRecord record = normal.record();
                        if (included(record)) {
                            rows.set(counter - 1, Listed.of(record));
                            counter--;
                            if (counter == 0) {
                                reading = false;
                                firstCard = record.num();
                            }
                        }
                    }
                    default -> {
                        reading = false;
                        fileError();
                    }
                }
            }
            browse.end();
        }

        @LegacyDefect(id = "COCRDLIC-no-user-filter",
                goldens = {"card-list-first-page"},
                behavior = "every signed-on user sees every card; nothing limits the browse to an account on the commarea",
                fix = "browse CARDAIX for the signed-on user's account")
        private boolean included(CardRecord record) {
            if (account.ok() && record.acctId() != Long.parseLong(account.text())) {
                return false;
            }
            return !card.ok() || record.num().equals(card.text());
        }

        private void noteEnd(boolean nothingOnFirstPage) {
            nextPage = false;
            if (error.isEmpty()) {
                error = NO_MORE_RECORDS;
            }
            if (nothingOnFirstPage) {
                error = NO_RECORDS;
            }
        }

        private void fileError() {
            error = "Error reading Card Data File";
        }

        private static StartKey startOf(String cardNumber) {
            return cardNumber.isEmpty() ? StartKey.LOW_VALUES : StartKey.at(cardNumber);
        }

        @LegacyDefect(id = "COCRDLIC-page-up-from-page-1",
                goldens = {"card-list-page-up-from-page-2"},
                behavior = "PF7 subtracts from the page number before the message is chosen, so paging up onto page 1 "
                        + "also says NO PREVIOUS PAGES TO DISPLAY and drops the info line",
                fix = "choose the message from the page number before subtracting")
        private String infoLine(Key aid) {
            boolean show;
            if (!filtersAcceptable()) {
                show = false;
            } else if (aid == Key.PF7 && page == 1) {
                error = NO_PREVIOUS;
                show = false;
            } else if (aid == Key.PF8 && !nextPage && lastPageShown) {
                error = NO_MORE_PAGES;
                show = false;
            } else if (aid == Key.PF8 && !nextPage) {
                show = true;
                lastPageShown = true;
            } else {
                show = true;
            }
            return show && !NO_RECORDS.equals(error) ? INFO : null;
        }

        Outcome paint(Key aid) {
            SymbolicMap<CCRDLIA> map = SymbolicMap.lowValues(CCRDLIA.MAP);
            map.set(PAGENO, Integer.toString(page));
            for (int i = 0; i < PAGE_SIZE; i++) {
                paintRow(map, i);
            }
            if (account.kind() != FixedDigits.Kind.BLANK) {
                map.set(ACCTSID, account.text());
            }
            if (card.kind() != FixedDigits.Kind.BLANK) {
                map.set(CARDSID, card.text());
            }
            if (account.kind() == FixedDigits.Kind.INVALID) {
                map.color(ACCTSID, Color.RED).cursor(ACCTSID);
            }
            if (card.kind() == FixedDigits.Kind.INVALID) {
                map.color(CARDSID, Color.RED).cursor(CARDSID);
            }
            if (!inputError) {
                map.cursor(ACCTSID);
            }
            String info = infoLine(aid);
            if (!error.isEmpty()) {
                map.set(ERRMSG, error);
            }
            if (info != null) {
                map.set(INFOMSG, info);
            }
            String accountFilter = account.ok() ? account.text() : "";
            String cardFilter = card.ok() ? card.text() : "";
            return show(map, new State(firstCard, lastCard, page, nextPage, lastPageShown, accountFilter, cardFilter,
                    List.copyOf(rows)));
        }

        private void paintRow(SymbolicMap<CCRDLIA> map, int i) {
            Listed row = rows.get(i);
            if (!row.present()) {
                return;
            }
            Line line = LINES.get(i);
            map.set(line.acct(), row.account()).set(line.card(), row.card()).set(line.status(), row.status());
            if (rowError[i] && selections[i].isEmpty()) {
                map.set(line.sel(), "*");
            } else if (!selections[i].isEmpty()) {
                map.set(line.sel(), selections[i]);
            }
            if (!protectSelections) {
                map.attribute(line.sel(), FieldAttribute.BMFSE);
            }
            if (rowError[i]) {
                markSelection(map, line, i);
            }
        }

        @LegacyDefect(id = "COCRDLIC-row1-no-cursor",
                goldens = {"card-list-invalid-action-row1"},
                behavior = "an invalid action on row 1 colors the row but never sets its cursor length, unlike rows "
                        + "2-7, so the cursor stays on the map's IC field ACCTSID",
                fix = "set CRDSEL1L to -1 the same way as the other rows")
        private void markSelection(SymbolicMap<CCRDLIA> map, Line line, int i) {
            map.color(line.sel(), Color.RED);
            if (i > 0) {
                map.cursor(line.sel());
            }
        }
    }
}
