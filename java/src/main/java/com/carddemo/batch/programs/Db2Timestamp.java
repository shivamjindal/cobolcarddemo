package com.carddemo.batch.programs;

import java.time.Clock;
import java.time.ZonedDateTime;

/** Z-GET-DB2-FORMAT-TIMESTAMP, shared by the posting and interest programs. */
final class Db2Timestamp {

    private Db2Timestamp() {}

    /**
     * {@code YYYY-MM-DD-HH.MM.SS.hh0000}. {@code hh} is the hundredths from CURRENT-DATE. The clock's zone is
     * dropped and the last four characters are the literal {@code 0000}, not the timezone.
     */
    static String format(Clock clock) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        int hundredths = now.getNano() / 10_000_000;
        return "%04d-%02d-%02d-%02d.%02d.%02d.%02d0000".formatted(now.getYear(), now.getMonthValue(),
                now.getDayOfMonth(), now.getHour(), now.getMinute(), now.getSecond(), hundredths);
    }
}
