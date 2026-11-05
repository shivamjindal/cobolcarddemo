package com.carddemo.report;

import java.time.LocalDate;

/** The monthly and yearly ranges CORPT00C derives from FUNCTION CURRENT-DATE. */
final class ReportDates {

    /** A report name and the two PIC X(10) dates the job cards carry. */
    record Range(String name, String start, String end) {}

    private ReportDates() {}

    /**
     * The first of this month through the day before the first of next month. That is day 1, add one
     * month, then INTEGER-OF-DATE minus one.
     */
    static Range monthly(LocalDate today) {
        LocalDate start = today.withDayOfMonth(1);
        LocalDate end = start.plusMonths(1).minusDays(1);
        return new Range("Monthly", start.toString(), end.toString());
    }

    static Range yearly(LocalDate today) {
        String year = "%04d".formatted(today.getYear());
        return new Range("Yearly", year + "-01-01", year + "-12-31");
    }
}
