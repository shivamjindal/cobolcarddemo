package com.carddemo.card;

import static com.carddemo.online.maps.CCRDSLA.ACCTSID;
import static com.carddemo.online.maps.CCRDSLA.CARDSID;
import static com.carddemo.online.maps.CCRDSLA.CRDNAME;
import static com.carddemo.online.maps.CCRDSLA.CRDSTCD;
import static com.carddemo.online.maps.CCRDSLA.ERRMSG;
import static com.carddemo.online.maps.CCRDSLA.EXPMON;
import static com.carddemo.online.maps.CCRDSLA.EXPYEAR;
import static com.carddemo.online.maps.CCRDSLA.INFOMSG;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.accounts.CardRecord;
import com.carddemo.accounts.CardStore;
import com.carddemo.accounts.Read;
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
import com.carddemo.online.maps.CCRDSLA;

/** COCRDSLC. Shows one card. The account number is echoed and never compared with the card. */
@Component
public final class CardDetailScreen extends Screen<CCRDSLA, CardDetailScreen.Action, CardDetailScreen.State, CardSelected> {

    public static final ScreenRef<CardSelected> REF = ScreenRef.of("COCRDSLC", "CCDL", "card-detail", CardSelected.class);

    private static final String PROMPT = "Please enter Account and Card Number";
    private static final String FOUND = "   Displaying requested details";
    private static final String ACCT_MISSING = "Account number not provided";
    private static final String CARD_MISSING = "Card number not provided";
    private static final String NO_INPUT = "No input received";
    private static final String ACCT_NUMERIC = "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";
    private static final String CARD_NUMERIC = "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";
    private static final String NOT_FOUND = "Did not find cards for this search condition";

    enum Action implements ScreenAction {
        SEARCH(Key.ENTER), BACK(Key.PF3);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    /** @param returnTo CDEMO-FROM-PROGRAM, kept because PF3 goes back there rather than always to the menu */
    record State(ProgramName returnTo, boolean fromList) implements ScreenState {}

    private final CardStore cards;

    CardDetailScreen(CardStore cards) {
        super(REF, CCRDSLA.MAP, Action.class, State.class, Access.SIGNED_ON);
        this.cards = cards;
    }

