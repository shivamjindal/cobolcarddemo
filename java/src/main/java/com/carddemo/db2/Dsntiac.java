package com.carddemo.db2;

/**
 * {@code CALL 'DSNTIAC' USING DFHEIBLK, DFHCOMMAREA, SQLCA, message, lrecl}: the SQLCA formatted as DSNTIAR
 * does, into the message area's lines. Only the first message (DSNT404I for a warning, DSNT408I for an error)
 * is written, with the Db2 text of the SQLCODEs these programs can meet; that is what reaches the screen.
 */
public final class Dsntiac {

    private static final int CONTINUATION_INDENT = 10;
    private static final int MIN_LRECL = 72;
    private static final int MAX_LRECL = 240;
    private static final int MIN_MESSAGE_LENGTH = 240;

    /** The call's RETURN-CODE and the message area after it. */
    public record Formatted(int returnCode, String area) {}

    private Dsntiac() {}

    /**
     * Formats into {@code messageLength / lrecl} lines of {@code lrecl} characters at the start of {@code area};
     * the rest of the area is left as it was. DSNTIAR breaks lines between words and indents continuation lines
     * ten columns. An LRECL outside 72 to 240 is return code 8, a message length under 240 return code 12, and
     * either leaves the area untouched.
     */
    public static Formatted format(Sqlca sqlca, int messageLength, int lrecl, String area) {
        if (lrecl < MIN_LRECL || lrecl > MAX_LRECL) {
            return new Formatted(8, area);
        }
        if (messageLength < MIN_MESSAGE_LENGTH) {
            return new Formatted(12, area);
        }
        int lines = messageLength / lrecl;
        char[] formatted = " ".repeat(lines * lrecl).toCharArray();
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
            text.getChars(p, p + take, formatted, line * lrecl + pos);
            p += take;
        }
        return new Formatted(0, new String(formatted) + (area.length() > formatted.length ? area.substring(formatted.length) : ""));
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
