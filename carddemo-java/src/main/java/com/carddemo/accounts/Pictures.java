package com.carddemo.accounts;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How the account screens show and read amounts and keys, as the legacy PICTUREs and NUMVAL-C did. */
final class Pictures {

    private static final String NUMBER = "(\\d[\\d,]*(?:\\.\\d*)?|\\.\\d+)";
    private static final Pattern LEADING_SIGN = Pattern.compile(" *([+-]?) *\\$? *" + NUMBER + " *");
    private static final Pattern TRAILING_SIGN = Pattern.compile(" *\\$? *" + NUMBER + " *([+-]|CR|DB|cr|db) *");
    private static final BigInteger HUNDRED = BigInteger.valueOf(100);
    private static final BigDecimal TOO_LARGE = BigDecimal.TEN.pow(10);

    private Pictures() {
    }

    /** PICOUT '+ZZZ,ZZZ,ZZZ.99': a sign, the zero-suppressed integer digits, then the cents. */
    static String amount(BigDecimal value) {
        BigInteger cents = value.setScale(2, RoundingMode.DOWN).unscaledValue();
        BigInteger units = cents.abs().divide(HUNDRED);
        String digits = units.signum() == 0 ? "" : String.format(Locale.ROOT, "%,d", units);
        return (cents.signum() < 0 ? "-" : "+") + " ".repeat(Math.max(0, 11 - digits.length())) + digits
                + String.format(Locale.ROOT, ".%02d", cents.abs().mod(HUNDRED));
    }

    /**
     * FUNCTION NUMVAL-C of what TEST-NUMVAL-C accepts: an optional sign before or after, '$', commas and
     * decimals. Cents beyond two are dropped, as COMPUTE into S9(10)V99 truncates; more than ten integer
     * digits would not fit the column, so they are not a valid amount.
     */
    static Optional<BigDecimal> parseAmount(String text) {
        Matcher leading = LEADING_SIGN.matcher(text);
        if (leading.matches()) {
            return fitting(number(leading.group(2), leading.group(1).equals("-")));
        }
        Matcher trailing = TRAILING_SIGN.matcher(text);
        if (trailing.matches()) {
            String sign = trailing.group(2).toUpperCase(Locale.ROOT);
            return fitting(number(trailing.group(1), !sign.equals("+")));
        }
        return Optional.empty();
    }

    static String accountId(long id) {
        return String.format("%011d", id);
    }

    static String customerId(int id) {
        return String.format("%09d", id);
    }

    private static BigDecimal number(String digits, boolean negative) {
        BigDecimal value = new BigDecimal(digits.replace(",", "")).setScale(2, RoundingMode.DOWN);
        return negative ? value.negate() : value;
    }

    private static Optional<BigDecimal> fitting(BigDecimal value) {
        return value.abs().compareTo(TOO_LARGE) < 0 ? Optional.of(value) : Optional.empty();
    }
}
