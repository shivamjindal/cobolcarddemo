package com.carddemo.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The GnuCOBOL numeric reads COACTUPC's edits depend on: NUMVAL, NUMVAL-C and zoned DISPLAY fields. */
final class Intrinsics {

    private static final String NUMBER = "(\\d+(?:\\.\\d*)?|\\.\\d+)";
    private static final String NUMBER_WITH_COMMAS = "(\\d[\\d,]*(?:\\.\\d*)?|\\.\\d+)";
    private static final String TRAILING_SIGN = "([+-]|CR|DB)";

    private static final Pattern NUMVAL_LEADING = Pattern.compile(" *([+-])? *" + NUMBER + " *");
    private static final Pattern NUMVAL_TRAILING = Pattern.compile(" *" + NUMBER + " *" + TRAILING_SIGN + " *",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMVAL_C_LEADING = Pattern.compile(
            " *(?:([+-]) *(?:\\$ *)?|\\$ *(?:([+-]) *)?)?" + NUMBER_WITH_COMMAS + " *");
    private static final Pattern NUMVAL_C_TRAILING = Pattern.compile(
            " *(?:\\$ *)?" + NUMBER_WITH_COMMAS + " *" + TRAILING_SIGN + " *", Pattern.CASE_INSENSITIVE);

    private static final BigDecimal TEN_DIGITS = BigDecimal.TEN.pow(10);

    private Intrinsics() {}

    /** {@code FUNCTION TEST-NUMVAL(text) = 0}. */
    static boolean testNumval(String text) {
        return NUMVAL_LEADING.matcher(text).matches() || NUMVAL_TRAILING.matcher(text).matches();
    }

    /** {@code FUNCTION TEST-NUMVAL-C(text) = 0}, with the dollar sign as currency symbol. */
    static boolean testNumvalC(String text) {
        return NUMVAL_C_LEADING.matcher(text).matches() || NUMVAL_C_TRAILING.matcher(text).matches();
    }

    /** {@code FUNCTION NUMVAL(text)} of text that passes {@link #testNumval}. */
    static BigDecimal numval(String text) {
        Matcher leading = NUMVAL_LEADING.matcher(text);
        if (leading.matches()) {
            return signed(leading.group(2), "-".equals(leading.group(1)));
        }
        Matcher trailing = NUMVAL_TRAILING.matcher(text);
        if (trailing.matches()) {
            return signed(trailing.group(1), isNegative(trailing.group(2)));
        }
        throw new IllegalArgumentException("NUMVAL of '" + text + "'");
    }

    /** {@code FUNCTION NUMVAL-C(text)} of text that passes {@link #testNumvalC}. */
    static BigDecimal numvalC(String text) {
        Matcher leading = NUMVAL_C_LEADING.matcher(text);
        if (leading.matches()) {
            return signed(leading.group(3), "-".equals(leading.group(1)) || "-".equals(leading.group(2)));
        }
        Matcher trailing = NUMVAL_C_TRAILING.matcher(text);
        if (trailing.matches()) {
            return signed(trailing.group(1), isNegative(trailing.group(2)));
        }
        throw new IllegalArgumentException("NUMVAL-C of '" + text + "'");
    }

    /**
     * An unsigned zoned DISPLAY field of {@code length} digits holding {@code text}, padded with spaces;
     * null is LOW-VALUES. GnuCOBOL reads each byte's low nibble as its digit, so spaces and LOW-VALUES
     * count as zeros and letters as whatever their nibble is.
     */
    static long zoned(String text, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            int digit = text != null && i < text.length() ? text.charAt(i) & 0x0F : 0;
            value = value * 10 + digit;
        }
        return value;
    }

    /** {@code COMPUTE n = value} into a PIC 9(digits): no sign, no decimals, high digits dropped. */
    static int unsigned(BigDecimal value, int digits) {
        return value.abs().setScale(0, RoundingMode.DOWN).remainder(BigDecimal.TEN.pow(digits)).intValueExact();
    }

    /** {@code COMPUTE n = value} into a PIC S9(10)V99. */
    static BigDecimal signed9v2(BigDecimal value) {
        return value.setScale(2, RoundingMode.DOWN).remainder(TEN_DIGITS).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static boolean isNegative(String trailingSign) {
        return trailingSign.equals("-") || trailingSign.equalsIgnoreCase("CR") || trailingSign.equalsIgnoreCase("DB");
    }

    private static BigDecimal signed(String digits, boolean negative) {
        BigDecimal value = new BigDecimal(digits.replace(",", ""));
        return negative ? value.negate() : value;
    }
}
