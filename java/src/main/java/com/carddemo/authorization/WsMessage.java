package com.carddemo.authorization;

import java.util.Arrays;

/** WS-MESSAGE, PIC X(80): a MOVE replaces it, a STRING writes over its start and keeps the rest. */
final class WsMessage {

    private final char[] text = " ".repeat(80).toCharArray();

    /** {@code MOVE value TO WS-MESSAGE}. */
    void replace(String value) {
        Arrays.fill(text, ' ');
        overlay(value);
    }

    /** {@code STRING ... DELIMITED BY SIZE INTO WS-MESSAGE}. */
    void overlay(String value) {
        value.getChars(0, Math.min(value.length(), text.length), text, 0);
    }

    @Override
    public String toString() {
        return new String(text).stripTrailing();
    }
}
