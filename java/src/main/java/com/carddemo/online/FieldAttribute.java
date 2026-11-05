package com.carddemo.online;

/** DFHBMSCA attribute bytes a program moves into {@code xxxA}, by the two bits the terminal acts on. */
public enum FieldAttribute {
    /** DFHBMFSE: unprotected, MDT on. */
    BMFSE(false, true),
    /** DFHBMUNP: unprotected, MDT off. */
    BMUNP(false, false),
    /** DFHBMPRO: protected, MDT off. */
    BMPRO(true, false),
    /** DFHBMPRF: protected, MDT on. */
    BMPRF(true, true),
    /** DFHBMASB: autoskip and bright, MDT off. */
    BMASB(true, false),
    /** DFHBMASF: autoskip, MDT on. */
    BMASF(true, true),
    /**
     * DFHBMBRY. Bright is unprotected with MDT off, and those are the two bits the terminal keeps.
     */
    BMBRY(false, false),
    /** DFHBMDAR, X'4C': dark, and unprotected with MDT off. */
    BMDAR(false, false);

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
