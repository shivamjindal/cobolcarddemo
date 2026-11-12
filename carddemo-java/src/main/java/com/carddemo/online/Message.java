package com.carddemo.online;

import java.io.Serializable;

/** The text a program puts in ERRMSG, and the colour it would have on the terminal. */
public record Message(String text, Kind kind) implements Serializable {

    public enum Kind { INFO, ERROR, SUCCESS }

    public static final Message INVALID_KEY = error("Invalid key pressed. Please see below...");
    public static final Message THANK_YOU = info("Thank you for using CardDemo application...");

    public static Message info(String text) {
        return new Message(text, Kind.INFO);
    }

    public static Message error(String text) {
        return new Message(text, Kind.ERROR);
    }

    public static Message success(String text) {
        return new Message(text, Kind.SUCCESS);
    }
}
