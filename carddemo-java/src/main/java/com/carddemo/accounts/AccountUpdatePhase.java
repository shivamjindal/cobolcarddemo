package com.carddemo.accounts;

import java.util.Arrays;

/** ACUP-CHANGE-ACTION: where COACTUPC is between search, edit, confirmation and save. */
public enum AccountUpdatePhase {
    NOT_FETCHED(""), SHOWN("S"), INVALID("E"), CONFIRM("N"), SAVED("C"), LOCK_FAILED("L"), SAVE_FAILED("F");

    private final String code;

    AccountUpdatePhase(String code) {
        this.code = code;
    }

    /** The ACUP-CHANGE-ACTION letter; blank before a record is fetched. */
    public String code() {
        return code;
    }

    public static AccountUpdatePhase of(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code.strip())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No phase for ACUP-CHANGE-ACTION '" + code + "'"));
    }

    /** A save attempt has ended; the next key other than PF3 starts a new search. */
    boolean ended() {
        return this == SAVED || this == LOCK_FAILED || this == SAVE_FAILED;
    }
}
