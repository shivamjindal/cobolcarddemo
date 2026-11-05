package com.carddemo.online;

import java.io.Serializable;
import java.util.regex.Pattern;

/** A CICS transaction id. */
public record TransId(String value) implements Serializable {

    private static final Pattern ID = Pattern.compile("[A-Z0-9@#$]{1,4}");

    public TransId {
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException("transaction id " + value);
        }
    }

    public static TransId of(String value) {
        return new TransId(value);
    }
}
