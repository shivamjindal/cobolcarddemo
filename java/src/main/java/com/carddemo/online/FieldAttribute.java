package com.carddemo.online;

/** DFHBMSCA attribute bytes a program moves into {@code xxxA}, by the two bits the terminal acts on. */
public enum FieldAttribute {
    /** DFHBMFSE: unprotected, MDT on. */
    BMFSE(false, true),
    /** DFHBMPRF: protected, MDT on. */
    BMPRF(true, true),
    /** DFHBMASB: autoskip and bright, MDT off. */
    BMASB(true, false);

    private final boolean protect;
    private final boolean mdt;

    FieldAttribute(boolean protect, boolean mdt) {
        this.protect = protect;
        this.mdt = mdt;
    }

    boolean protect() {
        return protect;
    }

    boolean mdt() {
        return mdt;
    }
}
