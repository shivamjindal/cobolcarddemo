package com.carddemo.authorization;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.carddemo.LegacyDefect;

/**
 * CCPAURQY, the PENDING-AUTH-REQUEST storage COPAUA0C unstrings each request line into: eighteen comma-separated
 * fields, held as their COBOL storage text, LOW-VALUES until a request first reaches them.
 */
final class AuthorizationRequest {

    static final int AUTH_DATE = 0;
    static final int AUTH_TIME = 1;
    static final int CARD_NUM = 2;
    static final int AUTH_TYPE = 3;
    static final int CARD_EXPIRY_DATE = 4;
    static final int MESSAGE_TYPE = 5;
    static final int MESSAGE_SOURCE = 6;
    static final int PROCESSING_CODE = 7;
    static final int TRANSACTION_AMT_AN = 8;
    static final int MERCHANT_CATAGORY_CODE = 9;
    static final int ACQR_COUNTRY_CODE = 10;
    static final int POS_ENTRY_MODE = 11;
    static final int MERCHANT_ID = 12;
    static final int MERCHANT_NAME = 13;
    static final int MERCHANT_CITY = 14;
    static final int MERCHANT_STATE = 15;
    static final int MERCHANT_ZIP = 16;
    static final int TRANSACTION_ID = 17;

    private static final int[] WIDTHS = {6, 6, 16, 4, 4, 6, 6, 6, 13, 4, 3, 2, 15, 22, 13, 2, 9, 15};
    private static final int GET_BUFFER = 500;
    private static final BigDecimal TEN_DIGITS = BigDecimal.TEN.pow(10);

    private final String[] fields = Arrays.stream(WIDTHS).mapToObj("\0"::repeat).toArray(String[]::new);
    private BigDecimal transactionAmt = BigDecimal.ZERO.setScale(2);

    /**
     * 2100-EXTRACT-REQUEST-MSG: {@code UNSTRING ... DELIMITED BY ','} into the fields in order, then
     * {@code COMPUTE PA-RQ-TRANSACTION-AMT = FUNCTION NUMVAL(WS-TRANSACTION-AMT-AN)}.
     */
    @LegacyDefect(id = "COPAUA0C-unstring-keeps-old-fields",
            goldens = {"auth-malformed-request"},
            behavior = "a request with fewer than eighteen fields leaves the rest as the previous request, or "
                    + "low-values in a fresh task, so a line without commas is decided on a card of low-values and "
                    + "its reply carries low-values for the card, transaction id and auth id",
            fix = "INITIALIZE the request before the UNSTRING and reject a request without all eighteen fields")
    void unstring(byte[] data) {
        String text = new String(data, 0, Math.min(data.length, GET_BUFFER), StandardCharsets.ISO_8859_1);
        String[] parts = text.split(",", -1);
        for (int i = 0; i < Math.min(parts.length, WIDTHS.length); i++) {
            fields[i] = i == PROCESSING_CODE || i == POS_ENTRY_MODE
                    ? rightJustified(parts[i], WIDTHS[i])
                    : AuthPicture.pad(parts[i], WIDTHS[i]);
        }
        transactionAmt = numval(fields[TRANSACTION_AMT_AN]).setScale(2, RoundingMode.DOWN).remainder(TEN_DIGITS);
    }

    /** The field as its storage holds it. */
    String text(int field) {
        return fields[field];
    }

    /** The field as a segment's text field holds it: without trailing spaces, or all LOW-VALUES. */
    String segmentText(int field) {
        return segmentText(fields[field]);
    }

    static String segmentText(String storage) {
        if (storage.chars().allMatch(c -> c == 0)) {
            return storage;
        }
        return storage.replace('\0', ' ').stripTrailing();
    }

    /** A {@code PIC 9(n)} field's value; a byte that is not a digit counts as zero. */
    int number(int field) {
        int value = 0;
        for (char c : fields[field].toCharArray()) {
            value = value * 10 + (Character.isDigit(c) ? c - '0' : 0);
        }
        return value;
    }

    /** {@code PA-RQ-TRANSACTION-AMT}, {@code PIC +9(10).99}. */
    BigDecimal transactionAmt() {
        return transactionAmt;
    }

    /** An UNSTRING into a numeric DISPLAY receiver moves the digits right-justified, zero-filled on the left. */
    private static String rightJustified(String part, int width) {
        return part.length() >= width ? part.substring(part.length() - width)
                : "0".repeat(width - part.length()) + part;
    }

    /** {@code FUNCTION NUMVAL}: leading and trailing blanks ignored, a sign before or after, zero when invalid. */
    static BigDecimal numval(String argument) {
        String text = argument.replace('\0', ' ').strip();
        boolean negative = false;
        if (text.endsWith("CR") || text.endsWith("DB")) {
            negative = true;
            text = text.substring(0, text.length() - 2).strip();
        } else if (text.endsWith("-") || text.endsWith("+")) {
            negative = text.endsWith("-");
            text = text.substring(0, text.length() - 1).strip();
        } else if (text.startsWith("-") || text.startsWith("+")) {
            negative = text.startsWith("-");
            text = text.substring(1).strip();
        }
        if (!text.matches("\\d*\\.?\\d*") || text.replace(".", "").isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = new BigDecimal(text.startsWith(".") ? "0" + text : text.endsWith(".") ? text + "0" : text);
        return negative ? value.negate() : value;
    }
}
