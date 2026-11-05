package com.carddemo.vsam;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

public final class RecordCodec {

    static final Charset EBCDIC = Charset.forName("Cp037");
    private static final byte SPACE = 0x40;

    public Object[] decode(byte[] record, TableDef table) {
        String where = table.dataset().table() + "/" + table.layout().recordName();
        if (record.length != table.layout().recordLength()) {
            throw bad(where, "record length " + record.length + " != " + table.layout().recordLength());
        }
        Object[] row = new Object[table.columns().size()];
        for (int i = 0; i < row.length; i++) {
            Column column = table.columns().get(i);
            row[i] = coerce(decodeRaw(record, column.field(), where), column, where);
        }
        return row;
    }

    public Object decodeRaw(byte[] record, Field field, String where) {
        int end = field.offset() + field.length();
        if (field.offset() < 0 || end > record.length) {
            throw bad(where, field.cobolName() + " offset " + field.offset() + " length " + field.length()
                    + " outside record of " + record.length);
        }
        byte[] slice = Arrays.copyOfRange(record, field.offset(), end);
        String at = where + "/" + field.cobolName();
        return switch (field.pic()) {
            case Pic.Alnum ignored -> decodeAlnum(slice);
            case Pic.Zoned zoned -> decodeZoned(slice, zoned, at);
            case Pic.Packed packed -> decodePacked(slice, packed, at);
            case Pic.Binary binary -> decodeBinary(slice, binary, at);
        };
    }

    public byte[] encode(Object[] row, TableDef table) {
        if (row.length != table.columns().size()) {
            throw bad(table.dataset().table(), "expected " + table.columns().size() + " values, got " + row.length);
        }
        byte[] out = new byte[table.layout().recordLength()];
        Arrays.fill(out, SPACE);
        for (int i = 0; i < row.length; i++) {
            Column column = table.columns().get(i);
            byte[] encoded = encodeField(row[i], column, table.dataset().table());
            System.arraycopy(encoded, 0, out, column.field().offset(), encoded.length);
        }
        return out;
    }

    private static String decodeAlnum(byte[] slice) {
        String text = new String(slice, EBCDIC);
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(0, end);
    }

    private static BigDecimal decodeZoned(byte[] slice, Pic.Zoned zoned, String where) {
        if (slice.length != zoned.digits()) {
            throw bad(where, "zoned length " + slice.length + " != digits " + zoned.digits());
        }
        StringBuilder digits = new StringBuilder(slice.length);
        int sign = 0;
        for (int i = 0; i < slice.length; i++) {
            int b = slice[i] & 0xFF;
            int lo = b & 0x0F;
            if (lo > 9) {
                throw bad(where, "digit nibble " + lo + " at byte " + i);
            }
            digits.append((char) ('0' + lo));
            if (i == slice.length - 1) {
                sign = b >>> 4;
            }
        }
        BigDecimal value = new BigDecimal(new BigInteger(digits.toString()), zoned.scale());
        return negative(sign, zoned.signed(), where) ? value.negate() : value;
    }

