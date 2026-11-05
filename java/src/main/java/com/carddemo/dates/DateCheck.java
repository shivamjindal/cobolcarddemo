package com.carddemo.dates;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * CSUTLDTC: CEEDAYS through its feedback code, with the semantics of the stand-in
 * {@code characterization/harness/runtime/lestubs.c}, which recorded the goldens. The picture, without
 * trailing blanks, is read left to right: YYYY, MM and DD take digits from the same columns of the date,
 * another capital letter is a bad picture, and any other character is a separator nobody compares. Then
 * come, in order, a slot missing, the month, the day and the first Lilian day.
 */
public final class DateCheck {

    /** CEE2513, a date before 15 October 1582. COTRN02C and CORPT00C let it through. */
    public static final int UNSUPPORTED_RANGE = 2513;

    private static final int FIELD_LENGTH = 10;
    private static final LocalDate LILIAN_DAY_ONE = LocalDate.of(1582, 10, 15);

    private DateCheck() {}

    /** SEVERITY and MSG-NO of the feedback token, and CSUTLDTC's WS-RESULT without trailing blanks. */
    public record Feedback(int severity, int msgNo, String result) {
        public boolean valid() {
            return severity == 0;
        }
    }

    private enum Condition {
        VALID(0, "Date is valid"),
        INSUFFICIENT_DATA(2507, "Insufficient"),
        BAD_DATE_VALUE(2508, "Datevalue error"),
        UNSUPP_RANGE(UNSUPPORTED_RANGE, "Unsupp. Range"),
        INVALID_MONTH(2517, "Invalid month"),
        BAD_PIC_STRING(2518, "Bad Pic String"),
        NON_NUMERIC_DATA(2520, "Nonnumeric data");

        private final Feedback feedback;

        Condition(int msgNo, String result) {
            this.feedback = new Feedback(msgNo == 0 ? 0 : 3, msgNo, result);
        }
    }

    /** {@code CALL 'CSUTLDTC' USING date picture result}: both arguments are PIC X(10). */
    public static Feedback check(String date, String picture) {
        return ceedays(field(date), field(picture)).feedback;
    }

    private static Condition ceedays(String date, String picture) {
        int length = picture.length();
        while (length > 0 && picture.charAt(length - 1) == ' ') {
            length--;
        }
        if (length == 0) {
            return Condition.BAD_PIC_STRING;
        }
        String pic = picture.substring(0, length);
        int year = -1;
        int month = -1;
        int day = -1;
        int i = 0;
        while (i < pic.length()) {
            String slot = pic.startsWith("YYYY", i) ? "YYYY" : pic.startsWith("MM", i) ? "MM" : pic.startsWith("DD", i) ? "DD" : null;
            if (slot == null) {
                if (pic.charAt(i) >= 'A' && pic.charAt(i) <= 'Z') {
                    return Condition.BAD_PIC_STRING;
                }
                i++;
                continue;
            }
            String digits = date.substring(i, i + slot.length());
            if (!digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return Condition.NON_NUMERIC_DATA;
            }
            int value = Integer.parseInt(digits);
            switch (slot) {
                case "YYYY" -> year = value;
                case "MM" -> month = value;
                default -> day = value;
            }
            i += slot.length();
        }
        if (year < 0 || month < 0 || day < 0) {
            return Condition.INSUFFICIENT_DATA;
        }
        if (month < 1 || month > 12) {
            return Condition.INVALID_MONTH;
        }
        if (day < 1 || day > YearMonth.of(year, month).lengthOfMonth()) {
            return Condition.BAD_DATE_VALUE;
        }
        return LocalDate.of(year, month, day).isBefore(LILIAN_DAY_ONE) ? Condition.UNSUPP_RANGE : Condition.VALID;
    }

    private static String field(String text) {
        return text.length() >= FIELD_LENGTH ? text.substring(0, FIELD_LENGTH) : text + " ".repeat(FIELD_LENGTH - text.length());
    }
}
