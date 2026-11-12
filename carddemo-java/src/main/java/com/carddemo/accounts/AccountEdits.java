package com.carddemo.accounts;

import java.time.LocalDate;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 1200-EDIT-MAP-INPUTS of COACTUPC as a table: each edit in edit order with the label its messages use.
 * Every edit runs and flags the fields it rejects; the message is the first failure's (WS-RETURN-MSG-OFF).
 */
final class AccountEdits {

    /** BLANK fields also show '*' (CSSETATY). */
    enum Flag { BLANK, INVALID }

    /** The first failure's message, or empty, and every flagged field. */
    record Problems(Optional<String> message, Map<String, Flag> flags) {
    }

    private interface Edit {
        void check(Run run);
    }

    private static final List<Edit> EDITS = List.of(
            yesNo("ACSTTUS", "Account Status"),
            date("OPN", "Open Date"),
            amount("ACRDLIM", "Credit Limit"),
            date("EXP", "Expiry Date"),
            amount("ACSHLIM", "Cash Credit Limit"),
            date("RIS", "Reissue Date"),
            amount("ACURBAL", "Current Balance"),
            amount("ACRCYCR", "Current Cycle Credit Limit"),
            amount("ACRCYDB", "Current Cycle Debit Limit"),
            AccountEdits::ssn,
            AccountEdits::birthDate,
            run -> {
                if (number(run, "ACSTFCO", "FICO Score", 3) && !between(run.value("ACSTFCO"), 300, 850)) {
                    run.fail("FICO Score: should be between 300 and 850", Flag.INVALID, "ACSTFCO");
                }
            },
            alpha("ACSFNAM", "First Name", true),
            alpha("ACSMNAM", "Middle Name", false),
            alpha("ACSLNAM", "Last Name", true),
            run -> required(run, "ACSADL1", "Address Line 1"),
            run -> {
                if (alpha(run, "ACSSTTE", "State", true) && !Lookups.STATE_CODES.contains(run.value("ACSSTTE"))) {
                    run.fail("State: is not a valid state code", Flag.INVALID, "ACSSTTE");
                }
            },
            run -> number(run, "ACSZIPC", "Zip", 5),
            alpha("ACSCITY", "City", true),
            alpha("ACSCTRY", "Country", true),
            phone("ACSPH1", "Phone Number 1"),
            phone("ACSPH2", "Phone Number 2"),
            run -> number(run, "ACSEFTC", "EFT Account Id", 10),
            yesNo("ACSPFLG", "Primary Card Holder"),
            AccountEdits::stateZip);

    private AccountEdits() {
    }

    /** {@code input} holds every field by base name, right-trimmed, with '*' already read as blank. */
    static Problems check(Map<String, String> input, LocalDate today) {
        Run run = new Run(input, today);
        EDITS.forEach(edit -> edit.check(run));
        return new Problems(Optional.ofNullable(run.message), run.flags);
    }

    private static Edit yesNo(String field, String label) {
        return run -> {
            String value = run.value(field);
            if (value.isEmpty() || value.equals("0")) {
                run.fail(label + " must be supplied.", Flag.BLANK, field);
            } else if (!value.equals("Y") && !value.equals("N")) {
                run.fail(label + " must be Y or N.", Flag.INVALID, field);
            }
        };
    }

    private static Edit amount(String field, String label) {
        return run -> {
            String value = run.value(field);
            if (value.isEmpty()) {
                run.fail(label + " must be supplied.", Flag.BLANK, field);
            } else if (Pictures.parseAmount(value).isEmpty()) {
                run.fail(label + " is not valid", Flag.INVALID, field);
            }
        };
    }

    private static Edit alpha(String field, String label, boolean required) {
        return run -> alpha(run, field, label, required);
    }

