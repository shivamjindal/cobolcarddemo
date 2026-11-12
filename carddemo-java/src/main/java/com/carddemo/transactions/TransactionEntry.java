package com.carddemo.transactions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** COTRN02C's field checks, in the order the first failure wins. */
final class TransactionEntry {

    /** Lookups the key checks need. A failure throws {@link Unavailable}. */
    interface Reads {
        Optional<String> cardFor(long accountId);

        Optional<Long> accountFor(String cardNumber);
    }

    static final class Unavailable extends RuntimeException {
    }

    record Draft(String cardNumber, String typeCode, int categoryCode, String source, String description,
                 BigDecimal amount, LocalDate originated, LocalDate processed, long merchantId,
                 String merchantName, String merchantCity, String merchantZip) {
    }

    sealed interface Decision permits Reject, Accept {
        Map<String, String> screen();
    }

    record Reject(String message, String cursor, Map<String, String> screen) implements Decision {
    }

    record Accept(Draft draft, Map<String, String> screen) implements Decision {
    }

    private record Blank(String name, String label) {
    }

    private static final Blank[] REQUIRED = {
            new Blank("TTYPCD", "Type CD"),
            new Blank("TCATCD", "Category CD"),
            new Blank("TRNSRC", "Source"),
            new Blank("TDESC", "Description"),
            new Blank("TRNAMT", "Amount"),
            new Blank("TORIGDT", "Orig Date"),
            new Blank("TPROCDT", "Proc Date"),
            new Blank("MID", "Merchant ID"),
            new Blank("MNAME", "Merchant Name"),
            new Blank("MCITY", "Merchant City"),
            new Blank("MZIP", "Merchant Zip")
    };

    /** CEEDAYS feedback 2513, which the legacy program ignored and a timestamp cannot store. */
    private static final LocalDate GREGORIAN = LocalDate.of(1582, 10, 15);
    private static final String ISO = "\\d{4}-\\d{2}-\\d{2}";
    private static final String AMOUNT = "[+-]\\d{8}\\.\\d{2}";

    private TransactionEntry() {
    }

    static Decision check(Map<String, String> fields, Reads reads) {
        Map<String, String> screen = new LinkedHashMap<>(fields);
        Optional<Reject> keyed = normalizeKeys(screen, reads);
        return keyed.isPresent() ? keyed.get() : data(screen);
    }

    /** The account and card, written back onto {@code screen}, or the first key-field error. */
    static Optional<Reject> normalizeKeys(Map<String, String> screen, Reads reads) {
        String account = text(screen, "ACTIDIN");
        String card = text(screen, "CARDNIN");
        if (!account.isBlank()) {
            if (!account.matches("\\d{11}")) {
                return Optional.of(reject(screen, "Account ID must be Numeric...", "ACTIDIN"));
            }
            long id = Long.parseLong(account);
            screen.put("ACTIDIN", "%011d".formatted(id));
            Optional<String> found;
            try {
                found = reads.cardFor(id);
            } catch (Unavailable e) {
                return Optional.of(reject(screen, "Unable to lookup Acct in XREF AIX file...", "ACTIDIN"));
            }
            if (found.isEmpty()) {
                return Optional.of(reject(screen, "Account ID NOT found...", "ACTIDIN"));
            }
            screen.put("CARDNIN", found.get());
            return Optional.empty();
        }
        if (!card.isBlank()) {
            if (!card.matches("\\d{16}")) {
                return Optional.of(reject(screen, "Card Number must be Numeric...", "CARDNIN"));
            }
            String number = "%016d".formatted(Long.parseLong(card));
            screen.put("CARDNIN", number);
            Optional<Long> found;
            try {
                found = reads.accountFor(number);
            } catch (Unavailable e) {
                return Optional.of(reject(screen, "Unable to lookup Card # in XREF file...", "CARDNIN"));
            }
            if (found.isEmpty()) {
                return Optional.of(reject(screen, "Card Number NOT found...", "CARDNIN"));
            }
            screen.put("ACTIDIN", "%011d".formatted(found.get()));
            return Optional.empty();
        }
        return Optional.of(reject(screen, "Account or Card Number must be entered...", "ACTIDIN"));
    }

