package com.carddemo.trantypes;

import java.io.Serializable;

/**
 * COTRTUPC's TTUP-CHANGE-ACTION plus the fetched row and the description waiting to be saved.
 * The letter is the COMMAREA value.
 */
public record TranTypeForm(Phase phase, String code, String description, String edited) implements Serializable {

    public enum Phase {
        NOT_FETCHED(""),
        NOT_FOUND("X"),
        SHOWN("S"),
        CREATE("R"),
        CONFIRM("N"),
        CONFIRM_DELETE("9"),
        DELETE_DONE("7"),
        DELETE_FAILED("6"),
        INVALID_EDIT("E"),
        SAVED("C"),
        BACKED_OUT("B");

        private final String code;

        Phase(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Phase of(String code) {
            String letter = code == null ? "" : code.strip();
            for (Phase phase : values()) {
                if (phase.code.equals(letter)) {
                    return phase;
                }
            }
            throw new IllegalArgumentException("No phase for TTUP-CHANGE-ACTION '" + code + "'");
        }

        /** A finished save or delete. The next key other than PF3 starts a new search. */
        boolean ended() {
            return this == SAVED || this == DELETE_DONE || this == DELETE_FAILED;
        }
    }

    static TranTypeForm fresh() {
        return new TranTypeForm(Phase.NOT_FETCHED, "", "", "");
    }

    TranTypeForm phase(Phase phase) {
        return new TranTypeForm(phase, code, description, edited);
    }

    TranTypeForm editing(String edited) {
        return new TranTypeForm(phase, code, description, edited);
    }
}
