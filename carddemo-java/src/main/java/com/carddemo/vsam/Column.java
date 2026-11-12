package com.carddemo.vsam;

import com.carddemo.copybook.Field;
import com.carddemo.copybook.Pic;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

public record Column(Field field, ColumnKind kind) {

    // Sample data uses ISO with a space; batch posting (CBTRN02C, CBACT04C) writes DB2 format.
    private static final List<DateTimeFormatter> TIMESTAMP_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS"));

    public static Column of(Field field, ColumnKind override) {
        if (override == null) {
            return new Column(field, switch (field.pic()) {
                case Pic.Alphanumeric a -> ColumnKind.TEXT;
                case Pic.Numeric n -> n.scale() == 0 ? ColumnKind.INTEGER : ColumnKind.DECIMAL;
            });
        }
        int expected = override == ColumnKind.DATE ? 10 : 26;
        if (!(field.pic() instanceof Pic.Alphanumeric a) || a.length() != expected) {
            throw new IllegalArgumentException(field.name() + " cannot hold a " + override);
        }
        return new Column(field, override);
    }

    public String name() {
        return field.name().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public String ddl() {
        return switch (kind) {
            case TEXT -> name() + " varchar(" + field.length() + ") NOT NULL";
            case DATE -> name() + " date";
            case TIMESTAMP -> name() + " timestamp(6)";
            case INTEGER -> {
                Pic.Numeric n = numeric();
                String type = n.digits() <= 4 ? "smallint" : n.digits() <= 9 ? "integer" : "bigint";
                String max = "9".repeat(n.digits());
                String min = n.signed() ? "-" + max : "0";
                yield name() + " " + type + " NOT NULL CHECK (" + name() + " BETWEEN " + min + " AND " + max + ")";
            }
            case DECIMAL -> {
                Pic.Numeric n = numeric();
                String check = n.signed() ? "" : " CHECK (" + name() + " >= 0)";
                yield name() + " numeric(" + n.digits() + ", " + n.scale() + ") NOT NULL" + check;
            }
        };
    }

    public Object decode(String record) {
        String raw = record.substring(field.offset(), field.end());
        return switch (kind) {
            case TEXT -> {
                if (raw.indexOf('\u0000') >= 0) {
                    throw new IllegalArgumentException(field.name() + " contains LOW-VALUES");
                }
                yield raw.stripTrailing();
            }
            case DATE -> raw.isBlank() ? null : LocalDate.parse(raw);
            case TIMESTAMP -> raw.isBlank() ? null : timestamp(raw);
            case INTEGER -> {
                BigInteger value = zoned(raw, numeric()).toBigIntegerExact();
                yield numeric().digits() <= 9 ? (Object) value.intValueExact() : value.longValueExact();
            }
            case DECIMAL -> zoned(raw, numeric());
        };
    }

    private Pic.Numeric numeric() {
        return (Pic.Numeric) field.pic();
    }

    /** DISPLAY numeric with the sign overpunched on the last digit, as text decoded from EBCDIC or ASCII. */
    static BigDecimal zoned(String raw, Pic.Numeric pic) {
        if (pic.usage() != Pic.Usage.DISPLAY) {
            throw new UnsupportedOperationException(pic.usage() + " fields are binary, not text");
        }
        char last = raw.charAt(raw.length() - 1);
        boolean negative = last == '}' || (last >= 'J' && last <= 'R');
        char digit = switch (last) {
            case '{', '}' -> '0';
            default -> last >= 'A' && last <= 'I' ? (char) ('1' + last - 'A')
                    : last >= 'J' && last <= 'R' ? (char) ('1' + last - 'J')
                    : last;
        };
        String digits = raw.substring(0, raw.length() - 1) + digit;
        if (!digits.matches("\\d+") || (negative && !pic.signed())) {
            throw new IllegalArgumentException("Invalid zoned decimal '" + raw + "' for " + pic);
        }
        BigDecimal value = new BigDecimal(new BigInteger(digits), pic.scale());
        return negative ? value.negate() : value;
    }

    private static LocalDateTime timestamp(String raw) {
        for (DateTimeFormatter format : TIMESTAMP_FORMATS) {
            try {
                return LocalDateTime.parse(raw, format);
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new IllegalArgumentException("Unrecognized timestamp '" + raw + "'");
    }
}
