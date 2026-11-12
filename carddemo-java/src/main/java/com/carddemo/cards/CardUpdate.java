package com.carddemo.cards;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * COCRDUPC: fetch a card, edit its name, status and expiry, confirm, and save with F5. The phases are
 * {@link CardUpdatePhase}; F5 writes the values validated in CONFIRM, with the CVV and the expiry day of
 * the card as fetched.
 */
@Component
class CardUpdate implements ScreenProgram {

    private static final String SHOWN = "Details of selected card shown above";
    private static final String CHANGES = "Update card details presented above.";
    private static final String CONFIRM = "Changes validated.Press F5 to save";
    private static final String SAVED = "Changes committed to database";
    private static final String FAILED = "Changes unsuccessful. Please try again";

    private final CardRepository cards;
    private final TransactionTemplate tx;

    CardUpdate(CardRepository cards, TransactionTemplate tx) {
        this.cards = cards;
        this.tx = tx;
    }

    @Override
    public Program program() {
        return Program.COCRDUPC;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return protect(conversation, conversation.take(CardSelection.class)
                .map(selected -> fetch(conversation, selected.accountId(), selected.cardNumber()))
                .orElseGet(() -> blank(conversation)));
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        return protect(conversation, handle(conversation, aid, fields));
    }

    /**
     * 3300-SETUP-SCREEN-ATTRS: the keys before a card is fetched or after a failed save, the four edits while
     * editing, nothing in confirmation or after a save. The expiry day is never editable.
     */
    private static Outcome protect(Conversation conversation, Outcome outcome) {
        if (!(outcome instanceof Outcome.Show show)) {
            return outcome;
        }
        CardUpdatePhase phase = conversation.get(CardUpdateState.class).map(CardUpdateState::phase)
                .orElse(CardUpdatePhase.NOT_FETCHED);
        return show.protecting(switch (phase) {
            case NOT_FETCHED, LOCK_FAILED, SAVE_FAILED -> Set.of("CRDNAME", "CRDSTCD", "EXPMON", "EXPYEAR", "EXPDAY");
            case SHOWN, INVALID -> Set.of("ACCTSID", "CARDSID", "EXPDAY");
            case CONFIRM, SAVED -> Set.of("ACCTSID", "CARDSID", "CRDNAME", "CRDSTCD", "EXPMON", "EXPYEAR", "EXPDAY");
        });
    }

    /** F5 counts only in CONFIRM and F12 only once a card is fetched; any other key acts as ENTER. */
    private Outcome handle(Conversation conversation, Aid aid, Map<String, String> fields) {
        if (aid == Aid.PF3) {
            return exit(conversation);
        }
        CardUpdateState state = conversation.get(CardUpdateState.class).orElse(CardUpdateState.NOT_FETCHED);
        return switch (state.phase()) {
            case NOT_FETCHED -> switch (CardKeys.search(fields)) {
                case CardKeys.Search.Valid keys -> fetch(conversation, keys.accountId(), keys.cardNumber());
                case CardKeys.Search.Invalid invalid -> invalid.screen();
            };
            case SHOWN, INVALID -> aid == Aid.PF12 ? refetch(conversation, state.fetched())
                    : edit(conversation, state.fetched(), fields);
            case CONFIRM -> switch (aid) {
                case PF5 -> save(conversation, state);
                case PF12 -> refetch(conversation, state.fetched());
                default -> values(state.validated(), state.validated().embossedName(), CONFIRM, null, "ACCTSID");
            };
            case SAVED, LOCK_FAILED, SAVE_FAILED -> conversation.from().equals(Optional.of(Program.COCRDLIC))
                    ? exit(conversation) : blank(conversation);
        };
    }

    private Outcome exit(Conversation conversation) {
        conversation.take(CardUpdateState.class);
        return new Outcome.Transfer(conversation.from().orElse(Program.COMEN01C));
    }

