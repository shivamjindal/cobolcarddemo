package com.carddemo.account;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * PICOUT '+ZZZ,ZZZ,ZZZ.99', the edit both account screens give an S9(10)V99 amount. The sign is a
 * fixed insertion, and the MOVE drops the tenth integer digit.
 */
final class AmountPicture {

    private static final String INTEGER_PART = "ZZZ,ZZZ,ZZZ";
    private static final BigInteger NINE_DIGITS = BigInteger.TEN.pow(9);

    private AmountPicture() {}

    static String edit(BigDecimal amount) {
        BigInteger cents = amount.setScale(2, RoundingMode.DOWN).unscaledValue().abs();
        String digits = String.format("%09d", cents.divide(BigInteger.valueOf(100)).mod(NINE_DIGITS).longValue());
        StringBuilder edited = new StringBuilder(amount.signum() < 0 ? "-" : "+");
        boolean suppressing = true;
        int next = 0;
        for (char symbol : INTEGER_PART.toCharArray()) {
            if (symbol == ',') {
                edited.append(suppressing ? ' ' : ',');
                continue;
            }
            char digit = digits.charAt(next++);
            suppressing &= digit == '0';
            edited.append(suppressing ? ' ' : digit);
        }
        return edited.append('.').append(String.format("%02d", cents.mod(BigInteger.valueOf(100)).intValue())).toString();
    }
}
