package com.carddemo.cards;

import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import org.springframework.dao.DataAccessException;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** What the card screens share: the 11/16-digit key rules, the detail search edits and the lookup. */
final class CardKeys {

    static final String SEARCH_PROMPT = "Please enter Account and Card Number";
    static final Message NOT_FOUND = Message.error("Did not find cards for this search condition");
    static final String ACCOUNT_RULE = "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";
    static final String CARD_RULE = "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    private static final Pattern ACCOUNT = Pattern.compile("\\d{11}");
    private static final Pattern CARD = Pattern.compile("\\d{16}");

    private CardKeys() {
    }

    /** Blank, the '*' a blank key is echoed as, and all zeros all mean "not supplied". */
    static boolean blank(String key) {
        return key.isBlank() || key.equals("*") || key.matches("0+");
    }

    static boolean account(String key) {
        return ACCOUNT.matcher(key).matches();
    }

    static boolean card(String key) {
        return CARD.matcher(key).matches();
    }

    static String account(long id) {
        return "%011d".formatted(id);
    }

    /** The account and card search keys, valid, or the screen that reports what is wrong with them. */
    sealed interface Search {
        record Valid(long accountId, String cardNumber) implements Search {
        }

        record Invalid(Outcome.Show screen) implements Search {
        }
    }

    /**
     * The search edits in legacy order. A blank key is starred, a malformed one is cleared from the screen,
     * and the account message wins over the card message.
     */
    static Search search(Map<String, String> fields) {
        String account = fields.getOrDefault("ACCTSID", "");
        String card = fields.getOrDefault("CARDSID", "");
        boolean accountBlank = blank(account);
        boolean cardBlank = blank(card);
        boolean accountOk = !accountBlank && account(account);
        boolean cardOk = !cardBlank && card(card);
        if (accountOk && cardOk) {
            return new Search.Valid(Long.parseLong(account), card);
        }
        String message;
        if (accountBlank && cardBlank) {
            message = "No input received";
        } else if (accountBlank) {
            message = "Account number not provided";
        } else if (!accountOk) {
            message = ACCOUNT_RULE;
        } else if (cardBlank) {
            message = "Card number not provided";
        } else {
            message = CARD_RULE;
        }
        Map<String, String> screen = new HashMap<>();
        screen.put("ACCTSID", accountBlank ? "*" : accountOk ? account : "");
        screen.put("CARDSID", cardBlank ? "*" : cardOk ? card : "");
        screen.put("INFOMSG", SEARCH_PROMPT);
        return new Search.Invalid(new Outcome.Show(screen, Message.error(message), accountOk ? "CARDSID" : "ACCTSID"));
    }

    /** READ by card number; a card the account does not own is not found. */
    static Optional<Card> find(CardRepository cards, long accountId, String cardNumber) {
        return cards.find(cardNumber).filter(c -> c.accountId() == accountId);
    }

    /** The search screen for keys that found nothing, or a read that failed. */
    static Outcome.Show notFound(long accountId, String cardNumber, Message message) {
        return new Outcome.Show(Map.of("ACCTSID", account(accountId), "CARDSID", cardNumber, "INFOMSG", SEARCH_PROMPT),
                message, "ACCTSID");
    }

    /** WS-FILE-ERROR-MESSAGE. There is no CICS RESP, so it reports IOERR and the SQL state as RESP2. */
    static Message fileError(String operation, DataAccessException e) {
        String state = e.getMostSpecificCause() instanceof SQLException sql && sql.getSQLState() != null
                ? sql.getSQLState() : "0";
        return Message.error("File Error: %s on CARDDAT returned RESP IOERR,RESP2 %s".formatted(operation, state));
    }

    static String month(LocalDate date) {
        return date == null ? "" : "%02d".formatted(date.getMonthValue());
    }

    static String year(LocalDate date) {
        return date == null ? "" : "%04d".formatted(date.getYear());
    }

    static String day(LocalDate date) {
        return date == null ? "" : "%02d".formatted(date.getDayOfMonth());
    }
}