    private static BigDecimal decodePacked(byte[] slice, Pic.Packed packed, String where) {
        int length = (packed.digits() + 2) / 2;
        if (slice.length != length) {
            throw bad(where, "packed length " + slice.length + " != " + length);
        }
        int nibbleCount = slice.length * 2;
        int sign = nibble(slice, nibbleCount - 1);
        int leading = nibbleCount - 1 - packed.digits();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < nibbleCount - 1; i++) {
            int n = nibble(slice, i);
            if (i < leading) {
                if (n != 0) {
                    throw bad(where, "packed leading nibble " + n);
                }
                continue;
            }
            if (n > 9) {
                throw bad(where, "packed digit nibble " + n);
            }
            digits.append((char) ('0' + n));
        }
        BigDecimal value = new BigDecimal(new BigInteger(digits.toString()), packed.scale());
        return negative(sign, packed.signed(), where) ? value.negate() : value;
    }

    private static BigDecimal decodeBinary(byte[] slice, Pic.Binary binary, String where) {
        int length = binaryBytes(binary.digits());
        if (slice.length != length) {
            throw bad(where, "binary length " + slice.length + " != " + length);
        }
        BigInteger unscaled = binary.signed() ? new BigInteger(slice) : new BigInteger(1, slice);
        checkDigits(unscaled, binary.digits(), where);
        return new BigDecimal(unscaled, binary.scale());
    }

    private static Object coerce(Object raw, Column column, String where) {
        String at = where + "/" + column.field().cobolName();
        try {
            return switch (column.type()) {
                case SqlType.Text ignored -> (String) raw;
                case SqlType.Date ignored -> LocalDate.parse((String) raw, SqlTypes.DATE);
                case SqlType.Timestamp ignored -> LocalDateTime.parse((String) raw, SqlTypes.TIMESTAMP);
                case SqlType.Int ignored -> ((BigDecimal) raw).intValueExact();
                case SqlType.BigInt ignored -> ((BigDecimal) raw).longValueExact();
                case SqlType.Numeric numeric -> ((BigDecimal) raw).setScale(numeric.scale(), RoundingMode.UNNECESSARY);
            };
        } catch (RuntimeException e) {
            throw bad(at, e.getMessage());
        }
    }

    private byte[] encodeField(Object value, Column column, String dataset) {
        String where = dataset + "/" + column.field().cobolName();
        if (value == null) {
            throw bad(where, "null");
        }
        return switch (column.field().pic()) {
            case Pic.Alnum ignored -> encodeAlnum(text(value, column.type(), where), column.field().length(), where);
            case Pic.Zoned zoned -> encodeZoned(decimal(value, zoned.scale(), where), zoned, where);
            case Pic.Packed packed -> encodePacked(decimal(value, packed.scale(), where), packed, where);
            case Pic.Binary binary -> encodeBinary(decimal(value, binary.scale(), where), binary, column.field().length(), where);
        };
    }

    private static String text(Object value, SqlType type, String where) {
        return switch (type) {
            case SqlType.Text ignored -> (String) value;
            case SqlType.Date ignored -> ((LocalDate) value).format(SqlTypes.DATE);
            case SqlType.Timestamp ignored -> ((LocalDateTime) value).format(SqlTypes.TIMESTAMP);
            default -> throw bad(where, "not text");
        };
    }

    private static byte[] encodeAlnum(String text, int length, String where) {
        byte[] encoded = text.getBytes(EBCDIC);
        if (encoded.length > length) {
            throw bad(where, "text longer than " + length);
        }
        byte[] out = new byte[length];
        Arrays.fill(out, SPACE);
        System.arraycopy(encoded, 0, out, 0, encoded.length);
        return out;
    }

    private static byte[] encodeZoned(BigDecimal value, Pic.Zoned zoned, String where) {
        boolean negative = value.signum() < 0;
        String digits = digits(value, zoned.digits(), zoned.scale(), where);
        byte[] out = new byte[digits.length()];
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(i) - '0';
            int zone = 0xF;
            if (i == digits.length() - 1 && zoned.signed()) {
                zone = negative ? 0xD : 0xC;
            }
            out[i] = (byte) ((zone << 4) | digit);
        }
        return out;
    }

    private static byte[] encodePacked(BigDecimal value, Pic.Packed packed, String where) {
        int length = (packed.digits() + 2) / 2;
        String digits = digits(value, packed.digits(), packed.scale(), where);
        int sign = packed.signed() && value.signum() < 0 ? 0xD : packed.signed() ? 0xC : 0xF;
        byte[] out = new byte[length];
        int nibblePos = length * 2 - 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            putNibble(out, nibblePos, digits.charAt(i) - '0');
            nibblePos--;
        }
        putNibble(out, length * 2 - 1, sign);
        return out;
    }

    private static byte[] encodeBinary(BigDecimal value, Pic.Binary binary, int length, String where) {
        BigInteger unscaled = value.movePointRight(binary.scale()).toBigIntegerExact();
        if (!binary.signed() && unscaled.signum() < 0) {
            throw bad(where, "negative unsigned binary");
        }
        checkDigits(unscaled, binary.digits(), where);
        byte[] raw = unscaled.toByteArray();
        if (raw.length > length) {
            boolean extraSign = raw.length == length + 1 && (raw[0] == 0 || (binary.signed() && raw[0] == (byte) 0xFF));
            if (!extraSign) {
                throw bad(where, "binary value does not fit in " + length + " bytes");
            }
            raw = Arrays.copyOfRange(raw, 1, raw.length);
        }
        byte[] out = new byte[length];
        if (binary.signed() && unscaled.signum() < 0) {
            Arrays.fill(out, (byte) 0xFF);
        }
        System.arraycopy(raw, 0, out, length - raw.length, raw.length);
        return out;
    }

    private static String digits(BigDecimal value, int digits, int scale, String where) {
        BigInteger unscaled = value.abs().movePointRight(scale).toBigIntegerExact();
        String text = unscaled.toString();
        if (text.length() > digits) {
            throw bad(where, "value does not fit in " + digits + " digits");
        }
        return "0".repeat(digits - text.length()) + text;
    }

    private static BigDecimal decimal(Object value, int scale, String where) {
        BigDecimal decimal;
        if (value instanceof BigDecimal number) {
            decimal = number;
        } else if (value instanceof Integer || value instanceof Long) {
            decimal = BigDecimal.valueOf(((Number) value).longValue());
        } else {
            throw bad(where, "not a number");
        }
        try {
            return decimal.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw bad(where, e.getMessage());
        }
    }

    private static boolean negative(int sign, boolean signed, String where) {
        if (sign == 0xD) {
            if (!signed) {
                throw bad(where, "unsigned field has sign D");
            }
            return true;
        }
        // C on an unsigned field is accepted. Encode writes F, so the round trip reports it.
        if (sign == 0xC || sign == 0xF) {
            return false;
        }
        throw bad(where, "sign nibble " + Integer.toHexString(sign));
    }

    private static int nibble(byte[] slice, int index) {
        int b = slice[index / 2] & 0xFF;
        return index % 2 == 0 ? b >>> 4 : b & 0x0F;
    }

    private static void putNibble(byte[] out, int index, int value) {
        int byteIndex = index / 2;
        if (index % 2 == 0) {
            out[byteIndex] = (byte) ((out[byteIndex] & 0x0F) | (value << 4));
        } else {
            out[byteIndex] = (byte) ((out[byteIndex] & 0xF0) | value);
        }
    }

    private static int binaryBytes(int digits) {
        if (digits <= 4) {
            return 2;
        }
        if (digits <= 9) {
            return 4;
        }
        if (digits <= 18) {
            return 8;
        }
        throw new IllegalArgumentException("binary digits " + digits);
    }

    private static void checkDigits(BigInteger unscaled, int digits, String where) {
        if (unscaled.signum() != 0 && unscaled.abs().toString().length() > digits) {
            throw bad(where, "value exceeds " + digits + " digits");
        }
    }

    private static IllegalArgumentException bad(String where, String message) {
        return new IllegalArgumentException(where + ": " + message);
    }
}
