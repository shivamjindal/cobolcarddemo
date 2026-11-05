package com.carddemo.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pictures COTRN00C and COTRN01C move onto the map: {@code +99999999.99}, category {@code 9(04)},
 * merchant id {@code 9(09)}, and the list date taken from TRAN-ORIG-TS the way CSDAT01Y lays it out.
 */
final class TransactionPicture {

    private TransactionPicture() {}

    /** {@code WS-TRAN-AMT PIC +99999999.99}. */
    static String amount(BigDecimal value) {
        BigDecimal scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        String digits = scaled.abs().unscaledValue().toString();
        digits = "0".repeat(Math.max(0, 10 - digits.length())) + digits;
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }
        return (scaled.signum() < 0 ? "-" : "+") + digits.substring(0, 8) + "." + digits.substring(8);
    }

    /** {@code TRAN-CAT-CD PIC 9(04)}. */
    static String category(int catCd) {
        return "%04d".formatted(catCd);
    }

    /** {@code TRAN-MERCHANT-ID PIC 9(09)}. */
    static String merchantId(int merchantId) {
        return "%09d".formatted(merchantId);
    }

    /**
     * {@code MOVE TRAN-ORIG-TS TO WS-TIMESTAMP}, then {@code YYYY(3:2)}, {@code MM} and {@code DD}
     * into {@code WS-CURDATE-MM-DD-YY}.
     */
    static String listDate(String origTs) {
        return origTs.substring(5, 7) + "/" + origTs.substring(8, 10) + "/" + origTs.substring(2, 4);
    }
}
