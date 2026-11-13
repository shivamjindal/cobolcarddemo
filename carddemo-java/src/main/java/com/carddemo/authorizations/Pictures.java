package com.carddemo.authorizations;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The PICTUREs and date and time layouts the authorization programs show values with. */
final class Pictures {

    private Pictures() {
    }

    /** {@code -z...z9.99} in {@code width} characters: a minus or a space, then the zero-suppressed amount. */
    static String minusAmount(BigDecimal value, int width) {
        BigDecimal cents = value.setScale(2, RoundingMode.DOWN);
        String digits = cents.abs().toPlainString();
        return (cents.signum() < 0 ? "-" : " ") + " ".repeat(Math.max(0, width - 1 - digits.length())) + digits;
    }

    /** PA-AUTH-ORIG-DATE YYMMDD as MM/DD/YY. */
    static String date(String yymmdd) {
        String d = pad(yymmdd, 6);
        return d.substring(2, 4) + "/" + d.substring(4, 6) + "/" + d.substring(0, 2);
    }

    /** PA-AUTH-ORIG-TIME HHMMSS as HH:MM:SS. */
    static String time(String hhmmss) {
        String t = pad(hhmmss, 6);
        return t.substring(0, 2) + ":" + t.substring(2, 4) + ":" + t.substring(4, 6);
    }

    static String pad(String value, int width) {
        return value.length() >= width ? value.substring(0, width) : value + " ".repeat(width - value.length());
    }
}
