package com.carddemo.vsam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;

public final class SqlTypes {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);
    public static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS")
            .withResolverStyle(ResolverStyle.STRICT);

    private SqlTypes() {}

    public record Mapped(SqlType type, String reason) {}

    public static Mapped map(Field field, List<?> values) {
        return switch (field.pic()) {
            case Pic.Alnum alnum -> mapText(alnum, values);
            case Pic.Zoned zoned -> mapNumber(zoned.digits(), zoned.scale(), zoned.signed(), "zoned");
            case Pic.Packed packed -> mapNumber(packed.digits(), packed.scale(), packed.signed(), "packed");
            case Pic.Binary binary -> mapNumber(binary.digits(), binary.scale(), binary.signed(), "binary");
        };
    }

    private static Mapped mapText(Pic.Alnum alnum, List<?> values) {
        if (alnum.length() == 10 && !values.isEmpty() && values.stream().allMatch(SqlTypes::isIsoDate)) {
            return new Mapped(new SqlType.Date(), "every source row parses as uuuu-MM-dd");
        }
        if (alnum.length() == 26 && !values.isEmpty() && values.stream().allMatch(SqlTypes::isIsoTimestamp)) {
            return new Mapped(new SqlType.Timestamp(), "every source row parses as uuuu-MM-dd HH:mm:ss.SSSSSS");
        }
        String reason = switch (alnum.length()) {
            case 10 -> values.isEmpty()
                    ? "no source rows, PIC X(10) stays text"
                    : "PIC X(10) is not a date on every row";
            case 26 -> values.isEmpty()
                    ? "no source rows, PIC X(26) stays text"
                    : "PIC X(26) is not a timestamp on every row";
            default -> "PIC X(" + alnum.length() + ") stored as text";
        };
        return new Mapped(new SqlType.Text(alnum.length()), reason);
    }

    private static Mapped mapNumber(int digits, int scale, boolean signed, String kind) {
        if (!signed && scale == 0) {
            if (digits >= 1 && digits <= 9) {
                return new Mapped(new SqlType.Int(digits),
                        "unsigned " + kind + " with " + digits + " digits, stored as integer");
            }
            if (digits <= 18) {
                return new Mapped(new SqlType.BigInt(digits),
                        "unsigned " + kind + " with " + digits + " digits, stored as bigint");
            }
            throw new IllegalArgumentException("cannot map unsigned " + kind + " with " + digits + " digits");
        }
        String sign = signed ? "signed " : "unsigned ";
        return new Mapped(new SqlType.Numeric(digits, scale, signed),
                sign + kind + " numeric(" + digits + "," + scale + ")");
    }

    private static boolean isIsoDate(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            return text.equals(DATE.format(LocalDate.parse(text, DATE)));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isIsoTimestamp(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            return text.equals(TIMESTAMP.format(LocalDateTime.parse(text, TIMESTAMP)));
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
