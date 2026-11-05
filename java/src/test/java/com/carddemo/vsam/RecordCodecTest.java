package com.carddemo.vsam;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordCodecTest {

    private static final Charset EBCDIC = Charset.forName("Cp037");
    private final RecordCodec codec = new RecordCodec();

    @Test
    void roundTripsZonedSignedPackedAndBinary() {
        assertArrayEquals(bytes(0xF0, 0xF0, 0xF1, 0xF2), roundTrip(zoned("AMT", 4, 0, false), new SqlType.Int(4), 12));
        assertArrayEquals(bytes(0xF0, 0xF0, 0xF1, 0xD2),
                roundTrip(zoned("AMT", 4, 0, true), new SqlType.Numeric(4, 0, true), new BigDecimal("-12")));
        assertArrayEquals(bytes(0xF0, 0xF1, 0xF2, 0xF3, 0xC4),
                roundTrip(zoned("AMT", 5, 2, true), new SqlType.Numeric(5, 2, true), new BigDecimal("12.34")));
        assertArrayEquals(bytes(0xF0, 0xF1, 0xF2, 0xF3, 0xD4),
                roundTrip(zoned("AMT", 5, 2, true), new SqlType.Numeric(5, 2, true), new BigDecimal("-12.34")));

        assertArrayEquals(bytes(0x12, 0x34, 0x5C),
                roundTrip(packed("AMT", 5, 0, true), new SqlType.Numeric(5, 0, true), new BigDecimal("12345")));
        assertArrayEquals(bytes(0x01, 0x23, 0x4C),
                roundTrip(packed("AMT", 4, 0, true), new SqlType.Numeric(4, 0, true), new BigDecimal("1234")));
        assertArrayEquals(bytes(0x00, 0x00, 0x5D),
                roundTrip(packed("AMT", 5, 0, true), new SqlType.Numeric(5, 0, true), new BigDecimal("-5")));
        assertArrayEquals(bytes(0x01, 0x2F),
                roundTrip(packed("AMT", 3, 0, false), new SqlType.Int(3), 12));

        assertArrayEquals(bytes(0x00, 0x01),
                roundTrip(binary("AMT", 4, 0, true), new SqlType.Numeric(4, 0, true), new BigDecimal("1")));
        assertArrayEquals(bytes(0xFF, 0xFF),
                roundTrip(binary("AMT", 4, 0, true), new SqlType.Numeric(4, 0, true), new BigDecimal("-1")));
        assertArrayEquals(bytes(0xFF, 0xFE),
                roundTrip(binary("AMT", 4, 0, true), new SqlType.Numeric(4, 0, true), new BigDecimal("-2")));
        assertArrayEquals(bytes(0x00, 0xFF),
                roundTrip(binary("AMT", 4, 0, false), new SqlType.Int(4), 255));
        assertArrayEquals(bytes(0x00, 0x01, 0xE2, 0x40),
                roundTrip(binary("AMT", 6, 0, true), new SqlType.Numeric(6, 0, true), new BigDecimal("123456")));
    }

    @Test
    void unsignedZonedCSignDecodesButEncodesAsF() {
        Field field = zoned("AMT", 4, 0, false);
        TableDef table = table(field, new SqlType.Int(4), field.length());
        byte[] source = bytes(0xF0, 0xF0, 0xF1, 0xC2);
        assertEquals(12, codec.decode(source, table)[0]);
        byte[] encoded = codec.encode(new Object[] {12}, table);
        assertArrayEquals(bytes(0xF0, 0xF0, 0xF1, 0xF2), encoded);
        assertFalse(Arrays.equals(source, encoded));
    }

    @Test
    void textTrimsOnlyTrailingSpacesAndDatesRoundTrip() {
        Field name = new Field("NAME", 0, 4, new Pic.Alnum(4), "X(4)");
        TableDef text = table(name, new SqlType.Text(4), 4);
        byte[] raw = "AB  ".getBytes(EBCDIC);
        assertEquals("AB", codec.decode(raw, text)[0]);
        assertArrayEquals(raw, codec.encode(new Object[] {"AB"}, text));
        assertEquals("", codec.decode("    ".getBytes(EBCDIC), text)[0]);
        assertEquals(" A", codec.decode(" A  ".getBytes(EBCDIC), text)[0]);

        Field opened = new Field("OPENED", 0, 10, new Pic.Alnum(10), "X(10)");
        TableDef date = table(opened, new SqlType.Date(), 10);
        byte[] day = "2014-11-20".getBytes(EBCDIC);
        assertEquals(LocalDate.of(2014, 11, 20), codec.decode(day, date)[0]);
        assertArrayEquals(day, codec.encode(codec.decode(day, date), date));

        Field stamped = new Field("STAMP", 0, 26, new Pic.Alnum(26), "X(26)");
        TableDef timestamp = table(stamped, new SqlType.Timestamp(), 26);
        byte[] when = "2022-06-10 19:27:53.000000".getBytes(EBCDIC);
        assertEquals(LocalDateTime.of(2022, 6, 10, 19, 27, 53), codec.decode(when, timestamp)[0]);
        assertArrayEquals(when, codec.encode(codec.decode(when, timestamp), timestamp));
    }

    @Test
    void fillersAreEncodedAsEbcdicSpaces() {
        Field name = new Field("NAME", 0, 2, new Pic.Alnum(2), "X(2)");
        Field filler = new Field("FILLER", 2, 2, new Pic.Alnum(2), "X(2)");
        Column column = new Column(name, "name", new SqlType.Text(2));
        TableDef table = new TableDef(
                new Dataset("sample", "SAMPLE", "SAMPLE", "SAMPLE.PS"),
                new Layout("REC", 4, List.of(name), List.of(filler)),
                List.of(column),
                List.of(column),
                List.of(),
                List.of(),
                List.of());
        byte[] encoded = codec.encode(new Object[] {"AB"}, table);
        assertArrayEquals("AB".getBytes(EBCDIC), Arrays.copyOf(encoded, 2));
        assertEquals(0x40, encoded[2] & 0xFF);
        assertEquals(0x40, encoded[3] & 0xFF);
    }

    @Test
    void badZonedDigitNamesTheField() {
        Field field = zoned("ACCT-ID", 2, 0, false);
        TableDef table = table(field, new SqlType.Int(2), 2);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> codec.decode(bytes(0xF0, 0x4B), table));
        assertTrue(error.getMessage().contains("sample/REC/ACCT-ID"));
    }

    @Test
    void mapsPicturesFromObservedValues() {
        Field date = new Field("OPEN", 0, 10, new Pic.Alnum(10), "X(10)");
        assertInstanceOf(SqlType.Date.class, SqlTypes.map(date, List.of("2014-11-20", "2009-04-20")).type());
        assertInstanceOf(SqlType.Text.class, SqlTypes.map(date, List.of("2014-11-20", "ZEROAPR")).type());
        assertInstanceOf(SqlType.Text.class, SqlTypes.map(date, List.of("2014-02-31")).type());

        Field stamp = new Field("TS", 0, 26, new Pic.Alnum(26), "X(26)");
        assertInstanceOf(SqlType.Timestamp.class,
                SqlTypes.map(stamp, List.of("2022-06-10 19:27:53.000000")).type());
        assertInstanceOf(SqlType.Text.class, SqlTypes.map(stamp, List.of("")).type());

        Field big = new Field("ID", 0, 11, new Pic.Zoned(11, 0, false), "9(11)");
        assertInstanceOf(SqlType.BigInt.class, SqlTypes.map(big, List.of()).type());
        Field small = new Field("ID", 0, 9, new Pic.Zoned(9, 0, false), "9(09)");
        assertInstanceOf(SqlType.Int.class, SqlTypes.map(small, List.of()).type());
        Field money = new Field("BAL", 0, 12, new Pic.Zoned(12, 2, true), "S9(10)V99");
        SqlType.Numeric numeric = assertInstanceOf(SqlType.Numeric.class, SqlTypes.map(money, List.of()).type());
        assertEquals(12, numeric.precision());
        assertEquals(2, numeric.scale());
        assertTrue(numeric.signed());
        assertEquals("acct_expiraion_date", Names.column("ACCT-EXPIRAION-DATE"));
    }

    private byte[] roundTrip(Field field, SqlType type, Object value) {
        TableDef table = table(field, type, field.length());
        byte[] encoded = codec.encode(new Object[] {value}, table);
        Object[] decoded = codec.decode(encoded, table);
        if (value instanceof BigDecimal decimal) {
            assertEquals(0, decimal.compareTo((BigDecimal) decoded[0]));
        } else if (value instanceof Integer number && decoded[0] instanceof Integer) {
            assertEquals(number, decoded[0]);
        } else if (value instanceof Integer number && decoded[0] instanceof BigDecimal decimal) {
            assertEquals(0, BigDecimal.valueOf(number).compareTo(decimal));
        } else {
            assertEquals(value, decoded[0]);
        }
        assertArrayEquals(encoded, codec.encode(decoded, table));
        return encoded;
    }

    private static TableDef table(Field field, SqlType type, int recordLength) {
        Column column = new Column(field, Names.column(field.cobolName()), type);
        return new TableDef(
                new Dataset("sample", "SAMPLE", "SAMPLE", "SAMPLE.PS"),
                new Layout("REC", recordLength, List.of(field), List.of()),
                List.of(column),
                List.of(column),
                List.of(),
                List.of(),
                List.of());
    }

    private static Field zoned(String name, int digits, int scale, boolean signed) {
        return new Field(name, 0, digits, new Pic.Zoned(digits, scale, signed), "9");
    }

    private static Field packed(String name, int digits, int scale, boolean signed) {
        int length = (digits + 2) / 2;
        return new Field(name, 0, length, new Pic.Packed(digits, scale, signed), "9");
    }

    private static Field binary(String name, int digits, int scale, boolean signed) {
        int length = digits <= 4 ? 2 : digits <= 9 ? 4 : 8;
        return new Field(name, 0, length, new Pic.Binary(digits, scale, signed), "9");
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
