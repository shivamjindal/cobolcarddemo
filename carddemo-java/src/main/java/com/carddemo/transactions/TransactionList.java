package com.carddemo.transactions;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** COTRN00C: ten transactions a page in id order, a start-from filter, and S row selection. */
@Component
class TransactionList implements ScreenProgram {

    private static final int ROWS = 10;
    private static final DateTimeFormatter LIST_DATE = DateTimeFormatter.ofPattern("MM/dd/yy");

    private final TransactionRepository transactions;

    TransactionList(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Override
    public Program program() {
        return Program.COTRN00C;
    }

    /** Arrival always lists from the first key. The legacy PF5 path skipped that record. */
    @Override
    public Outcome arrive(Conversation conversation) {
        conversation.take(TransactionSelection.class);
        return page(conversation, transactions.pageFrom("", ROWS + 1), 1, null);
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        TransactionListPage current = conversation.get(TransactionListPage.class).orElse(TransactionListPage.FIRST);
        return switch (aid) {
            case ENTER -> enter(conversation, fields);
            case PF3 -> new Outcome.Transfer(Program.COMEN01C);
            case PF7 -> backward(conversation, current);
            case PF8 -> forward(conversation, current);
            default -> again(conversation, current, Message.INVALID_KEY);
        };
    }

    /** The first marked row wins. S with an id transfers; any other mark re-lists and keeps its message. */
    private Outcome enter(Conversation conversation, Map<String, String> fields) {
        for (int row = 1; row <= ROWS; row++) {
            String mark = fields.getOrDefault("SEL%04d".formatted(row), "");
            if (mark.isBlank()) {
                continue;
            }
            String id = fields.getOrDefault("TRNID%02d".formatted(row), "");
            if (mark.equalsIgnoreCase("S") && !id.isBlank()) {
                conversation.put(new TransactionSelection(id));
                return new Outcome.Transfer(Program.COTRN01C);
            }
            if (!mark.equalsIgnoreCase("S")) {
                return search(conversation, fields, Message.error("Invalid selection. Valid value is S"));
            }
            break;
        }
        return search(conversation, fields, null);
    }

    private Outcome search(Conversation conversation, Map<String, String> fields, Message message) {
        String typed = fields.getOrDefault("TRNIDIN", "");
        if (!typed.isEmpty() && !numericId(typed)) {
            return new Outcome.Show(Map.of("TRNIDIN", typed), Message.error("Tran ID must be Numeric ..."), "TRNIDIN");
        }
        return page(conversation, transactions.pageFrom(typed, ROWS + 1), 1, message);
    }

    /** A short id is space-padded on the 3270 and fails IS NUMERIC; the adapter has already stripped those spaces. */
    private static boolean numericId(String typed) {
        return typed.matches("\\d{16}");
    }

    private Outcome backward(Conversation conversation, TransactionListPage current) {
        if (current.pageNumber() <= 1) {
            return again(conversation, current, Message.info("You are already at the top of the page..."));
        }
        List<Transaction> before = transactions.pageBefore(current.firstId(), ROWS + 1);
        if (before.size() > ROWS) {
            String start = before.get(ROWS - 1).id();
            return page(conversation, transactions.pageFrom(start, ROWS + 1), current.pageNumber() - 1, null);
        }
        return page(conversation, transactions.pageFrom("", ROWS + 1), 1,
                Message.info("You have reached the top of the page..."));
    }

    private Outcome forward(Conversation conversation, TransactionListPage current) {
        if (!current.hasNext()) {
            return again(conversation, current, Message.info("You are already at the bottom of the page..."));
        }
        return page(conversation, transactions.pageAfter(current.lastId(), ROWS + 1), current.pageNumber() + 1, null);
    }

    private Outcome again(Conversation conversation, TransactionListPage current, Message message) {
        return page(conversation, transactions.pageFrom(current.firstId(), ROWS + 1), current.pageNumber(), message);
    }

    /** Rows holds up to one transaction past the page, which tells whether PF8 has a next page. */
    private Outcome page(Conversation conversation, List<Transaction> rows, int pageNumber, Message message) {
        List<Transaction> shown = rows.subList(0, Math.min(ROWS, rows.size()));
        boolean hasNext = rows.size() > ROWS;
        conversation.put(shown.isEmpty() ? new TransactionListPage("", "", pageNumber, false)
                : new TransactionListPage(shown.getFirst().id(), shown.getLast().id(), pageNumber, hasNext));
        Map<String, String> fields = new HashMap<>();
        fields.put("PAGENUM", "%08d".formatted(pageNumber));
        fields.put("TRNIDIN", "");
        for (int i = 0; i < shown.size(); i++) {
            String row = "%02d".formatted(i + 1);
            String amount = "%03d".formatted(i + 1);
            Transaction transaction = shown.get(i);
            fields.put("TRNID" + row, transaction.id());
            fields.put("TDATE" + row, listDate(transaction.originated()));
            fields.put("TDESC" + row, cut(transaction.description(), 26));
            fields.put("TAMT" + amount, amount(transaction.amount()));
        }
        if (message == null && shown.isEmpty()) {
            message = Message.info("You are at the top of the page...");
        } else if (message == null && !hasNext) {
            message = Message.info("You have reached the bottom of the page...");
        }
        return new Outcome.Show(fields, message, "TRNIDIN");
    }

    private static String amount(BigDecimal value) {
        return String.format(Locale.ROOT, "%+012.2f", value);
    }

    private static String listDate(LocalDateTime originated) {
        return originated == null ? "" : LIST_DATE.format(originated);
    }

    private static String cut(String value, int width) {
        if (value == null || value.length() <= width) {
            return value == null ? "" : value;
        }
        return value.substring(0, width);
    }
}
