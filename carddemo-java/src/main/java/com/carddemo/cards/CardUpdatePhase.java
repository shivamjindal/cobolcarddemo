package com.carddemo.cards;

/** CCUP-CHANGE-ACTION: where COCRDUPC is in fetch, edit, confirm and save. */
public enum CardUpdatePhase {
    NOT_FETCHED(""), SHOWN("S"), INVALID("E"), CONFIRM("N"), SAVED("C"), LOCK_FAILED("L"), SAVE_FAILED("F");

    public final String code;

    CardUpdatePhase(String code) {
        this.code = code;
    }
}
