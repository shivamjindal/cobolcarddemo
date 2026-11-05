package com.carddemo.dli;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import com.carddemo.records.Fields;
import com.carddemo.vsam.Field;
import com.carddemo.vsam.Pic;

/**
 * Field access on a segment image, and the checks the segment records make on their values. Text is held
 * without trailing spaces. A text field is either all LOW-VALUES, held as that many NULs and stored as NULL,
 * or holds no NUL, because Postgres text cannot. A packed field reads as its digits whatever its sign nibble
 * unless that is D, and is written back with the picture's sign; a binary field holds what its bytes can.
 */
public final class Image {

    private static final Charset EBCDIC = Charset.forName("Cp037");

    private Image() {}

    /** An EBCDIC segment, as DFSURGU0 unloads it, recoded field by field: text through Cp037, packed and binary as is. */
    public static byte[] fromEbcdic(byte[] ebcdic, List<Field> layout) {
        byte[] image = Fields.spaces(ebcdic.length);
        for (Field field : layout) {
            byte[] slice = Arrays.copyOfRange(ebcdic, field.offset(), field.offset() + field.length());
            byte[] recoded = switch (field.pic()) {
                case Pic.Alnum ignored -> new String(slice, EBCDIC).getBytes(ISO_8859_1);
                case Pic.Zoned ignored -> new String(slice, EBCDIC).getBytes(ISO_8859_1);
                case Pic.Packed ignored -> slice;
                case Pic.Binary ignored -> slice;
            };
            System.arraycopy(recoded, 0, image, field.offset(), field.length());
        }
        return image;
    }

    static String text(byte[] image, Field field) {
        return stripSpaces(Fields.text(image, field.offset(), field.length()));
    }

    static void text(byte[] image, Field field, String value) {
        Fields.text(image, field.offset(), field.length(), value);
    }

    static BigDecimal number(byte[] image, Field field) {
        return switch (field.pic()) {
            case Pic.Packed packed -> Fields.packed(image, field.offset(), packed.digits(), packed.scale());
            case Pic.Zoned zoned -> Fields.zoned(image, field.offset(), field.length(), zoned.scale());
            case Pic.Binary binary -> new BigDecimal(new BigInteger(
                    Arrays.copyOfRange(image, field.offset(), field.offset() + field.length())), binary.scale());
            case Pic.Alnum ignored -> throw new IllegalArgumentException(field.cobolName() + " is not numeric");
        };
    }

    static void number(byte[] image, Field field, BigDecimal value) {
        switch (field.pic()) {
            case Pic.Packed packed -> Fields.packed(image, field.offset(), packed.digits(), packed.scale(), packed.signed(), value);
            case Pic.Zoned zoned -> Fields.zoned(image, field.offset(), field.length(), zoned.scale(), zoned.signed(), value);
            case Pic.Binary binary -> {
                long unscaled = value.movePointRight(binary.scale()).longValueExact();
                for (int i = field.offset() + field.length() - 1; i >= field.offset(); i--) {
                    image[i] = (byte) unscaled;
                    unscaled >>= 8;
                }
            }
            case Pic.Alnum ignored -> throw new IllegalArgumentException(field.cobolName() + " is not numeric");
        }
    }

    /** {@code value} if it fits the field as text the image and a column can hold back. */
    static String checkText(String value, Field field) {
        if (value == null || value.length() > field.length() || !stripSpaces(value).equals(value)
                || value.chars().anyMatch(c -> c > 0xFF)
                || value.indexOf('\0') >= 0 && !value.equals(lowValues(field))) {
            throw new IllegalArgumentException(field.cobolName() + " cannot hold '" + value + "'");
        }
        return value;
    }

    /** The text of a field of all LOW-VALUES. */
    public static String lowValues(Field field) {
        return "\0".repeat(field.length());
    }

    /** {@code value} at the field's scale, if its digits fit the picture. */
    static BigDecimal checkNumber(BigDecimal value, Field field) {
        int digits;
        int scale;
        boolean signed;
        switch (field.pic()) {
            case Pic.Packed p -> { digits = p.digits(); scale = p.scale(); signed = p.signed(); }
            case Pic.Zoned z -> { digits = z.digits(); scale = z.scale(); signed = z.signed(); }
            case Pic.Binary b -> { digits = b.digits(); scale = b.scale(); signed = b.signed(); }
            case Pic.Alnum ignored -> throw new IllegalArgumentException(field.cobolName() + " is not numeric");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(field.cobolName() + " has " + scale + " decimals, not " + value);
        }
        boolean fits = field.pic() instanceof Pic.Binary
                ? scaled.unscaledValue().bitLength() < field.length() * 8 - (signed ? 0 : -1)
                : scaled.unscaledValue().abs().toString().length() <= digits;
        if (!fits || (!signed && scaled.signum() < 0)) {
            throw new IllegalArgumentException(field.cobolName() + " PIC " + field.picClause() + " cannot hold " + value);
        }
        return scaled;
    }

    static long checkInteger(long value, Field field) {
        return checkNumber(BigDecimal.valueOf(value), field).longValueExact();
    }

    private static String stripSpaces(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(0, end);
    }
}
