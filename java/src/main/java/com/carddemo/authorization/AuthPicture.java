package com.carddemo.authorization;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** The edit pictures and date layouts COPAUS0C and COPAUS1C move onto their maps. */
final class AuthPicture {

    private AuthPicture() {}

    /**
     * {@code PIC -zzzzzzz9.99} for {@code integerDigits} 8 and {@code PIC -zzzz9.99} for 5: a fixed sign position,
     * blank unless negative, then the integer part zero-suppressed down to its last digit. The MOVE drops integer
     * digits the picture has no room for.
     */
    static String signed(BigDecimal amount, int integerDigits) {
        BigInteger cents = amount.setScale(2, RoundingMode.DOWN).unscaledValue().abs();
        BigInteger units = cents.divide(BigInteger.valueOf(100)).mod(BigInteger.TEN.pow(integerDigits));
        String integer = String.format("%" + integerDigits + "s", units.toString());
        return (amount.signum() < 0 ? "-" : " ") + integer + "." + String.format("%02d", cents.mod(BigInteger.valueOf(100)).intValue());
    }

    /** {@code WS-CURDATE-MM-DD-YY} from a {@code YYMMDD} authorization date. */
    static String monthDayYear(String yymmdd) {
        String date = pad(yymmdd, 6);
        return date.substring(2, 4) + "/" + date.substring(4, 6) + "/" + date.substring(0, 2);
    }

    /** {@code WS-AUTH-TIME} {@code HH:MM:SS} from a {@code HHMMSS} authorization time. */
    static String hourMinuteSecond(String hhmmss) {
        String time = pad(hhmmss, 6);
        return time.substring(0, 2) + ":" + time.substring(2, 4) + ":" + time.substring(4, 6);
    }

    /** A fixed-width text field as its COBOL storage holds it: padded with spaces. */
    static String pad(String text, int length) {
        String value = text == null ? "" : text;
        return value.length() >= length ? value.substring(0, length) : value + " ".repeat(length - value.length());
    }
}