    private Outcome blank(Conversation conversation) {
        conversation.put(CardUpdateState.NOT_FETCHED);
        return new Outcome.Show(Map.of("INFOMSG", CardKeys.SEARCH_PROMPT), null, "ACCTSID");
    }

    private Outcome refetch(Conversation conversation, Card fetched) {
        return fetch(conversation, fetched.accountId(), fetched.number());
    }

    private Outcome fetch(Conversation conversation, long accountId, String cardNumber) {
        Optional<Card> card;
        try {
            card = CardKeys.find(cards, accountId, cardNumber);
        } catch (DataAccessException e) {
            conversation.put(CardUpdateState.NOT_FETCHED);
            return CardKeys.notFound(accountId, cardNumber, CardKeys.fileError("READ", e));
        }
        if (card.isEmpty()) {
            conversation.put(CardUpdateState.NOT_FETCHED);
            return CardKeys.notFound(accountId, cardNumber, CardKeys.NOT_FOUND);
        }
        return shown(conversation, card.get(), null);
    }

    /** The card as fetched, name upper-cased, ready for edits. */
    private Outcome shown(Conversation conversation, Card fetched, Message message) {
        conversation.put(CardUpdateState.shown(fetched));
        return values(fetched, fetched.embossedName().toUpperCase(Locale.ROOT), SHOWN, message, "CRDNAME");
    }

    /**
     * The four edits in screen order on SHOWN or INVALID. The keys are protected, so only the fetched ones
     * count; the first failing field gets the message and the cursor, and a blank one is starred.
     */
    private Outcome edit(Conversation conversation, Card fetched, Map<String, String> fields) {
        String name = typed(fields, "CRDNAME");
        String status = typed(fields, "CRDSTCD");
        String month = typed(fields, "EXPMON");
        String year = typed(fields, "EXPYEAR");
        if (name.equalsIgnoreCase(fetched.embossedName()) && status.equalsIgnoreCase(fetched.activeStatus())
                && month.equals(CardKeys.month(fetched.expirationDate()))
                && year.equals(CardKeys.year(fetched.expirationDate()))) {
            return shown(conversation, fetched, Message.info("No change detected with respect to values fetched."));
        }
        Map<String, String> screen = new HashMap<>(Map.of("ACCTSID", CardKeys.account(fetched.accountId()),
                "CARDSID", fetched.number(), "CRDNAME", name, "CRDSTCD", status, "EXPMON", month, "EXPYEAR", year,
                "EXPDAY", CardKeys.day(fetched.expirationDate()), "INFOMSG", CHANGES));
        Edits edits = new Edits(screen);
        edits.check("CRDNAME", "Card name not provided", "Card name can only contain alphabets and spaces",
                name.matches("[A-Za-z ]+"));
        edits.check("CRDSTCD", "Card Active Status must be Y or N", status.equals("Y") || status.equals("N"));
        edits.check("EXPMON", "Card expiry month must be between 1 and 12", inRange(month, 2, 1, 12));
        edits.check("EXPYEAR", "Invalid card expiry year", inRange(year, 4, 1950, 2099));
        if (edits.message != null) {
            conversation.put(new CardUpdateState(CardUpdatePhase.INVALID, fetched, null));
            return new Outcome.Show(screen, Message.error(edits.message), edits.cursor);
        }
        Card validated = new Card(fetched.number(), fetched.accountId(), fetched.cvv(), name,
                expiry(Integer.parseInt(year), Integer.parseInt(month), fetched.expirationDate()), status);
        conversation.put(new CardUpdateState(CardUpdatePhase.CONFIRM, fetched, validated));
        return values(validated, name, CONFIRM, null, "ACCTSID");
    }

    /** Collects the field edits: the first failure sets the message and cursor. */
    private static final class Edits {

        private final Map<String, String> screen;
        private String message;
        private String cursor;

