package com.carddemo.cards;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import com.carddemo.online.UserType;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * COCRDLIC: seven cards a page in card number order, account and card filters, and S/U row selection.
 * An admin lists every card; a regular user lists only the cards of the account typed in the filter.
 */
@Component
class CardList implements ScreenProgram {

    private static final int ROWS = 7;
    private static final String ACTIONS = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";
    private static final Set<Program> RETURNS_HERE = EnumSet.of(Program.COCRDSLC, Program.COCRDUPC);

    private final CardRepository cards;

    CardList(CardRepository cards) {
        this.cards = cards;
    }

    @Override
    public Program program() {
        return Program.COCRDLIC;
    }

    /** Back from detail or update, the page and filters the user left are read again. */
    @Override
    public Outcome arrive(Conversation conversation) {
        conversation.take(CardSelection.class);
        CardListPage page = conversation.from().filter(RETURNS_HERE::contains)
                .flatMap(from -> conversation.get(CardListPage.class)).orElse(CardListPage.FIRST);
        return list(conversation, Aid.ENTER, page, page.filter(),
                () -> forward(page.filter(), page.firstCard(), page.pageNumber()));
    }

    /** Filter edits, then selection edits, then paging; F7 and F8 page even with a row marked. */
    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        if (aid == Aid.PF3) {
            return new Outcome.Transfer(Program.COMEN01C);
        }
        CardListPage page = conversation.get(CardListPage.class).orElse(CardListPage.FIRST);
        String account = fields.getOrDefault("ACCTSID", "");
        String card = fields.getOrDefault("CARDSID", "");
        boolean accountBad = !CardKeys.blank(account) && !CardKeys.account(account);
        boolean cardBad = !CardKeys.blank(card) && !CardKeys.card(card);
        Map<Integer, String> marks = marks(page, fields);
        if (accountBad || cardBad) {
            Map<String, String> typed = Map.of("ACCTSID", CardKeys.blank(account) ? "" : account,
                    "CARDSID", CardKeys.blank(card) ? "" : card);
            return screen(page, typed, marks, Message.error(accountBad ? CardKeys.ACCOUNT_RULE : CardKeys.CARD_RULE),
                    false, accountBad ? "ACCTSID" : "CARDSID").protecting(selections(page));
        }
        CardFilter filter = new CardFilter(CardKeys.blank(account) ? null : Long.valueOf(account),
                CardKeys.blank(card) ? null : card);

        List<Integer> picked = new ArrayList<>();
        List<Integer> flagged = new ArrayList<>();
        marks.forEach((row, mark) -> {
            if (mark.equals("S") || mark.equals("U")) {
                picked.add(row);
            } else {
                flagged.add(row);
            }
        });
        if (picked.size() > 1 || !flagged.isEmpty()) {
            Message message = Message.error(picked.size() > 1 ? "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE"
                    : "INVALID ACTION CODE");
            if (picked.size() > 1) {
                flagged.addAll(picked);
            }
            int first = flagged.stream().mapToInt(Integer::intValue).min().orElseThrow();
            return screen(page, filterFields(filter), marks, message, true, "CRDSEL" + first);
        }
        if (picked.size() == 1 && aid != Aid.PF7 && aid != Aid.PF8) {
            int row = picked.getFirst();
            CardListPage.Row selected = page.rows().get(row - 1);
            conversation.put(new CardSelection(selected.accountId(), selected.cardNumber()));
            return new Outcome.Transfer(marks.get(row).equals("S") ? Program.COCRDSLC : Program.COCRDUPC);
        }