    @Override
    protected Outcome enter(Arrival<CardSelected> arrival) {
        ProgramName from = arrival.from().orElse(MainMenuScreen.REF.program());
        State state = new State(from, from.equals(CardListScreen.REF.program()));
        if (state.fromList() && arrival.handoff().isPresent()) {
            CardSelected selected = arrival.handoff().get();
            Read<CardRecord> read = readByCardNumber(selected.cardNum());
            CardRecord found = read instanceof Read.Found<CardRecord> hit ? hit.record() : null;
            String message = found == null ? NOT_FOUND : "";
            return present(state, FixedDigits.Value.ok("%011d".formatted(selected.acctId())),
                    FixedDigits.Value.ok(selected.cardNum()), found, message, false);
        }
        return present(state, FixedDigits.Value.blank(), FixedDigits.Value.blank(), null, "", false);
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<CCRDSLA> map, State state) {
        return switch (action) {
            case BACK -> transfer(state.returnTo());
            case SEARCH -> search(state, map);
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<CCRDSLA> map, State state) {
        return invalidKeyActsAsEnter(state, map);
    }

    @LegacyDefect(id = "COCRDSLC-invalid-key-acts-as-enter",
            goldens = {"card-detail-invalid-key-acts-as-enter"},
            behavior = "a key other than ENTER or PF3 is forced to ENTER and the search runs",
            fix = "reject the key and leave the screen as it is")
    private Outcome invalidKeyActsAsEnter(State state, SymbolicMap<CCRDSLA> map) {
        return search(state, map);
    }

    @LegacyDefect(id = "COCRDSLC-both-blank-hides-account-message",
            goldens = {"card-detail-both-blank", "card-detail-both-invalid"},
            behavior = "the account edit's message is replaced when both fields are blank, and a bad card does not "
                    + "replace a message the account edit already set",
            fix = "keep the first failing field's message only when it is the one that should win")
    private Outcome search(State state, SymbolicMap<CCRDSLA> map) {
        FixedDigits.Value account = FixedDigits.read(blankStar(map.text(ACCTSID)), 11);
        FixedDigits.Value card = FixedDigits.read(blankStar(map.text(CARDSID)), 16);
        String message = "";
        if (account.kind() == FixedDigits.Kind.BLANK) {
            message = ACCT_MISSING;
        } else if (account.kind() == FixedDigits.Kind.INVALID) {
            message = ACCT_NUMERIC;
        }
        if (card.kind() == FixedDigits.Kind.BLANK && message.isEmpty()) {
            message = CARD_MISSING;
        } else if (card.kind() == FixedDigits.Kind.INVALID && message.isEmpty()) {
            message = CARD_NUMERIC;
        }
        if (account.kind() == FixedDigits.Kind.BLANK && card.kind() == FixedDigits.Kind.BLANK) {
            message = NO_INPUT;
        }
        Read<CardRecord> read = message.isEmpty() ? readByCardNumber(card.text()) : null;
        if (read instanceof Read.Missing) {
            message = NOT_FOUND;
        } else if (read instanceof Read.Failed) {
            message = "Error reading Card Data File";
        }
        CardRecord found = read instanceof Read.Found<CardRecord> hit ? hit.record() : null;
        return present(state, account, card, found, message, true);
    }

    @LegacyDefect(id = "COCRDSLC-account-not-checked",
            goldens = {"card-detail-account-not-checked"},
            behavior = "the card is read by card number only, so a mismatched account still shows that card",
            fix = "read the card and reject it when CARD-ACCT-ID is not the account that was asked for")
    private Read<CardRecord> readByCardNumber(String cardNum) {
        return cards.find(cardNum);
    }

    private Outcome present(State state, FixedDigits.Value account, FixedDigits.Value card, CardRecord found,
            String message, boolean reenter) {
        SymbolicMap<CCRDSLA> map = SymbolicMap.lowValues(CCRDSLA.MAP);
        boolean notFound = NOT_FOUND.equals(message);
        paintKey(map, ACCTSID, account, reenter, notFound);
        paintKey(map, CARDSID, card, reenter, notFound);
        if (found != null) {
            LocalDate expiry = found.expirationDate();
            map.set(CRDNAME, found.embossedName())
                    .set(CRDSTCD, found.activeStatus())
                    .set(EXPMON, "%02d".formatted(expiry.getMonthValue()))
                    .set(EXPYEAR, "%04d".formatted(expiry.getYear()));
        }
        map.set(INFOMSG, found == null ? PROMPT : FOUND);
        if (!message.isEmpty()) {
            map.set(ERRMSG, message);
        }
        if (state.fromList()) {
            map.attribute(ACCTSID, FieldAttribute.BMPRF).attribute(CARDSID, FieldAttribute.BMPRF);
        }
        if (account.kind() != FixedDigits.Kind.OK) {
            map.cursor(ACCTSID);
        } else if (card.kind() != FixedDigits.Kind.OK) {
            map.cursor(CARDSID);
        } else {
            map.cursor(ACCTSID);
        }
        return show(map, state);
    }

    @LegacyDefect(id = "COCRDSLC-invalid-key-erased",
            goldens = {"card-detail-account-not-numeric", "card-detail-account-short", "card-detail-card-not-numeric",
                    "card-detail-both-invalid"},
            behavior = "a field that fails IS NUMERIC is stored as zero and 1200-SETUP-SCREEN-VARS sends LOW-VALUES, "
                    + "so the typed value disappears",
            fix = "echo the typed value on the field that failed")
    private static void paintKey(SymbolicMap<CCRDSLA> map, CCRDSLA field, FixedDigits.Value value, boolean reenter,
            boolean notFound) {
        if (value.ok()) {
            map.set(field, value.text());
        } else if (value.kind() == FixedDigits.Kind.BLANK && reenter) {
            map.set(field, "*");
        }
        if (notFound || value.kind() == FixedDigits.Kind.INVALID || (value.kind() == FixedDigits.Kind.BLANK && reenter)) {
            map.color(field, Color.RED);
        }
    }

    private static String blankStar(String raw) {
        return "*".equals(raw) ? "" : raw;
    }
}