        Edits(Map<String, String> screen) {
            this.screen = screen;
        }

        void check(String field, String message, boolean valid) {
            check(field, message, message, valid);
        }

        void check(String field, String blankMessage, String invalidMessage, boolean valid) {
            boolean blank = CardKeys.blank(screen.get(field));
            if (blank) {
                screen.put(field, "*");
            }
            if ((blank || !valid) && message == null) {
                message = blank ? blankMessage : invalidMessage;
                cursor = field;
            }
        }
    }

    /**
     * READ UPDATE, compare with the card as fetched, REWRITE, in one transaction. A changed card is shown
     * as it now is, and becomes the snapshot the next F5 compares against.
     */
    private Outcome save(Conversation conversation, CardUpdateState state) {
        Card fetched = state.fetched();
        Card validated = state.validated();
        Attempt attempt = tx.execute(status -> {
            try {
                Optional<Card> locked = cards.lock(fetched.number());
                if (locked.isEmpty()) {
                    return new Attempt(CardUpdatePhase.LOCK_FAILED, null);
                }
                if (!locked.get().equals(fetched)) {
                    return new Attempt(CardUpdatePhase.SHOWN, locked.get());
                }
            } catch (DataAccessException e) {
                status.setRollbackOnly();
                return new Attempt(CardUpdatePhase.LOCK_FAILED, null);
            }
            try {
                cards.rewrite(validated);
                return new Attempt(CardUpdatePhase.SAVED, validated);
            } catch (DataAccessException e) {
                status.setRollbackOnly();
                return new Attempt(CardUpdatePhase.SAVE_FAILED, null);
            }
        });
        return switch (attempt.phase()) {
            case SHOWN -> shown(conversation, attempt.current(),
                    Message.error("Record changed by some one else. Please review"));
            case SAVED -> {
                conversation.put(new CardUpdateState(CardUpdatePhase.SAVED, validated, validated));
                yield values(validated, validated.embossedName(), SAVED, null, "ACCTSID");
            }
            case LOCK_FAILED, SAVE_FAILED -> {
                conversation.put(new CardUpdateState(attempt.phase(), fetched, validated));
                yield values(validated, validated.embossedName(), FAILED, Message.error(
                        attempt.phase() == CardUpdatePhase.LOCK_FAILED ? "Could not lock record for update"
                                : "Update of record failed"), "ACCTSID");
            }
            default -> throw new IllegalStateException(attempt.toString());
        };
    }

    private record Attempt(CardUpdatePhase phase, Card current) {
    }

    private static Outcome.Show values(Card card, String name, String info, Message message, String cursor) {
        LocalDate expiry = card.expirationDate();
        return new Outcome.Show(Map.of("ACCTSID", CardKeys.account(card.accountId()), "CARDSID", card.number(),
                "CRDNAME", name, "CRDSTCD", card.activeStatus(), "EXPMON", CardKeys.month(expiry),
                "EXPYEAR", CardKeys.year(expiry), "EXPDAY", CardKeys.day(expiry), "INFOMSG", info), message, cursor);
    }

    private static String typed(Map<String, String> fields, String name) {
        String value = fields.getOrDefault(name, "");
        return value.equals("*") ? "" : value;
    }

    private static boolean inRange(String digits, int maxLength, int low, int high) {
        if (!digits.matches("\\d{1," + maxLength + "}")) {
            return false;
        }
        int value = Integer.parseInt(digits);
        return value >= low && value <= high;
    }

    /** The day cannot be edited; a day the new month lacks becomes its last day, since a date column holds no 06-31. */
    private static LocalDate expiry(int year, int month, LocalDate fetched) {
        YearMonth yearMonth = YearMonth.of(year, month);
        int day = fetched == null ? yearMonth.lengthOfMonth() : Math.min(fetched.getDayOfMonth(), yearMonth.lengthOfMonth());
        return yearMonth.atDay(day);
    }
}
