package com.carddemo.card;

import static com.carddemo.online.maps.CCRDUPA.ACCTSID;
import static com.carddemo.online.maps.CCRDUPA.CARDSID;
import static com.carddemo.online.maps.CCRDUPA.CRDNAME;
import static com.carddemo.online.maps.CCRDUPA.CRDSTCD;
import static com.carddemo.online.maps.CCRDUPA.ERRMSG;
import static com.carddemo.online.maps.CCRDUPA.EXPDAY;
import static com.carddemo.online.maps.CCRDUPA.EXPMON;
import static com.carddemo.online.maps.CCRDUPA.EXPYEAR;
import static com.carddemo.online.maps.CCRDUPA.FKEYSC;
import static com.carddemo.online.maps.CCRDUPA.INFOMSG;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.accounts.CardRecord;
import com.carddemo.accounts.CardStore;
import com.carddemo.accounts.Held;
import com.carddemo.accounts.Read;
import com.carddemo.accounts.Write;
import com.carddemo.menu.MainMenuScreen;
import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.Color;
import com.carddemo.online.FieldAttribute;
import com.carddemo.online.Key;
import com.carddemo.online.Outcome;
import com.carddemo.online.ProgramName;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.ScreenState;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.CCRDUPA;

/**
 * COCRDUPC. Fetch a card, edit the name, status and expiry, then confirm with PF5. {@link Phase} is
 * CCUP-CHANGE-ACTION.
 */
