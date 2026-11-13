package com.carddemo.authorizations;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * PA-AUTHORIZATION-KEY: COPAUA0C stores 99999 - YYDDD and 999999999 - HHMMSSmmm of the moment it decided, so under
 * one summary the newest authorization has the lowest key.
 */
public record AuthorizationKey(int date9c, int time9c) implements Serializable {

    static AuthorizationKey at(LocalDateTime moment) {
        int yyddd = moment.getYear() % 100 * 1000 + moment.getDayOfYear();
        int hhmmss = (moment.getHour() * 100 + moment.getMinute()) * 100 + moment.getSecond();
        return new AuthorizationKey(99999 - yyddd, 999999999 - (hhmmss * 1000 + moment.getNano() / 1_000_000));
    }

    /** The time of day the key was taken, to the millisecond. */
    LocalTime time() {
        int hhmmssmmm = 999999999 - time9c;
        return LocalTime.of(hhmmssmmm / 10_000_000, hhmmssmmm / 100_000 % 100, hhmmssmmm / 1000 % 100,
                hhmmssmmm % 1000 * 1_000_000);
    }

    /** YYDDD of the day the key was taken. */
    int yyddd() {
        return 99999 - date9c;
    }
}
