package com.carddemo.report;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportDatesTest {

    @Test
    void novemberRunsFromTheFirstToTheThirtieth() {
        ReportDates.Range range = ReportDates.monthly(LocalDate.of(2026, 11, 5));
        assertEquals("Monthly", range.name());
        assertEquals("2026-11-01", range.start());
        assertEquals("2026-11-30", range.end());
    }

    @Test
    void decemberStaysInTheSameYear() {
        ReportDates.Range range = ReportDates.monthly(LocalDate.of(2026, 12, 31));
        assertEquals("2026-12-01", range.start());
        assertEquals("2026-12-31", range.end());
    }

    @Test
    void januaryEndsOnTheThirtyFirst() {
        assertEquals("2026-01-31", ReportDates.monthly(LocalDate.of(2026, 1, 1)).end());
    }

    @Test
    void aLeapFebruaryEndsOnTheTwentyNinth() {
        ReportDates.Range range = ReportDates.monthly(LocalDate.of(2024, 2, 29));
        assertEquals("2024-02-01", range.start());
        assertEquals("2024-02-29", range.end());
    }

    @Test
    void aCommonFebruaryEndsOnTheTwentyEighth() {
        assertEquals("2026-02-28", ReportDates.monthly(LocalDate.of(2026, 2, 15)).end());
    }

    @Test
    void yearlyIsTheFirstThroughTheLastOfTheYear() {
        ReportDates.Range range = ReportDates.yearly(LocalDate.of(2026, 11, 5));
        assertEquals("Yearly", range.name());
        assertEquals("2026-01-01", range.start());
        assertEquals("2026-12-31", range.end());
    }
}
