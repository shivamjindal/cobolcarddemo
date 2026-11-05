package com.carddemo.online;

/** The ERASE and CURSOR options of {@code EXEC CICS SEND MAP}. */
public enum SendMode {
    ERASE_CURSOR(true, true), ERASE(true, false), CURSOR(false, true);

    private final boolean erase;
    private final boolean cursor;

    SendMode(boolean erase, boolean cursor) {
        this.erase = erase;
        this.cursor = cursor;
    }

    boolean erase() {
        return erase;
    }

    boolean cursor() {
        return cursor;
    }
}
