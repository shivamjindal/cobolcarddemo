package com.carddemo.batch;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** FUNCTION CURRENT-DATE as the batch programs stamp it (Z-GET-DB2-FORMAT-TIMESTAMP): hundredths of a second. */
public final class CurrentDate {

    private CurrentDate() {
    }

    public static LocalDateTime timestamp(Clock clock) {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        return now.withNano(now.getNano() / 10_000_000 * 10_000_000);
    }
}
