package com.carddemo.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The GnuCOBOL numeric reads the account edits, CORPT00C's date parts and COTRTUPC's type code depend on. */
public final class Intrinsics {

    private static final String NUMBER = "(\\d+(?:\\.\\d*)?|\\.\\d+)";
    private static final String NUMBER_WITH_COMMAS = "(\\d[\\d,]*(?:\\.\\d*)?|\\.\\d+)";
    private static final String TRAILING_SIGN = "([+-]|CR|DB)";

    private static final Pattern NUMVAL_LEADING = Pattern.compile(" *([+-])? *" + NUMBER + " *");
    private static final Pattern NUMVAL_TRAILING = Pattern.compile(" *" + NUMBER + " *" + TRAILING_SIGN + " *");
    private static final Pattern NUMVAL_C_LEADING = Pattern.compile(
            " *(?:([+-]) *(?:\\$ *)?|\\$ *(?:([+-]) *)?)?" + NUMBER_WITH_COMMAS + " *");
    private static final Pattern NUMVAL_C_TRAILING = Pattern.compile(
            " *(?:\\$ *)?" + NUMBER_WITH_COMMAS + " *" + TRAILING_SIGN + " *");

    private static final BigDecimal TEN_DIGITS = BigDecimal.TEN.pow(10);

    private Intrinsics() {}

    /** {@code FUNCTION TEST-NUMVAL(text) = 0}. */
    public static boolean testNumval(String text) {
        return NUMVAL_LEADING.matcher(text).matches() || NUMVAL_TRAILING.matcher(text).matches();
    }

    /** {@code FUNCTION TEST-NUMVAL-C(text) = 0}, with the dollar sign as currency symbol. */
    static boolean testNumvalC(String text) {
        return NUMVAL_C_LEADING.matcher(text).matches() || NUMVAL_C_TRAILING.matcher(text).matches();
    }

    /** {@code FUNCTION NUMVAL(text)} of text that passes {@link #testNumval}. */
    public static BigDecimal numval(String text) {
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

    /**
     * {@code COMPUTE n = FUNCTION NUMVAL-C(text)} into PIC 9({@code digits}), as the zero-padded image a
     * MOVE back to the date field produces. GnuCOBOL's NUMVAL-C answers 0 for text that fails
     * TEST-NUMVAL-C; Enterprise COBOL leaves it undefined.
     */
    public static String numvalCImage(String text, int digits) {
        return ("%0" + digits + "d").formatted(testNumvalC(text) ? unsigned(numvalC(text), digits) : 0);
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

    /** The bytes of a PIC X({@code length}) holding {@code text}: padded with spaces, LOW-VALUES for null. */
    static String field(String text, int length) {
        return text == null ? "\0".repeat(length) : ("%-" + length + "s").formatted(text);
    }

    /**
     * A PIC 9(n) DISPLAY redefining {@code bytes}, as GnuCOBOL 3.1 compares and computes with it: each
     * byte counts as its code minus '0', so a space or LOW-VALUE is no zero and the value can go negative.
     */
    static long zoned(String bytes) {
        long value = 0;
        for (char c : bytes.toCharArray()) {
            value = value * 10 + (c - '0');
        }
        return value;
    }

    /** {@code COMPUTE n = value} into a PIC 9(digits): no sign, no decimals, high digits dropped. */
    public static int unsigned(BigDecimal value, int digits) {
        return value.abs().setScale(0, RoundingMode.DOWN).remainder(BigDecimal.TEN.pow(digits)).intValueExact();
    }

    /** {@code COMPUTE n = value} into a PIC S9(10)V99. */
    static BigDecimal signed9v2(BigDecimal value) {
        return value.setScale(2, RoundingMode.DOWN).remainder(TEN_DIGITS).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static boolean isNegative(String trailingSign) {
        return trailingSign.equals("-") || trailingSign.equals("CR") || trailingSign.equals("DB");
    }

    private static BigDecimal signed(String digits, boolean negative) {
        BigDecimal value = new BigDecimal(digits.replace(",", ""));
        return negative ? value.negate() : value;
    }
}
