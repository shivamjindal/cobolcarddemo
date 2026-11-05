package com.carddemo.records;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class FieldsTest {

    @Test
    void signedZonedOverpunchesTheLastDigit() {
        byte[] record = Fields.spaces(12);
        Fields.zoned(record, 0, 12, 2, true, new BigDecimal("194.00"));
        assertEquals("00000001940{", Fields.text(record, 0, 12));
        Fields.zoned(record, 0, 12, 2, true, new BigDecimal("-58.25"));
        assertEquals("00000000582N", Fields.text(record, 0, 12));
        assertEquals(new BigDecimal("-58.25"), Fields.zoned(record, 0, 12, 2));
    }

    @Test
    void movesTruncateHighOrderDigitsAndExcessDecimals() {
        byte[] record = Fields.spaces(4);
        Fields.zoned(record, 0, 4, 1, false, new BigDecimal("-12345.67"));
        assertEquals("3456", Fields.text(record, 0, 4));
        Fields.text(record, 0, 4, "ABCDEF");
        assertEquals("ABCD", Fields.text(record, 0, 4));
        Fields.text(record, 0, 4, "A");
        assertEquals("A   ", Fields.text(record, 0, 4));
    }

    @Test
    void zonedRejectsWhatIsNotDigits() {
        byte[] record = "12 4".getBytes();
        assertFalse(Fields.isZoned(record, 0, 4, true));
        assertFalse(Fields.isZoned("123{".getBytes(), 0, 4, false));
        assertThrows(IllegalArgumentException.class, () -> Fields.zoned(record, 0, 4, 0));
    }

    @Test
    void packedHoldsTwoDigitsAByteAndTheSignNibble() {
        byte[] record = new byte[3];
        Fields.packed(record, 0, 5, 2, true, new BigDecimal("-123.45"));
        assertArrayEquals(new byte[] {0x12, 0x34, 0x5D}, record);
        assertEquals(new BigDecimal("-123.45"), Fields.packed(record, 0, 5, 2));
        Fields.packed(record, 0, 4, 0, false, new BigDecimal("98765"));
        assertArrayEquals(new byte[] {0x08, 0x76, 0x5F}, record);
        assertEquals(new BigDecimal("8765"), Fields.packed(record, 0, 4, 0));
    }
}
