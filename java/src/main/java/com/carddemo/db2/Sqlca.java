package com.carddemo.db2;

/**
 * What a statement leaves in the SQLCA: SQLCODE, SQLSTATE, the SQLERRM message tokens (at most 70 characters,
 * the database's message for an error) and SQLERRD(3), the rows fetched or changed.
 */
public record Sqlca(int sqlcode, String sqlstate, String sqlerrmc, int rows) {

    public static final int NOT_FOUND = 100;

    static final int SQLERRMC_LENGTH = 70;

    public Sqlca {
        if (sqlerrmc.length() > SQLERRMC_LENGTH) {
            sqlerrmc = sqlerrmc.substring(0, SQLERRMC_LENGTH);
        }
    }

    static Sqlca ok(int rows) {
        return new Sqlca(0, "00000", "", rows);
    }

    static Sqlca notFound() {
        return new Sqlca(NOT_FOUND, "02000", "", 0);
    }

    /** SQLERRML. */
    public int sqlerrml() {
        return sqlerrmc.length();
    }

    /** {@code MOVE SQLCODE TO WS-DISP-SQLCODE}, the {@code PIC ----9} every program of the extension shows. */
    public String sqlcodeEdited() {
        String digits = Integer.toString(Math.abs(sqlcode) % 10000);
        String signed = sqlcode < 0 ? "-" + digits : digits;
        return " ".repeat(5 - signed.length()) + signed;
    }
}