    private static boolean alpha(Run run, String field, String label, boolean required) {
        String value = run.value(field);
        if (value.isBlank()) {
            if (required) {
                run.fail(label + " must be supplied.", Flag.BLANK, field);
            }
            return !required;
        }
        if (!value.chars().allMatch(ch -> ch == ' ' || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z'))) {
            run.fail(label + " can have alphabets only.", Flag.INVALID, field);
            return false;
        }
        return true;
    }

    private static boolean required(Run run, String field, String label) {
        if (run.value(field).isBlank()) {
            run.fail(label + " must be supplied.", Flag.BLANK, field);
            return false;
        }
        return true;
    }

    /** 1245-EDIT-NUM-REQD: every one of the field's digits typed (the 3270 field is space padded), not all zero. */
    private static boolean number(Run run, String field, String label, int digits) {
        if (!required(run, field, label)) {
            return false;
        }
        String value = run.value(field);
        if (!value.matches("\\d{" + digits + "}")) {
            return run.fail(label + " must be all numeric.", Flag.INVALID, field);
        }
        if (value.chars().allMatch(ch -> ch == '0')) {
            return run.fail(label + " must not be zero.", Flag.INVALID, field);
        }
        return true;
    }

    private static boolean between(String digits, int low, int high) {
        int value = Integer.parseInt(digits);
        return value >= low && value <= high;
    }

    private static void ssn(Run run) {
        String first = run.value("ACTSSN1");
        if (number(run, "ACTSSN1", "SSN: First 3 chars", 3) && (first.equals("666") || between(first, 900, 999))) {
            run.fail("SSN: First 3 chars: should not be 000, 666, or between 900 and 999", Flag.INVALID, "ACTSSN1");
        }
        number(run, "ACTSSN2", "SSN 4th & 5th chars", 2);
        number(run, "ACTSSN3", "SSN Last 4 chars", 4);
    }

    /** CSUTLDPY: year, month and day each checked, then the combinations, only when all three passed. */
    private static Edit date(String prefix, String label) {
        return run -> date(run, prefix, label);
    }

    private static Optional<LocalDate> date(Run run, String prefix, String label) {
        String yearField = prefix + "YEAR";
        String monthField = prefix + "MON";
        String dayField = prefix + "DAY";
        String year = run.value(yearField);
        String month = run.value(monthField);
        String day = run.value(dayField);
        boolean partsValid = true;
        if (year.isEmpty()) {
            partsValid = run.fail(label + " : Year must be supplied.", Flag.BLANK, yearField);
        } else if (!year.matches("\\d{4}")) {
            partsValid = run.fail(label + " must be 4 digit number.", Flag.INVALID, yearField);
        } else if (!year.startsWith("19") && !year.startsWith("20")) {
            partsValid = run.fail(label + " : Century is not valid.", Flag.INVALID, yearField);
        }
        if (month.isEmpty()) {
            partsValid = run.fail(label + " : Month must be supplied.", Flag.BLANK, monthField);
        } else if (!month.matches("\\d{1,2}") || Integer.parseInt(month) < 1 || Integer.parseInt(month) > 12) {
            partsValid = run.fail(label + ": Month must be a number between 1 and 12.", Flag.INVALID, monthField);
        }
        if (day.isEmpty()) {
            partsValid = run.fail(label + " : Day must be supplied.", Flag.BLANK, dayField);
        } else if (!day.matches("\\d{1,2}") || Integer.parseInt(day) < 1 || Integer.parseInt(day) > 31) {
            partsValid = run.fail(label + ":day must be a number between 1 and 31.", Flag.INVALID, dayField);
        }
        if (!partsValid) {
            return Optional.empty();
        }
        int y = Integer.parseInt(year);
        int m = Integer.parseInt(month);
        int d = Integer.parseInt(day);
        if (d == 31 && List.of(2, 4, 6, 9, 11).contains(m)) {
            run.fail(label + ":Cannot have 31 days in this month.", Flag.INVALID, monthField, dayField);
            return Optional.empty();
        }
        if (m == 2 && d == 30) {
            run.fail(label + ":Cannot have 30 days in this month.", Flag.INVALID, monthField, dayField);
            return Optional.empty();
        }
        if (m == 2 && d == 29 && !Year.isLeap(y)) {
            run.fail(label + ":Not a leap year.Cannot have 29 days in this month.", Flag.INVALID,
                    yearField, monthField, dayField);
            return Optional.empty();
        }
        return Optional.of(LocalDate.of(y, m, d));
    }

    /** EDIT-DATE-OF-BIRTH: strictly before today, so a birth date of today is rejected too. */
    private static void birthDate(Run run) {
        date(run, "DOB", "Date of Birth").filter(dob -> !dob.isBefore(run.today)).ifPresent(dob ->
                run.fail("Date of Birth:cannot be in the future", Flag.INVALID, "DOBYEAR", "DOBMON", "DOBDAY"));
    }

    /** 1260-EDIT-US-PHONE-NUM: an all-blank number is allowed; otherwise each part is checked in turn. */
    private static Edit phone(String prefix, String label) {
        return run -> {
            String area = prefix + "A";
            String exchange = prefix + "B";
            String line = prefix + "C";
            if (run.value(area).isEmpty() && run.value(exchange).isEmpty() && run.value(line).isEmpty()) {
                return;
            }
            if (phonePart(run, area, label + ": Area code", 3)
                    && !Lookups.GENERAL_PURPOSE_AREA_CODES.contains(run.value(area))) {
                run.fail(label + ": Not valid North America general purpose area code", Flag.INVALID, area);
            }
            phonePart(run, exchange, label + ": Prefix code", 3);
            phonePart(run, line, label + ": Line number code", 4);
        };
    }

    private static boolean phonePart(Run run, String field, String label, int digits) {
        String value = run.value(field);
        if (value.isEmpty()) {
            return run.fail(label + " must be supplied.", Flag.BLANK, field);
        }
        if (!value.matches("\\d{" + digits + "}")) {
            return run.fail(label + " must be A " + digits + " digit number.", Flag.INVALID, field);
        }
        if (Integer.parseInt(value) == 0) {
            return run.fail(label + " cannot be zero", Flag.INVALID, field);
        }
        return true;
    }

    /** 1280-EDIT-US-STATE-ZIP-CD: runs last, and only when state and zip each passed. */
    private static void stateZip(Run run) {
        if (run.flags.containsKey("ACSSTTE") || run.flags.containsKey("ACSZIPC")) {
            return;
        }
        if (!Lookups.STATE_ZIP_PREFIXES.contains(run.value("ACSSTTE") + run.value("ACSZIPC").substring(0, 2))) {
            run.fail("Invalid zip code for state", Flag.INVALID, "ACSSTTE", "ACSZIPC");
        }
    }

    /** One pass over the screen: the input, today's date, the first message and the flags so far. */
    private static final class Run {
        private final Map<String, String> input;
        private final LocalDate today;
        private final Map<String, Flag> flags = new LinkedHashMap<>();
        private String message;

        Run(Map<String, String> input, LocalDate today) {
            this.input = input;
            this.today = today;
        }

        String value(String field) {
            return input.getOrDefault(field, "");
        }

        /** Flags the fields, keeps the message if it is the first, and answers false for use in conditions. */
        boolean fail(String text, Flag flag, String... fields) {
            if (message == null) {
                message = text;
            }
            for (String field : fields) {
                flags.put(field, flag);
            }
            return false;
        }
    }
}
