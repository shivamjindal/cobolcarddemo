package com.carddemo.account;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What GnuCOBOL 3.1.2 under the harness flags gives for these reads, taken from small test programs. */
class IntrinsicsTest {

    @Test
    void zonedCountsEachByteAsItsCodeMinusZero() {
        assertEquals(-6, Intrinsics.zoned(Intrinsics.field("1", 2)));
        assertEquals(10, Intrinsics.zoned("0:"));
        assertEquals(1900, Intrinsics.zoned("19/:"));
        assertEquals(-528, Intrinsics.zoned(Intrinsics.field(null, 2)));
        assertTrue(Intrinsics.zoned(Intrinsics.field("", 11)) != 0);
    }

    @Test
    void testNumvalTakesTrailingSignsInCapitalsOnly() {
        assertTrue(Intrinsics.testNumval("100CR"));
        assertTrue(Intrinsics.testNumval(" - 1"));
        assertFalse(Intrinsics.testNumval("100cr"));
        assertFalse(Intrinsics.testNumval("$5"));
        assertFalse(Intrinsics.testNumval("."));
    }

    @Test
    void testNumvalCTakesTheDollarAndCommasAnywhereInTheDigits() {
        assertTrue(Intrinsics.testNumvalC("$-5"));
        assertTrue(Intrinsics.testNumvalC("- $ 5"));
        assertTrue(Intrinsics.testNumvalC("1,0,0"));
        assertFalse(Intrinsics.testNumvalC("1db"));
        assertFalse(Intrinsics.testNumvalC(",100"));
        assertFalse(Intrinsics.testNumvalC("1 000"));
        assertEquals(new BigDecimal("-1000.50"), Intrinsics.numvalC("$1,000.50CR"));
    }
}
