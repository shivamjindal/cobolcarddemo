package com.carddemo.online;

import com.carddemo.cards.Card;
import com.carddemo.cards.CardFilter;
import com.carddemo.cards.CardListPage;
import com.carddemo.cards.CardSelection;
import com.carddemo.cards.CardUpdatePhase;
import com.carddemo.cards.CardUpdateState;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.carddemo.online.SpecCase.text;

/**
 * COCRDLIC, COCRDSLC and COCRDUPC COMMAREA fields, as CardListPage, CardSelection and CardUpdateState.
 * CDEMO-ACCT-ID and CDEMO-CARD-NUM are the list's filter, the card handed to detail or update, and the
 * card update fetched.
 */
class CardCommarea implements CommareaMapping {

    @Override
    @SuppressWarnings("unchecked")
    public void load(String program, Map<String, Object> commarea, Conversation conversation) {
        String account = value(commarea, "CDEMO-ACCT-ID");
        String card = value(commarea, "CDEMO-CARD-NUM");
        if (program.equals("COCRDLIC")) {
            if (commarea.keySet().stream().anyMatch(k -> k.startsWith("WS-CA-") || k.equals("WS-SCREEN-ROWS"))) {
                List<CardListPage.Row> rows = ((List<Map<String, Object>>) commarea.getOrDefault("WS-SCREEN-ROWS", List.of()))
                        .stream().filter(r -> !value(r, "WS-ROW-CARD-NUM").isBlank())
                        .map(r -> new CardListPage.Row(Long.parseLong(value(r, "WS-ROW-ACCTNO")),
                                value(r, "WS-ROW-CARD-NUM"), value(r, "WS-ROW-CARD-STATUS")))
                        .toList();
                String screen = value(commarea, "WS-CA-SCREEN-NUM");
                conversation.put(new CardListPage(screen.isBlank() ? 1 : Integer.parseInt(screen),
                        value(commarea, "WS-CA-FIRST-CARD-NUM"), value(commarea, "WS-CA-LAST-CARD-NUM"),
                        value(commarea, "WS-CA-NEXT-PAGE-IND").equals("Y"),
                        value(commarea, "WS-CA-LAST-PAGE-DISPLAYED").equals("0"),
                        new CardFilter(zero(account) ? null : Long.valueOf(account), zero(card) ? null : card), rows));
            }
            return;
        }
        if (value(commarea, "CDEMO-FROM-PROGRAM").equals("COCRDLIC") && value(commarea, "CDEMO-PGM-CONTEXT").equals("0")
                && !zero(account) && !zero(card)) {
            conversation.put(new CardSelection(Long.parseLong(account), card));
        }
        if (program.equals("COCRDUPC") && commarea.containsKey("CCUP-CHANGE-ACTION")) {
            String code = value(commarea, "CCUP-CHANGE-ACTION");
            CardUpdatePhase phase = Arrays.stream(CardUpdatePhase.values()).filter(p -> p.code.equals(code))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("CCUP-CHANGE-ACTION " + code));
            if (phase == CardUpdatePhase.NOT_FETCHED) {
                conversation.put(CardUpdateState.NOT_FETCHED);
            } else {
                boolean fetched = phase == CardUpdatePhase.SHOWN || phase == CardUpdatePhase.INVALID
                        || phase == CardUpdatePhase.CONFIRM;
                conversation.put(new CardUpdateState(phase, card(commarea, "CCUP-OLD-", account, card, fetched),
                        card(commarea, "CCUP-NEW-", account, card, false)));
            }
        }
    }

    @Override
    public Optional<String> read(String name, Conversation conversation) {
        Optional<CardSelection> selection = conversation.get(CardSelection.class);
        Optional<CardUpdateState> update = conversation.get(CardUpdateState.class);
        Optional<CardListPage> page = conversation.get(CardListPage.class);
        Optional<Card> fetched = update.map(CardUpdateState::fetched);
        Optional<LocalDate> expiry = fetched.map(Card::expirationDate);
        return switch (name) {
            case "CDEMO-LAST-MAPSET", "CDEMO-LAST-MAP" -> Optional.empty();
            case "CDEMO-ACCT-ID" -> selection.map(s -> account(s.accountId()))
                    .or(() -> update.map(u -> fetched.map(c -> account(c.accountId())).orElse("0")))
                    .or(() -> page.map(p -> p.filter().accountId() == null ? "0" : account(p.filter().accountId())));
            case "CDEMO-CARD-NUM" -> selection.map(CardSelection::cardNumber)
                    .or(() -> update.map(u -> fetched.map(Card::number).orElse("0")))
                    .or(() -> page.map(p -> p.filter().cardNumber() == null ? "0" : p.filter().cardNumber()));
            case "WS-CA-SCREEN-NUM" -> Optional.of(page.map(p -> String.valueOf(p.pageNumber())).orElse(""));
            case "WS-CA-FIRST-CARD-NUM" -> Optional.of(page.map(CardListPage::firstCard).orElse(""));
            case "WS-CA-LAST-CARD-NUM" -> Optional.of(page.map(CardListPage::lastCard).orElse(""));
            case "WS-CA-NEXT-PAGE-IND" -> Optional.of(page.filter(CardListPage::hasNext).map(p -> "Y").orElse(""));
            case "WS-CA-LAST-PAGE-DISPLAYED" -> Optional.of(page.map(p -> p.lastPageShown() ? "0" : "9").orElse(""));
            case "CCUP-CHANGE-ACTION" -> Optional.of(update.map(u -> u.phase().code).orElse(""));
            case "CCUP-OLD-ACCTID" -> Optional.of(fetched.map(c -> account(c.accountId())).orElse(""));
            case "CCUP-OLD-CARDID" -> Optional.of(fetched.map(Card::number).orElse(""));
            case "CCUP-OLD-CVV-CD" -> Optional.of(fetched.map(c -> "%03d".formatted(c.cvv())).orElse(""));
            case "CCUP-OLD-CRDNAME" -> Optional.of(fetched.map(c -> c.embossedName().toUpperCase(Locale.ROOT)).orElse(""));
            case "CCUP-OLD-EXPYEAR" -> Optional.of(expiry.map(d -> "%04d".formatted(d.getYear())).orElse(""));
            case "CCUP-OLD-EXPMON" -> Optional.of(expiry.map(d -> "%02d".formatted(d.getMonthValue())).orElse(""));
            case "CCUP-OLD-EXPDAY" -> Optional.of(expiry.map(d -> "%02d".formatted(d.getDayOfMonth())).orElse(""));
            case "CCUP-OLD-CRDSTCD" -> Optional.of(fetched.map(Card::activeStatus).orElse(""));
            default -> throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
        };
    }

    /**
     * CCUP-OLD-* or CCUP-NEW-* as a card, or null when the group is absent and not {@code required}. The keys
     * default to CDEMO-*, and a required group the spec leaves out is the blank one legacy would hold.
     */
    private static Card card(Map<String, Object> commarea, String prefix, String account, String card, boolean required) {
        if (!required && !commarea.containsKey(prefix + "CRDNAME") && !commarea.containsKey(prefix + "CARDID")) {
            return null;
        }
        String acctId = value(commarea, prefix + "ACCTID");
        String cardId = value(commarea, prefix + "CARDID");
        String cvv = value(commarea, prefix + "CVV-CD");
        String year = value(commarea, prefix + "EXPYEAR");
        String month = value(commarea, prefix + "EXPMON");
        String day = value(commarea, prefix + "EXPDAY");
        LocalDate expiry = year.isBlank() || month.isBlank() || day.isBlank() ? null
                : LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        return new Card(cardId.isBlank() ? card : cardId, Long.parseLong(acctId.isBlank() ? account : acctId),
                cvv.isBlank() ? 0 : Integer.parseInt(cvv), value(commarea, prefix + "CRDNAME"), expiry,
                value(commarea, prefix + "CRDSTCD"));
    }

    private static String value(Map<String, Object> map, String name) {
        return text(map.get(name));
    }

    private static boolean zero(String value) {
        return value.isBlank() || value.matches("0+");
    }

    private static String account(long id) {
        return "%011d".formatted(id);
    }
}
