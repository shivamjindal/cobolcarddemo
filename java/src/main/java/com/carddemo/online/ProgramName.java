package com.carddemo.online;

import java.io.Serializable;
import java.util.regex.Pattern;

/** A CICS program name. */
public record ProgramName(String value) implements Serializable {

    private static final Pattern NAME = Pattern.compile("[A-Z@#$][A-Z0-9@#$]{0,7}");

    public ProgramName {
        if (!NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("program name " + value);
        }
    }

    public static ProgramName of(String value) {
        return new ProgramName(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
