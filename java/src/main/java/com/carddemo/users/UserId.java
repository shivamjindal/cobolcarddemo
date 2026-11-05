package com.carddemo.users;

import java.io.Serializable;

/** SEC-USR-ID PIC X(08) without its trailing blanks, in the case it was given. */
public record UserId(String value) implements Serializable {

    public static final int LENGTH = 8;

    public UserId {
        if (value.isBlank() || value.length() > LENGTH || !value.equals(value.stripTrailing())) {
            throw new IllegalArgumentException("user id '" + value + "'");
        }
    }

    public static UserId of(String value) {
        return new UserId(value);
    }

    /** {@code STRING SEC-USR-ID DELIMITED BY SPACE}: the characters before the first blank. */
    public String delimitedBySpace() {
        int blank = value.indexOf(' ');
        return blank < 0 ? value : value.substring(0, blank);
    }

    @Override
    public String toString() {
        return value;
    }
}
