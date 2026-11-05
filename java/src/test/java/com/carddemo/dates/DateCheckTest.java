package com.carddemo.dates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSUTLDTC's answers under the CEEDAYS stand-in of the harness, which the goldens were recorded with. */
class DateCheckTest {

    private static final String ISO = "YYYY-MM-DD";

    @Test
    void aRealDateIsValidWithSeverityZero() {
        assertEquals(new DateCheck.Feedback(0, 0, "Date is valid"), DateCheck.check("2026-11-05", ISO));
        assertTrue(DateCheck.check("2024-02-29", ISO).valid());
        assertTrue(DateCheck.check("2000-02-29", ISO).valid());
        assertTrue(DateCheck.check("11/05/2026", "MM/DD/YYYY").valid());
    }

    @Test
    void separatorsAreSkippedNotCompared() {
        assertTrue(DateCheck.check("2026x11y05", ISO).valid());
        assertTrue(DateCheck.check("20261105", "YYYYMMDD  ").valid());
    }

    @Test
    void bothArgumentsAreCutToPicX10() {
        assertTrue(DateCheck.check("2026-11-05", "YYYY-MM-DDX").valid());
        assertTrue(DateCheck.check("2026-11-05T10:00", ISO).valid());
    }

    @Test
    void aBlankPictureOrAnUnknownCapitalIsABadPicture() {
        assertEquals(new DateCheck.Feedback(3, 2518, "Bad Pic String"), DateCheck.check("2026-11-05", "          "));
        assertEquals(new DateCheck.Feedback(3, 2518, "Bad Pic String"), DateCheck.check("2026-11-05", "YYYY-MM-D"));
        assertEquals(new DateCheck.Feedback(3, 2518, "Bad Pic String"), DateCheck.check("2026-11-05", "YYYY-MM-DX"));
    }

    @Test
    void aPictureWithoutYearMonthAndDayIsInsufficient() {
        assertEquals(new DateCheck.Feedback(3, 2507, "Insufficient"), DateCheck.check("2026-11-05", "YYYY-MM   "));
        assertEquals(new DateCheck.Feedback(3, 2507, "Insufficient"), DateCheck.check("2026-11-05", "yyyy-mm-dd"));
    }

    @Test
    void aNonDigitInASlotIsNonNumericBeforeAnyLaterCheck() {
        assertEquals(new DateCheck.Feedback(3, 2520, "Nonnumeric data"), DateCheck.check("2026-1X-05", ISO));
        assertEquals(new DateCheck.Feedback(3, 2520, "Nonnumeric data"), DateCheck.check("abcd-11-05", "YYYY-MM-DX"),
                "the year slot is read before the bad capital");
    }

    @Test
    void aShortDateIsPaddedWithBlanksSoItsMissingSlotIsNonNumeric() {
        assertEquals(new DateCheck.Feedback(3, 2520, "Nonnumeric data"), DateCheck.check("2026-11", ISO));
        assertEquals(new DateCheck.Feedback(3, 2520, "Nonnumeric data"), DateCheck.check("", ISO));
    }

    @Test
    void theMonthIsCheckedBeforeTheDay() {
        assertEquals(new DateCheck.Feedback(3, 2517, "Invalid month"), DateCheck.check("2026-13-05", ISO));
        assertEquals(new DateCheck.Feedback(3, 2517, "Invalid month"), DateCheck.check("2026-00-05", ISO));
        assertEquals(new DateCheck.Feedback(3, 2517, "Invalid month"), DateCheck.check("2026-13-32", ISO));
    }

    @Test
    void aDayOutsideTheMonthIsADateValueError() {
        assertEquals(new DateCheck.Feedback(3, 2508, "Datevalue error"), DateCheck.check("2026-02-29", ISO));
        assertEquals(new DateCheck.Feedback(3, 2508, "Datevalue error"), DateCheck.check("1900-02-29", ISO));
        assertEquals(new DateCheck.Feedback(3, 2508, "Datevalue error"), DateCheck.check("2026-04-31", ISO));
        assertEquals(new DateCheck.Feedback(3, 2508, "Datevalue error"), DateCheck.check("2026-11-00", ISO));
    }

    @Test
    void aDateBeforeTheFifteenthOfOctober1582IsOutOfRangeAfterTheDayCheck() {
        assertEquals(new DateCheck.Feedback(3, DateCheck.UNSUPPORTED_RANGE, "Unsupp. Range"), DateCheck.check("1582-10-14", ISO));
        assertEquals(new DateCheck.Feedback(3, 2513, "Unsupp. Range"), DateCheck.check("1500-01-01", ISO));
        assertEquals(new DateCheck.Feedback(3, 2513, "Unsupp. Range"), DateCheck.check("0000-01-01", ISO));
        assertFalse(DateCheck.check("1582-10-14", ISO).valid());
        assertTrue(DateCheck.check("1582-10-15", ISO).valid());
        assertEquals(new DateCheck.Feedback(3, 2508, "Datevalue error"), DateCheck.check("1500-02-30", ISO));
    }
}