    /** PF5: the highest-keyed transaction, cut to the widths of the screen fields. */
    static Map<String, String> copied(Map<String, String> screen, Transaction last) {
        Map<String, String> fields = new LinkedHashMap<>(screen);
        fields.put("TTYPCD", cut(last.typeCode(), 2));
        fields.put("TCATCD", "%04d".formatted(last.categoryCode()));
        fields.put("TRNSRC", cut(last.source(), 10));
        fields.put("TDESC", cut(last.description(), 60));
        fields.put("TRNAMT", edit(last.amount()));
        fields.put("TORIGDT", day(last.originated()));
        fields.put("TPROCDT", day(last.processed()));
        fields.put("MID", "%09d".formatted(last.merchantId()));
        fields.put("MNAME", cut(last.merchantName(), 30));
        fields.put("MCITY", cut(last.merchantCity(), 25));
        fields.put("MZIP", cut(last.merchantZip(), 10));
        return fields;
    }

    private static Decision data(Map<String, String> screen) {
        for (Blank field : REQUIRED) {
            if (text(screen, field.name).isBlank()) {
                return reject(screen, field.label + " can NOT be empty...", field.name);
            }
        }
        if (!text(screen, "TTYPCD").matches("\\d{2}")) {
            return reject(screen, "Type CD must be Numeric...", "TTYPCD");
        }
        if (!text(screen, "TCATCD").matches("\\d{4}")) {
            return reject(screen, "Category CD must be Numeric...", "TCATCD");
        }
        BigDecimal amount = parseAmount(text(screen, "TRNAMT"));
        if (amount == null) {
            return reject(screen, "Amount should be in format -99999999.99", "TRNAMT");
        }
        String orig = text(screen, "TORIGDT");
        String proc = text(screen, "TPROCDT");
        if (!orig.matches(ISO)) {
            return reject(screen, "Orig Date should be in format YYYY-MM-DD", "TORIGDT");
        }
        if (!proc.matches(ISO)) {
            return reject(screen, "Proc Date should be in format YYYY-MM-DD", "TPROCDT");
        }
        screen.put("TRNAMT", edit(amount));
        Optional<LocalDate> originated = calendar(orig);
        if (originated.isEmpty()) {
            return reject(screen, "Orig Date - Not a valid date...", "TORIGDT");
        }
        Optional<LocalDate> processed = calendar(proc);
        if (processed.isEmpty()) {
            return reject(screen, "Proc Date - Not a valid date...", "TPROCDT");
        }
        String merchant = text(screen, "MID");
        if (!merchant.matches("\\d{9}")) {
            return reject(screen, "Merchant ID must be Numeric...", "MID");
        }
        String confirm = text(screen, "CONFIRM");
        if (confirm.equals("Y") || confirm.equals("y")) {
            return new Accept(new Draft(text(screen, "CARDNIN"), text(screen, "TTYPCD"),
                    Integer.parseInt(text(screen, "TCATCD")), text(screen, "TRNSRC"), text(screen, "TDESC"), amount,
                    originated.get(), processed.get(), Long.parseLong(merchant), text(screen, "MNAME"),
                    text(screen, "MCITY"), text(screen, "MZIP")), screen);
        }
        if (confirm.isEmpty() || confirm.equals("N") || confirm.equals("n")) {
            return reject(screen, "Confirm to add this transaction...", "CONFIRM");
        }
        return reject(screen, "Invalid value. Valid values are (Y/N)...", "CONFIRM");
    }

    private static BigDecimal parseAmount(String text) {
        if (!text.matches(AMOUNT)) {
            return null;
        }
        return new BigDecimal(text).setScale(2);
    }

    private static String edit(BigDecimal amount) {
        long cents = amount.setScale(2, RoundingMode.DOWN).movePointRight(2).abs().longValueExact();
        String digits = "%010d".formatted(cents);
        return (amount.signum() < 0 ? "-" : "+") + digits.substring(0, 8) + "." + digits.substring(8);
    }

    private static Optional<LocalDate> calendar(String text) {
        try {
            LocalDate date = LocalDate.parse(text);
            return date.isBefore(GREGORIAN) ? Optional.empty() : Optional.of(date);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static String day(LocalDateTime timestamp) {
        return timestamp == null ? "" : timestamp.toLocalDate().toString();
    }

    private static String cut(String value, int width) {
        return value.length() <= width ? value : value.substring(0, width);
    }

    private static String text(Map<String, String> fields, String name) {
        String value = fields.get(name);
        return value == null ? "" : value;
    }

    private static Reject reject(Map<String, String> screen, String message, String cursor) {
        return new Reject(message, cursor, screen);
    }
}
