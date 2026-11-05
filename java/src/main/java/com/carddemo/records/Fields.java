package com.carddemo.records;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * DISPLAY and COMP-3 fields of a record image as GnuCOBOL holds it with {@code -fsign=EBCDIC}: one latin-1 byte
 * per character, a signed zoned field's sign overpunched on its last digit ({@code {A-I} positive, {@code }J-R}
 * negative), a packed field two digits a byte with its sign in the last nibble. Stores truncate as MOVE does:
 * text on the right, numbers on excess decimals and high-order digits.
 */
public final class Fields {

    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";

    private Fields() {}

    public static byte[] spaces(int length) {
        byte[] record = new byte[length];
        Arrays.fill(record, (byte) ' ');
        return record;
    }

    public static String text(byte[] record, int offset, int length) {
        return new String(record, offset, length, ISO_8859_1);
    }

    public static void text(byte[] record, int offset, int length, String value) {
        byte[] bytes = value.getBytes(ISO_8859_1);
        Arrays.fill(record, offset, offset + length, (byte) ' ');
        System.arraycopy(bytes, 0, record, offset, Math.min(bytes.length, length));
    }

    /** Digits, the last one optionally overpunched when {@code signed}. */
    public static boolean isZoned(byte[] record, int offset, int length, boolean signed) {
        for (int i = offset; i < offset + length - 1; i++) {
            if (record[i] < '0' || record[i] > '9') {
                return false;
            }
        }
        char last = (char) (record[offset + length - 1] & 0xFF);
        return last >= '0' && last <= '9'
                || signed && (POSITIVE.indexOf(last) >= 0 || NEGATIVE.indexOf(last) >= 0);
    }

    public static BigDecimal zoned(byte[] record, int offset, int length, int scale) {
        if (!isZoned(record, offset, length, true)) {
            throw new IllegalArgumentException("'" + text(record, offset, length) + "' is not zoned decimal");
        }
        char last = (char) (record[offset + length - 1] & 0xFF);
        int positive = POSITIVE.indexOf(last);
        int negative = NEGATIVE.indexOf(last);
        String digits = text(record, offset, length - 1)
                + (positive >= 0 ? (char) ('0' + positive) : negative >= 0 ? (char) ('0' + negative) : last);
        BigInteger unscaled = new BigInteger(digits);
        return new BigDecimal(negative >= 0 ? unscaled.negate() : unscaled, scale);
    }

    public static void zoned(byte[] record, int offset, int length, int scale, boolean signed, BigDecimal value) {
        BigInteger unscaled = value.movePointRight(scale).toBigInteger();
        String digits = lowOrder(unscaled.abs().toString(), length);
        if (signed) {
            String overpunch = unscaled.signum() < 0 ? NEGATIVE : POSITIVE;
            digits = digits.substring(0, length - 1) + overpunch.charAt(digits.charAt(length - 1) - '0');
        }
        text(record, offset, length, digits);
    }

    /** A packed field of {@code digits} digits occupies {@code digits / 2 + 1} bytes. */
    public static BigDecimal packed(byte[] record, int offset, int digits, int scale) {
        int length = digits / 2 + 1;
        StringBuilder text = new StringBuilder(length * 2);
        for (int i = offset; i < offset + length; i++) {
            text.append((char) ('0' + ((record[i] >> 4) & 0x0F))).append((char) ('0' + (record[i] & 0x0F)));
        }
        int sign = record[offset + length - 1] & 0x0F;
        text.setLength(text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > '9') {
                throw new IllegalArgumentException("packed field at " + offset + " has a non-decimal digit");
            }
        }
        BigInteger unscaled = new BigInteger(text.toString());
        return new BigDecimal(sign == 0x0D ? unscaled.negate() : unscaled, scale);
    }

    public static void packed(byte[] record, int offset, int digits, int scale, boolean signed, BigDecimal value) {
        int length = digits / 2 + 1;
        BigInteger unscaled = value.movePointRight(scale).toBigInteger();
        String nibbles = lowOrder(lowOrder(unscaled.abs().toString(), digits), length * 2 - 1);
        int sign = !signed ? 0x0F : unscaled.signum() < 0 ? 0x0D : 0x0C;
        for (int i = 0; i < length; i++) {
            int high = nibbles.charAt(2 * i) - '0';
            int low = i == length - 1 ? sign : nibbles.charAt(2 * i + 1) - '0';
            record[offset + i] = (byte) (high << 4 | low);
        }
    }

    private static String lowOrder(String digits, int length) {
        String padded = digits.length() >= length ? digits : "0".repeat(length - digits.length()) + digits;
        return padded.substring(padded.length() - length);
    }
}
