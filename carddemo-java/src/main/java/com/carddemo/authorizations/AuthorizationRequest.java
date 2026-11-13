package com.carddemo.authorizations;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

/** PENDING-AUTH-REQUEST (CCPAURQY): what the point of sale asked for, as PAUTDTL1 keeps it. */
public record AuthorizationRequest(String date, String time, String cardNumber, String authType,
                                   String cardExpiryDate, String messageType, String messageSource,
                                   int processingCode, BigDecimal amount, String merchantCategoryCode,
                                   String acquirerCountryCode, int posEntryMode, String merchantId,
                                   String merchantName, String merchantCity, String merchantState,
                                   String merchantZip, String transactionId) implements Serializable {

    /**
     * 2100-EXTRACT-REQUEST-MSG: UNSTRING DELIMITED BY ',' into the CCPAURQY fields, so each value is cut to its
     * PIC width and a missing one is blank. The amount is not cut: the legacy WS-TRANSACTION-AMT-AN is one
     * character shorter than a signed PIC +9(10).99 amount.
     */
    static AuthorizationRequest parse(String csv) {
        String[] values = Arrays.copyOf(csv.split(",", -1), 18);
        return new AuthorizationRequest(text(values[0], 6), text(values[1], 6), text(values[2], 16),
                text(values[3], 4), text(values[4], 4), text(values[5], 6), text(values[6], 6), number(values[7], 6),
                amount(values[8]), text(values[9], 4), text(values[10], 3), number(values[11], 2),
                text(values[12], 15), text(values[13], 22), text(values[14], 13), text(values[15], 2),
                text(values[16], 9), text(values[17], 15));
    }

    private static String text(String value, int width) {
        String v = value == null ? "" : value;
        return v.substring(0, Math.min(width, v.length())).stripTrailing();
    }

    /** A MOVE to PIC 9(n) keeps the rightmost n digits. */
    private static int number(String value, int digits) {
        String v = value == null ? "" : value.strip();
        if (!v.matches("\\d+")) {
            return 0;
        }
        return Integer.parseInt(v.substring(Math.max(0, v.length() - digits)));
    }

    /** NUMVAL into PIC +9(10).99: an unreadable amount is zero, extra decimals are truncated. */
    private static BigDecimal amount(String value) {
        try {
            return new BigDecimal(value == null ? "" : value.strip()).setScale(2, RoundingMode.DOWN);
        } catch (NumberFormatException e) {
            return new BigDecimal("0.00");
        }
    }
}
