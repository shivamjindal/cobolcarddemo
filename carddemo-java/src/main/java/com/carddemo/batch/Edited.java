package com.carddemo.batch;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;

/** Numeric-edited and DISPLAY pictures the batch reports print amounts with. */
final class Edited {

    private Edited() {
    }

    /** {@code -ZZZ,ZZZ,ZZZ.ZZ}: a minus or a space, then the zero-suppressed amount; zero prints as spaces. */
    static String minusAmount(BigDecimal value) {
        return edited(value, value.signum() < 0 ? '-' : ' ');
    }

    /** {@code +ZZZ,ZZZ,ZZZ.ZZ}: a plus or a minus, then the zero-suppressed amount; zero prints as spaces. */
    static String signedAmount(BigDecimal value) {
        return edited(value, value.signum() < 0 ? '-' : '+');
    }

    /** DISPLAY of a {@code S9(9)V99} item: sign, nine integer digits, point, two decimals. */
    static String display(BigDecimal value) {
        BigDecimal v = value.setScale(2, RoundingMode.DOWN);
        return (v.signum() < 0 ? "-" : "+") + String.format(Locale.ROOT, "%012.2f", v.abs());
    }

    /** {@code Z(9).99-}: zero-suppressed integer, point, two decimals, then a minus or a space. */
    static String trailingMinus(BigDecimal value) {
        BigDecimal v = value.setScale(2, RoundingMode.DOWN);
        long units = v.abs().longValue() % 1_000_000_000L;
        return "%9s.%02d%s".formatted(units == 0 ? "" : units, cents(v), v.signum() < 0 ? "-" : " ");
    }

    /** {@code 9(9).99-}: zero-filled integer, point, two decimals, then a minus or a space. */
    static String zeroFilledTrailingMinus(BigDecimal value) {
        BigDecimal v = value.setScale(2, RoundingMode.DOWN);
        return "%09d.%02d%s".formatted(v.abs().longValue() % 1_000_000_000L, cents(v), v.signum() < 0 ? "-" : " ");
    }

    private static int cents(BigDecimal scale2) {
        return scale2.abs().unscaledValue().mod(BigInteger.valueOf(100)).intValue();
    }

    private static String edited(BigDecimal value, char sign) {
        BigDecimal v = value.setScale(2, RoundingMode.DOWN).abs();
        if (v.signum() == 0) {
            return " ".repeat(15);
        }
        long units = v.longValue();
        String integer = units == 0 ? "" : String.format(Locale.ROOT, "%,d", units);
        String cents = v.toPlainString().substring(v.toPlainString().indexOf('.'));
        return sign + " ".repeat(11 - integer.length()) + integer + cents;
    }
}
