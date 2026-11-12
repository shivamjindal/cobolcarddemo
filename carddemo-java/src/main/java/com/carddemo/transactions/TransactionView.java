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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** COTRN01C: reads one transaction by id. PF3 returns to the caller and PF5 opens the list. */
@Component
class TransactionView implements ScreenProgram {

    private static final List<String> DETAILS = List.of("TRNID", "CARDNUM", "TTYPCD", "TCATCD", "TRNSRC", "TDESC",
            "TRNAMT", "TORIGDT", "TPROCDT", "MID", "MNAME", "MCITY", "MZIP");

    private final TransactionRepository transactions;

    TransactionView(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Override
    public Program program() {
        return Program.COTRN01C;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return conversation.take(TransactionSelection.class).map(s -> show(s.transactionId())).orElseGet(TransactionView::blank);
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        return switch (aid) {
            case ENTER -> show(fields.getOrDefault("TRNIDIN", ""));
            case PF3 -> new Outcome.Transfer(conversation.from().orElse(Program.COMEN01C));
            case PF4 -> blank();
            case PF5 -> new Outcome.Transfer(Program.COTRN00C);
            default -> new Outcome.Show(new LinkedHashMap<>(fields), Message.INVALID_KEY, null);
        };
    }

    private Outcome show(String id) {
        if (id.isBlank()) {
            return screen(id, null, Message.error("Tran ID can NOT be empty..."));
        }
        return transactions.find(id).map(found -> screen(id, found, null))
                .orElseGet(() -> screen(id, null, Message.error("Transaction ID NOT found...")));
    }

    private static Outcome blank() {
        return screen("", null, null);
    }

    private static Outcome screen(String id, Transaction transaction, Message message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("TRNIDIN", id);
        if (transaction == null) {
            DETAILS.forEach(name -> fields.put(name, ""));
        } else {
            fields.put("TRNID", cut(transaction.id(), 16));
            fields.put("CARDNUM", cut(transaction.cardNumber(), 16));
            fields.put("TTYPCD", cut(transaction.typeCode(), 2));
            fields.put("TCATCD", "%04d".formatted(transaction.categoryCode()));
            fields.put("TRNSRC", cut(transaction.source(), 10));
            fields.put("TDESC", cut(transaction.description(), 60));
            fields.put("TRNAMT", amount(transaction.amount()));
            fields.put("TORIGDT", day(transaction.originated()));
            fields.put("TPROCDT", day(transaction.processed()));
            fields.put("MID", "%09d".formatted(transaction.merchantId()));
            fields.put("MNAME", cut(transaction.merchantName(), 30));
            fields.put("MCITY", cut(transaction.merchantCity(), 25));
            fields.put("MZIP", cut(transaction.merchantZip(), 10));
        }
        return new Outcome.Show(fields, message, "TRNIDIN");
    }

    private static String amount(BigDecimal value) {
        return String.format(Locale.ROOT, "%+012.2f", value);
    }

    private static String day(LocalDateTime timestamp) {
        return timestamp == null ? "" : timestamp.toLocalDate().toString();
    }

    private static String cut(String value, int width) {
        if (value == null || value.length() <= width) {
            return value == null ? "" : value;
        }
        return value.substring(0, width);
    }
}
