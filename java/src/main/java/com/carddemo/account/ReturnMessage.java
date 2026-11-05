package com.carddemo.account;

/**
 * WS-RETURN-MSG X(75) of one task. Most paragraphs set it only {@code IF WS-RETURN-MSG-OFF}, so the
 * first message stays; file errors and a few others move into it unguarded.
 */
final class ReturnMessage {

    /** DFHRESP(NOTFND); its RESP2 is 0. */
    static final int NOTFND = 13;

    private static final int LENGTH = 75;

    private String text = "";

    /** WS-FILE-ERROR-MESSAGE as both account programs lay it out. */
    static String fileError(String operation, String file, int resp, int resp2) {
        return "File Error: " + pad(operation, 8) + " on " + pad(file, 9) + " returned RESP " + code(resp)
                + ",RESP2 " + code(resp2);
    }

    /** {@code MOVE WS-RESP-CD TO ERROR-RESP}: the S9(9) COMP in an X(10). */
    static String code(int resp) {
        return String.format("%09d ", Math.abs(resp));
    }

    boolean isOff() {
        return text.isBlank();
    }

    /** {@code IF WS-RETURN-MSG-OFF MOVE message TO WS-RETURN-MSG}. */
    void offer(String message) {
        if (isOff()) {
            replace(message);
        }
    }

    /** An unguarded {@code MOVE} or {@code STRING} into WS-RETURN-MSG. */
    void replace(String message) {
        text = message.length() > LENGTH ? message.substring(0, LENGTH) : message;
    }

    /** {@code SET WS-RETURN-MSG-OFF TO TRUE}. */
    void clear() {
        text = "";
    }

    String text() {
        return text;
    }

    private static String pad(String value, int length) {
        return String.format("%-" + length + "s", value);
    }
}