        if (!filter.equals(page.filter())) {
            return list(conversation, Aid.ENTER, page, filter, () -> forward(filter, "", 1));
        }
        return switch (aid) {
            case PF7 -> list(conversation, aid, page, filter, () -> page.pageNumber() <= 1
                    ? forward(filter, page.firstCard(), 1) : backward(page));
            case PF8 -> list(conversation, aid, page, filter, () -> page.hasNext()
                    ? forward(filter, page.lastCard(), page.pageNumber() + 1)
                    : forward(filter, page.firstCard(), page.pageNumber()));
            default -> list(conversation, Aid.ENTER, page, filter,
                    () -> forward(filter, page.firstCard(), page.pageNumber()));
        };
    }

    /** FLG-PROTECT-SELECT-ROWS-YES: a bad filter protects every selection field until it is fixed. */
    private static Set<String> selections(CardListPage page) {
        return IntStream.rangeClosed(1, page.rows().size()).mapToObj(row -> "CRDSEL" + row).collect(Collectors.toSet());
    }

    /** The non-blank selection codes typed on rows that hold a card. */
    private static Map<Integer, String> marks(CardListPage page, Map<String, String> fields) {
        Map<Integer, String> marks = new HashMap<>();
        for (int row = 1; row <= page.rows().size(); row++) {
            String mark = fields.getOrDefault("CRDSEL" + row, "");
            if (!mark.isBlank()) {
                marks.put(row, mark);
            }
        }
        return marks;
    }

    /** A regular user sees no cards until an account is typed; a failed read shows no rows. */
    private Outcome list(Conversation conversation, Aid aid, CardListPage previous, CardFilter filter,
                         Supplier<CardListPage> read) {
        if (conversation.user().type() == UserType.USER && filter.accountId() == null) {
            CardListPage empty = new CardListPage(1, "", "", false, false, filter, List.of());
            conversation.put(empty);
            return screen(empty, filterFields(filter), Map.of(),
                    Message.info("ENTER AN ACCOUNT NUMBER TO LIST ITS CARDS"), false, "ACCTSID");
        }
        CardListPage page;
        try {
            page = read.get();
        } catch (DataAccessException e) {
            CardListPage empty = new CardListPage(previous.pageNumber(), previous.firstCard(), previous.lastCard(),
                    previous.hasNext(), false, filter, List.of());
            conversation.put(empty);
            return screen(empty, filterFields(filter), Map.of(), CardKeys.fileError("READNEXT", e), false, "ACCTSID");
        }

        Message message = null;
        boolean actions = true;
        boolean lastPageShown = false;
        if (page.rows().isEmpty() && page.pageNumber() == 1) {
            message = Message.info("NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
            actions = false;
        } else if (!page.hasNext()) {
            message = Message.info("NO MORE RECORDS TO SHOW");
        }
        if (aid == Aid.PF7 && page.pageNumber() == 1) {
            message = Message.info("NO PREVIOUS PAGES TO DISPLAY");
            actions = false;
        } else if (aid == Aid.PF8 && !page.hasNext()) {
            lastPageShown = true;
            if (previous.lastPageShown()) {
                message = Message.info("NO MORE PAGES TO DISPLAY");
                actions = false;
            }
        }
        page = new CardListPage(page.pageNumber(), page.firstCard(), page.lastCard(), page.hasNext(), lastPageShown,
                page.filter(), page.rows());
        conversation.put(page);
        return screen(page, filterFields(filter), Map.of(), message, actions, "ACCTSID");
    }

    /** Reads one card past the page, which tells whether F8 has a next page and where it starts. */
    private CardListPage forward(CardFilter filter, String start, int pageNumber) {
        List<Card> read = cards.pageFrom(start, filter, ROWS + 1);
        List<Card> shown = read.subList(0, Math.min(ROWS, read.size()));
        boolean hasNext = read.size() > ROWS;
        String first = shown.isEmpty() ? start : shown.getFirst().number();
        String last = hasNext ? read.get(ROWS).number() : shown.isEmpty() ? start : shown.getLast().number();
        return new CardListPage(pageNumber, first, last, hasNext, false, filter, rows(shown));
    }

    /** Fewer than a page of cards before this one means the list starts over at page 1. */
    private CardListPage backward(CardListPage current) {
        List<Card> before = cards.pageBefore(current.firstCard(), current.filter(), ROWS);
        if (before.size() < ROWS) {
            return forward(current.filter(), "", 1);
        }
        return new CardListPage(current.pageNumber() - 1, before.getLast().number(), current.firstCard(), true, false,
                current.filter(), rows(before.reversed()));
    }

    private static List<CardListPage.Row> rows(List<Card> cards) {
        return cards.stream().map(c -> new CardListPage.Row(c.accountId(), c.number(), c.activeStatus())).toList();
    }

    private static Map<String, String> filterFields(CardFilter filter) {
        return Map.of("ACCTSID", filter.accountId() == null ? "" : CardKeys.account(filter.accountId()),
                "CARDSID", filter.cardNumber() == null ? "" : filter.cardNumber());
    }

    private static Outcome.Show screen(CardListPage page, Map<String, String> filter, Map<Integer, String> marks,
                                       Message message, boolean actions, String cursor) {
        Map<String, String> fields = new HashMap<>(filter);
        fields.put("PAGENO", String.valueOf(page.pageNumber()));
        if (actions) {
            fields.put("INFOMSG", ACTIONS);
        }
        for (int i = 0; i < page.rows().size(); i++) {
            int row = i + 1;
            CardListPage.Row card = page.rows().get(i);
            fields.put("CRDSEL" + row, marks.getOrDefault(row, ""));
            fields.put("ACCTNO" + row, CardKeys.account(card.accountId()));
            fields.put("CRDNUM" + row, card.cardNumber());
            fields.put("CRDSTS" + row, card.activeStatus());
        }
        return new Outcome.Show(fields, message, cursor);
    }
}