@Component
public final class CardUpdateScreen
        extends Screen<CCRDUPA, CardUpdateScreen.Action, CardUpdateScreen.State, CardSelected> {

    public static final ScreenRef<CardSelected> REF =
            ScreenRef.of("COCRDUPC", "CCUP", "card-update", CardSelected.class);

    private static final String PROMPT = "Please enter Account and Card Number";
    private static final String DETAILS = "Details of selected card shown above";
    private static final String CHANGES = "Update card details presented above.";
    private static final String CONFIRM = "Changes validated.Press F5 to save";
    private static final String COMMITTED = "Changes committed to database";
    private static final String FAILURE = "Changes unsuccessful. Please try again";
    private static final String ACCT_MISSING = "Account number not provided";
    private static final String CARD_MISSING = "Card number not provided";
    private static final String NO_INPUT = "No input received";
    private static final String ACCT_NUMERIC = "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";
    private static final String CARD_NUMERIC = "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";
    private static final String NOT_FOUND = "Did not find cards for this search condition";
    private static final String NAME_MISSING = "Card name not provided";
    private static final String NAME_ALPHA = "Card name can only contain alphabets and spaces";
    private static final String STATUS = "Card Active Status must be Y or N";
    private static final String MONTH = "Card expiry month must be between 1 and 12";
    private static final String YEAR = "Invalid card expiry year";
    private static final String NO_CHANGE = "No change detected with respect to values fetched.";

    enum Action implements ScreenAction {
        FETCH(Key.ENTER), BACK(Key.PF3), SAVE(Key.PF5), CANCEL(Key.PF12);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    /** CCUP-CHANGE-ACTION. */
    enum Phase {
        NOT_FETCHED, SHOWING, REJECTED, CONFIRMING, SAVED, LOCK_FAILED, SAVE_FAILED;

        boolean finished() {
            return this == SAVED || this == LOCK_FAILED || this == SAVE_FAILED;
        }
    }

    /** The old or new card image held in the commarea: name, status and the expiry parts. */
    record CardImage(String name, String status, String year, String month, String day) implements Serializable {

        static final CardImage EMPTY = new CardImage("", "", "", "", "");
    }

    record State(ProgramName returnTo, boolean fromList, Phase phase, String account, String card, CardImage oldImage,
            CardImage edited, Integer oldCvv) implements ScreenState {}

    private final CardStore cards;

    CardUpdateScreen(CardStore cards) {
        super(REF, CCRDUPA.MAP, Action.class, State.class, Access.SIGNED_ON);
        this.cards = cards;
    }

    @Override
    protected Outcome enter(Arrival<CardSelected> arrival) {
        ProgramName from = arrival.from().orElse(MainMenuScreen.REF.program());
        boolean fromList = from.equals(CardListScreen.REF.program());
        if (fromList && arrival.handoff().isPresent()) {
            CardSelected selected = arrival.handoff().get();
            Task task = new Task(null, new State(from, true, Phase.NOT_FETCHED,
                    "%011d".formatted(selected.acctId()), selected.cardNum(), CardImage.EMPTY, CardImage.EMPTY, null),
                    false);
            task.fetch();
            return task.render();
        }
        return blank(from);
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<CCRDUPA> map, State state) {
        if (action == Action.BACK) {
            return transfer(state.returnTo());
        }
        if (state.phase().finished()) {
            return leave(state);
        }
        Task task = new Task(map, state, false);
        return switch (action) {
            case BACK -> transfer(state.returnTo());
            case FETCH -> task.turn(Key.ENTER);
            case SAVE -> task.turn(task.saveKey());
            case CANCEL -> task.cancel(state);
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<CCRDUPA> map, State state) {
        return invalidKeyActsAsEnter(map, state);
    }

    @LegacyDefect(id = "COCRDUPC-invalid-key-acts-as-enter",
            behavior = "a key other than PF3, or PF5 and PF12 once they apply, is forced to ENTER",
            fix = "reject the key and leave the screen as it is")
    private Outcome invalidKeyActsAsEnter(SymbolicMap<CCRDUPA> map, State state) {
        if (state.phase().finished()) {
            return leave(state);
        }
        return new Task(map, state, false).turn(Key.ENTER);
    }

    private Outcome leave(State state) {
        if (state.fromList()) {
            return transfer(CardListScreen.REF.program());
        }
        return blank(state.returnTo());
    }

    private Outcome blank(ProgramName returnTo) {
        return new Task(null, new State(returnTo, false, Phase.NOT_FETCHED, "", "", CardImage.EMPTY, CardImage.EMPTY,
                null), true).render();
    }

    private final class Task {
        private final SymbolicMap<CCRDUPA> map;
        private final ProgramName returnTo;
        private final boolean fromList;
        private final boolean entering;
        private Phase phase;
        private String account;
        private String card;
        private CardImage oldImage;
        private CardImage edited;
        private Integer oldCvv;
        private String message = "";
        private FixedDigits.Kind accountKind = FixedDigits.Kind.BLANK;
        private FixedDigits.Kind cardKind = FixedDigits.Kind.BLANK;
        private FixedDigits.Kind nameKind = FixedDigits.Kind.OK;
        private FixedDigits.Kind statusKind = FixedDigits.Kind.OK;
        private FixedDigits.Kind monthKind = FixedDigits.Kind.OK;
        private FixedDigits.Kind yearKind = FixedDigits.Kind.OK;

        Task(SymbolicMap<CCRDUPA> map, State state, boolean entering) {
            this.map = map;
            this.returnTo = state.returnTo();
            this.fromList = state.fromList();
            this.entering = entering;
            this.phase = state.phase();
            this.account = state.account();
            this.card = state.card();
            this.oldImage = state.oldImage();
            this.edited = state.edited();
            this.oldCvv = state.oldCvv();
        }

        @LegacyDefect(id = "COCRDUPC-pf5-before-confirm-acts-as-enter",
                goldens = {"card-update-pf5-before-confirm-acts-as-enter"},
                behavior = "PF5 is a real save only while changes are waiting for confirmation; before that it is ENTER",
                fix = "ignore PF5 until the confirm screen is up")
        private Key saveKey() {
            return phase == Phase.CONFIRMING ? Key.PF5 : Key.ENTER;
        }

        @LegacyDefect(id = "COCRDUPC-pf12-before-fetch-acts-as-enter",
                goldens = {"card-update-pf12-before-fetch-acts-as-enter"},
                behavior = "PF12 is a cancel only after a card has been fetched; before that it is ENTER and fetches",
                fix = "ignore PF12 until a card is on screen")
        private Outcome cancel(State state) {
            if (phase == Phase.NOT_FETCHED) {
                return turn(Key.ENTER);
            }
            if (state.fromList()) {
                fetch();
                return render();
            }
            return turn(Key.PF12);
        }

        private Outcome turn(Key aid) {
            if (phase == Phase.NOT_FETCHED) {
                editKeys();
                if (accountKind == FixedDigits.Kind.OK && cardKind == FixedDigits.Kind.OK) {
                    fetch();
                }
            } else {
                editChanges();
                if (aid == Key.PF12) {
                    fetch();
                } else if (phase == Phase.CONFIRMING && aid == Key.PF5) {
                    save();
                }
            }
            return render();
        }

        @LegacyDefect(id = "COCRDUPC-both-blank-hides-account-message",
                goldens = {"card-update-search-both-blank"},
                behavior = "'No input received' replaces 'Account number not provided' when both search fields are blank",
                fix = "keep the account message when both fields are blank")
        private void editKeys() {
            FixedDigits.Value acct = FixedDigits.read(blankStar(map.text(ACCTSID)), 11);
            FixedDigits.Value cardNo = FixedDigits.read(blankStar(map.text(CARDSID)), 16);
            accountKind = acct.kind();
            cardKind = cardNo.kind();
            account = acct.kind() == FixedDigits.Kind.BLANK ? "" : acct.text();
            card = cardNo.kind() == FixedDigits.Kind.BLANK ? "" : cardNo.text();
            if (acct.kind() == FixedDigits.Kind.BLANK) {
                message = ACCT_MISSING;
            } else if (acct.kind() == FixedDigits.Kind.INVALID) {
                message = ACCT_NUMERIC;
            }
            if (cardNo.kind() == FixedDigits.Kind.BLANK && message.isEmpty()) {
                message = CARD_MISSING;
            } else if (cardNo.kind() == FixedDigits.Kind.INVALID && message.isEmpty()) {
                message = CARD_NUMERIC;
            }
            if (acct.kind() == FixedDigits.Kind.BLANK && cardNo.kind() == FixedDigits.Kind.BLANK) {
                message = NO_INPUT;
            }
        }

        private void editChanges() {
            accountKind = FixedDigits.Kind.OK;
            cardKind = FixedDigits.Kind.OK;
            String typedAccount = blankStar(map.text(ACCTSID));
            String typedCard = blankStar(map.text(CARDSID));
            if (!typedAccount.isEmpty()) {
                account = typedAccount;
            }
            if (!typedCard.isEmpty()) {
                card = typedCard;
            }
            String day = map.text(EXPDAY);
            edited = new CardImage(blankStar(map.text(CRDNAME)), blankStar(map.text(CRDSTCD)),
                    blankStar(map.text(EXPYEAR)), blankStar(map.text(EXPMON)), day.isEmpty() ? oldImage.day() : day);
            if (sameIgnoringCase(edited, oldImage) || phase == Phase.CONFIRMING) {
                nameKind = FixedDigits.Kind.OK;
                statusKind = FixedDigits.Kind.OK;
                monthKind = FixedDigits.Kind.OK;
                yearKind = FixedDigits.Kind.OK;
                return;
            }
            phase = Phase.REJECTED;
            nameKind = nameKind(edited.name());
            statusKind = statusKind(edited.status());
            monthKind = monthKind(edited.month());
            yearKind = yearKind(edited.year());
            if (nameKind == FixedDigits.Kind.BLANK) {
                message = NAME_MISSING;
            } else if (nameKind == FixedDigits.Kind.INVALID) {
                message = NAME_ALPHA;
            } else if (statusKind != FixedDigits.Kind.OK) {
                message = STATUS;
            } else if (monthKind != FixedDigits.Kind.OK) {
                message = MONTH;
            } else if (yearKind != FixedDigits.Kind.OK) {
                message = YEAR;
            } else {
                phase = Phase.CONFIRMING;
            }
        }

        @LegacyDefect(id = "COCRDUPC-compare-uppercased",
                goldens = {"card-update-lowercase-name-is-no-change", "card-update-lowercase-status-same-is-no-change"},
                behavior = "old and new details are compared after UPPER-CASE, so retyping the name or status in "
                        + "lower case is not a change and is never edited",
                fix = "compare the name and status in the case the user typed")
        private boolean sameIgnoringCase(CardImage edited, CardImage old) {
            boolean same = edited.name().equalsIgnoreCase(old.name()) && edited.status().equalsIgnoreCase(old.status())
                    && edited.year().equals(old.year()) && edited.month().equals(old.month())
                    && edited.day().equals(old.day());
            if (same && phase == Phase.SHOWING) {
                message = NO_CHANGE;
            }
            return same;
        }

        @LegacyDefect(id = "COCRDUPC-pf12-keeps-edit-error",
                goldens = {"card-update-pf12-with-bad-edits"},
                behavior = "PF12 re-reads the card after the edit has stored its message, and the re-read leaves that message up",
                fix = "clear the edit message when the original card is read back")
        private void fetch() {
            String prior = message;
            switch (readByCardNumber(card)) {
                case Read.Found<CardRecord> found -> {
                    CardRecord record = found.record();
                    LocalDate expiry = record.expirationDate();
                    oldCvv = record.cvv();
                    oldImage = new CardImage(fetchedName(record.embossedName()), record.activeStatus(),
                            "%04d".formatted(expiry.getYear()), "%02d".formatted(expiry.getMonthValue()),
                            "%02d".formatted(expiry.getDayOfMonth()));
                    edited = oldImage;
                    phase = Phase.SHOWING;
                    accountKind = FixedDigits.Kind.OK;
                    cardKind = FixedDigits.Kind.OK;
                    message = prior;
                }
                case Read.Missing<CardRecord> missing -> {
                    if (prior.isEmpty()) {
                        message = NOT_FOUND;
                    }
                    accountKind = FixedDigits.Kind.INVALID;
                    cardKind = FixedDigits.Kind.INVALID;
                }
                case Read.Failed<CardRecord> failed -> {
                    if (prior.isEmpty()) {
                        message = "Error reading Card Data File";
                    }
                }
            }
        }

        @LegacyDefect(id = "COCRDUPC-fetch-account-not-checked",
                goldens = {"card-update-fetch-account-not-checked"},
                behavior = "the card is read by card number only, so a mismatched account still fetches that card",
                fix = "reject the card when CARD-ACCT-ID is not the account that was asked for")
        private Read<CardRecord> readByCardNumber(String cardNum) {
            return cards.find(cardNum);
        }

        private void save() {
            switch (cards.findForUpdate(card)) {
                case Held<CardRecord> held -> {
                    if (changedSinceFetch(held.record())) {
                        message = "Record changed by some one else. Please review";
                        phase = Phase.SHOWING;
                        return;
                    }
                    LocalDate expiry = LocalDate.of(Integer.parseInt(edited.year()), Integer.parseInt(edited.month()),
                            Integer.parseInt(edited.day()));
                    CardRecord rewritten = new CardRecord(card, Long.parseLong(account), blankedCvv(), edited.name(),
                            expiry, edited.status(), uninitializedFiller());
                    if (cards.rewrite(held, rewritten) == Write.DONE) {
                        phase = Phase.SAVED;
                    } else {
                        message = "Update of record failed";
                        phase = Phase.SAVE_FAILED;
                    }
                }
                default -> {
                    message = "Could not lock record for update";
                    phase = Phase.LOCK_FAILED;
                }
            }
        }

        private boolean changedSinceFetch(CardRecord record) {
            LocalDate expiry = record.expirationDate();
            String name = fetchedName(record.embossedName());
            String year = "%04d".formatted(expiry.getYear());
            String month = "%02d".formatted(expiry.getMonthValue());
            String day = "%02d".formatted(expiry.getDayOfMonth());
            boolean same = Objects.equals(record.cvv(), oldCvv) && name.equals(oldImage.name()) && year.equals(oldImage.year())
                    && month.equals(oldImage.month()) && day.equals(oldImage.day())
                    && record.activeStatus().equals(oldImage.status());
            if (!same) {
                oldCvv = record.cvv();
                oldImage = new CardImage(name, record.activeStatus(), year, month, day);
            }
            return !same;
        }

        private Outcome render() {
            SymbolicMap<CCRDUPA> out = SymbolicMap.lowValues(CCRDUPA.MAP);
            if (!entering) {
                paintKey(out, ACCTSID, account, accountKind);
                paintKey(out, CARDSID, card, cardKind);
                paintDetails(out);
            }
            bright(out, INFOMSG);
            out.set(INFOMSG, info());
            if (phase == Phase.CONFIRMING) {
                bright(out, FKEYSC);
            }
            if (!message.isEmpty()) {
                out.set(ERRMSG, message);
            }
            protect(out);
            out.cursor(cursor());
            return show(out, new State(returnTo, fromList, phase, account, card, oldImage, edited, oldCvv));
        }

        private void paintKey(SymbolicMap<CCRDUPA> out, CCRDUPA field, String text, FixedDigits.Kind kind) {
            if (kind == FixedDigits.Kind.BLANK) {
                out.set(field, "*").color(field, Color.RED);
            } else {
                if (!text.isEmpty()) {
                    out.set(field, text);
                }
                if (kind == FixedDigits.Kind.INVALID) {
                    out.color(field, Color.RED);
                }
            }
        }

        private void paintDetails(SymbolicMap<CCRDUPA> out) {
            if (phase == Phase.NOT_FETCHED) {
                return;
            }
            CardImage shown = phase == Phase.SHOWING ? oldImage : edited;
            paintDetail(out, CRDNAME, shown.name(), nameKind);
            paintDetail(out, CRDSTCD, shown.status(), statusKind);
            paintDetail(out, EXPMON, shown.month(), monthKind);
            paintDetail(out, EXPYEAR, shown.year(), yearKind);
            out.set(EXPDAY, shown.day().isEmpty() ? oldImage.day() : shown.day());
        }

        private void paintDetail(SymbolicMap<CCRDUPA> out, CCRDUPA field, String text, FixedDigits.Kind kind) {
            if (phase == Phase.REJECTED && kind == FixedDigits.Kind.BLANK) {
                out.set(field, "*").color(field, Color.RED);
            } else {
                out.set(field, text);
                if (phase == Phase.REJECTED && kind == FixedDigits.Kind.INVALID) {
                    out.color(field, Color.RED);
                }
            }
        }

        private void protect(SymbolicMap<CCRDUPA> out) {
            switch (phase) {
                case SHOWING, REJECTED -> {
                    out.attribute(ACCTSID, FieldAttribute.BMPRF).attribute(CARDSID, FieldAttribute.BMPRF);
                    out.attribute(CRDNAME, FieldAttribute.BMFSE).attribute(CRDSTCD, FieldAttribute.BMFSE)
                            .attribute(EXPMON, FieldAttribute.BMFSE).attribute(EXPYEAR, FieldAttribute.BMFSE);
                }
                case CONFIRMING, SAVED -> {
                    out.attribute(ACCTSID, FieldAttribute.BMPRF).attribute(CARDSID, FieldAttribute.BMPRF);
                    out.attribute(CRDNAME, FieldAttribute.BMPRF).attribute(CRDSTCD, FieldAttribute.BMPRF)
                            .attribute(EXPMON, FieldAttribute.BMPRF).attribute(EXPYEAR, FieldAttribute.BMPRF);
                }
                default -> {
                    out.attribute(ACCTSID, FieldAttribute.BMFSE).attribute(CARDSID, FieldAttribute.BMFSE);
                    out.attribute(CRDNAME, FieldAttribute.BMPRF).attribute(CRDSTCD, FieldAttribute.BMPRF)
                            .attribute(EXPMON, FieldAttribute.BMPRF).attribute(EXPYEAR, FieldAttribute.BMPRF);
                }
            }
        }

        @LegacyDefect(id = "COCRDUPC-confirm-cursor-on-protected-account",
                goldens = {"card-update-valid-change-asks-confirm", "card-update-save"},
                behavior = "once the edits pass, the cursor falls through to ACCTSID, which confirmation has just protected",
                fix = "leave the cursor on the confirm prompt")
        private CCRDUPA cursor() {
            if (phase == Phase.SHOWING || NO_CHANGE.equals(message)) {
                return CRDNAME;
            }
            if (accountKind != FixedDigits.Kind.OK) {
                return ACCTSID;
            }
            if (cardKind != FixedDigits.Kind.OK) {
                return CARDSID;
            }
            if (nameKind != FixedDigits.Kind.OK) {
                return CRDNAME;
            }
            if (statusKind != FixedDigits.Kind.OK) {
                return CRDSTCD;
            }
            if (monthKind != FixedDigits.Kind.OK) {
                return EXPMON;
            }
            if (yearKind != FixedDigits.Kind.OK) {
                return EXPYEAR;
            }
            return ACCTSID;
        }

        private String info() {
            return switch (phase) {
                case SHOWING -> DETAILS;
                case REJECTED -> CHANGES;
                case CONFIRMING -> CONFIRM;
                case SAVED -> COMMITTED;
                case LOCK_FAILED, SAVE_FAILED -> FAILURE;
                case NOT_FETCHED -> PROMPT;
            };
        }

        @LegacyDefect(id = "COCRDUPC-bright-unprotects",
                goldens = {"card-update-initial-display", "card-update-fetch", "card-update-valid-change-asks-confirm"},
                behavior = "DFHBMBRY is moved into INFOMSGA, and into the F5 line on confirm, which clears the protected bit",
                fix = "set the intensity and leave the field protected")
        private void bright(SymbolicMap<CCRDUPA> out, CCRDUPA field) {
            out.attribute(field, FieldAttribute.BMBRY);
        }
    }

    @LegacyDefect(id = "COCRDUPC-name-uppercased-on-fetch",
            goldens = {"card-update-fetch", "card-update-from-list-save-returns-to-list"},
            behavior = "9000-READ-DATA upper-cases the embossed name, and a save writes that name back when it was not retyped",
            fix = "keep the stored name's case on fetch")
    private static String fetchedName(String name) {
        return name.toUpperCase(Locale.ROOT);
    }

    @LegacyDefect(id = "COCRDUPC-cvv-blanked-on-save",
            goldens = {"card-update-save", "card-update-from-list-save-returns-to-list"},
            behavior = "CCUP-NEW-CVV-CD is never filled, so the REWRITE stores spaces in CARD-CVV-CD",
            fix = "copy the fetched CVV into the record that is rewritten")
    private static Integer blankedCvv() {
        return null;
    }

    @LegacyDefect(id = "COCRDUPC-filler-low-values",
            behavior = "CARD-UPDATE-RECORD's FILLER is never initialized, and INITIALIZE does not touch FILLER, so the "
                    + "REWRITE stores LOW-VALUES",
            fix = "rewrite the filler the READ UPDATE returned")
    private static String uninitializedFiller() {
        return null;
    }

    private static FixedDigits.Kind nameKind(String name) {
        if (name.isEmpty()) {
            return FixedDigits.Kind.BLANK;
        }
        return name.chars().allMatch(c -> c == ' ' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))
                ? FixedDigits.Kind.OK
                : FixedDigits.Kind.INVALID;
    }

    private static FixedDigits.Kind statusKind(String status) {
        if (status.isEmpty()) {
            return FixedDigits.Kind.BLANK;
        }
        return "Y".equals(status) || "N".equals(status) ? FixedDigits.Kind.OK : FixedDigits.Kind.INVALID;
    }

    private static FixedDigits.Kind monthKind(String month) {
        FixedDigits.Value value = FixedDigits.read(month, 2);
        if (!value.ok()) {
            return value.kind();
        }
        int number = Integer.parseInt(value.text());
        return number >= 1 && number <= 12 ? FixedDigits.Kind.OK : FixedDigits.Kind.INVALID;
    }

    private static FixedDigits.Kind yearKind(String year) {
        FixedDigits.Value value = FixedDigits.read(year, 4);
        if (!value.ok()) {
            return value.kind();
        }
        int number = Integer.parseInt(value.text());
        return number >= 1950 && number <= 2099 ? FixedDigits.Kind.OK : FixedDigits.Kind.INVALID;
    }

    private static String blankStar(String raw) {
        return "*".equals(raw) ? "" : raw;
    }
}
