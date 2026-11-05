package com.carddemo.db2;

/**
 * {@code CALL 'DSNTIAC' USING DFHEIBLK, DFHCOMMAREA, SQLCA, message, lrecl}: the SQLCA formatted as DSNTIAR
 * does, into the message area's lines. Only the first message (DSNT404I for a warning, DSNT408I for an error)
 * is written, with the Db2 text of the SQLCODEs these programs can meet; that is what reaches the screen.
 */
public final class Dsntiac {

    private static final int CONTINUATION_INDENT = 10;

    private Dsntiac() {}

    /**
     * The message area after the call: {@code messageLength / lrecl} lines of {@code lrecl} characters. DSNTIAR
     * breaks lines between words and indents continuation lines ten columns.
     */
    public static String format(Sqlca sqlca, int messageLength, int lrecl) {
        int lines = lrecl > 0 ? messageLength / lrecl : 0;
        char[] area = " ".repeat(lines * lrecl).toCharArray();
        int code = sqlca.sqlcode();
        String text = " %s SQLCODE = %d, %s  %s".formatted(code > 0 ? "DSNT404I" : "DSNT408I", code,
                code == Sqlca.NOT_FOUND ? "NOT FOUND:" : code > 0 ? "WARNING:" : "ERROR:", text(code));
        int p = 0;
        for (int line = 0; line < lines && p < text.length(); line++) {
            int pos = 0;
            if (line > 0) {
                pos = CONTINUATION_INDENT;
                while (p < text.length() && text.charAt(p) == ' ') {
                    p++;
                }
            }
            int room = lrecl - pos;
            int take = text.length() - p;
            if (take > room) {
                take = room;
                while (take > 0 && text.charAt(p + take) != ' ') {
                    take--;
                }
                if (take == 0) {
                    take = room;
                }
            }
            text.getChars(p, p + take, area, line * lrecl + pos);
            p += take;
        }
        return new String(area);
    }

    private static String text(int code) {
        return switch (code) {
            case 100 -> "ROW NOT FOUND FOR FETCH, UPDATE OR DELETE, OR THE RESULT OF A QUERY IS AN EMPTY TABLE";
            case -803 -> "AN INSERTED OR UPDATED VALUE IS INVALID BECAUSE THE INDEX IN INDEX SPACE "
                    + "CONSTRAINS COLUMNS OF THE TABLE SO NO TWO ROWS CAN CONTAIN DUPLICATE VALUES IN THOSE COLUMNS";
            case -532 -> "THE RELATIONSHIP RESTRICTS THE DELETION OF ROW";
            case -530 -> "THE INSERT OR UPDATE VALUE OF FOREIGN KEY IS INVALID";
            case -911 -> "THE CURRENT UNIT OF WORK HAS BEEN ROLLED BACK DUE TO DEADLOCK OR TIMEOUT";
            case -501 -> "THE CURSOR IDENTIFIED IN A FETCH OR CLOSE STATEMENT IS NOT OPEN";
            case -502 -> "THE CURSOR IDENTIFIED IN AN OPEN STATEMENT IS ALREADY OPEN";
            default -> "MESSAGE TEXT NOT REPRODUCED BY THE HARNESS";
        };
    }
}
