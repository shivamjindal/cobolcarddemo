package com.carddemo.account;

import static com.carddemo.online.maps.CACTUPA.ACRCYCR;
import static com.carddemo.online.maps.CACTUPA.ACRCYDB;
import static com.carddemo.online.maps.CACTUPA.ACRDLIM;
import static com.carddemo.online.maps.CACTUPA.ACSADL1;
import static com.carddemo.online.maps.CACTUPA.ACSCITY;
import static com.carddemo.online.maps.CACTUPA.ACSCTRY;
import static com.carddemo.online.maps.CACTUPA.ACSEFTC;
import static com.carddemo.online.maps.CACTUPA.ACSFNAM;
import static com.carddemo.online.maps.CACTUPA.ACSHLIM;
import static com.carddemo.online.maps.CACTUPA.ACSLNAM;
import static com.carddemo.online.maps.CACTUPA.ACSMNAM;
import static com.carddemo.online.maps.CACTUPA.ACSPFLG;
import static com.carddemo.online.maps.CACTUPA.ACSPH1A;
import static com.carddemo.online.maps.CACTUPA.ACSPH1B;
import static com.carddemo.online.maps.CACTUPA.ACSPH1C;
import static com.carddemo.online.maps.CACTUPA.ACSPH2A;
import static com.carddemo.online.maps.CACTUPA.ACSPH2B;
import static com.carddemo.online.maps.CACTUPA.ACSPH2C;
import static com.carddemo.online.maps.CACTUPA.ACSSTTE;
import static com.carddemo.online.maps.CACTUPA.ACSTFCO;
import static com.carddemo.online.maps.CACTUPA.ACSTTUS;
import static com.carddemo.online.maps.CACTUPA.ACSZIPC;
import static com.carddemo.online.maps.CACTUPA.ACTSSN1;
import static com.carddemo.online.maps.CACTUPA.ACTSSN2;
import static com.carddemo.online.maps.CACTUPA.ACTSSN3;
import static com.carddemo.online.maps.CACTUPA.ACURBAL;
import static com.carddemo.online.maps.CACTUPA.DOBDAY;
import static com.carddemo.online.maps.CACTUPA.DOBMON;
import static com.carddemo.online.maps.CACTUPA.DOBYEAR;
import static com.carddemo.online.maps.CACTUPA.EXPDAY;
import static com.carddemo.online.maps.CACTUPA.EXPMON;
import static com.carddemo.online.maps.CACTUPA.EXPYEAR;
import static com.carddemo.online.maps.CACTUPA.OPNDAY;
import static com.carddemo.online.maps.CACTUPA.OPNMON;
import static com.carddemo.online.maps.CACTUPA.OPNYEAR;
import static com.carddemo.online.maps.CACTUPA.RISDAY;
import static com.carddemo.online.maps.CACTUPA.RISMON;
import static com.carddemo.online.maps.CACTUPA.RISYEAR;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.carddemo.LegacyDefect;
import com.carddemo.online.maps.CACTUPA;

/**
 * The FLG-xxx edit flags of one COACTUPC task, by the field each one turns red, and the edits of
 * 1200-EDIT-MAP-INPUTS that set them. A field without a flag passed or was not edited.
 */
final class AccountEdits {

    /** One edit flag: LOW-VALUES, '0' or 'B'. */
    enum Verdict {
        VALID, NOT_OK, BLANK;

        boolean failed() {
            return this != VALID;
        }
    }

    private interface Edit {
        void apply(Run run);
    }

    /** 1220-EDIT-YESNO. */
    private record YesNo(String name, CACTUPA field) implements Edit {
        @Override
        public void apply(Run run) {
            run.judge(field, run.yesNo(name, field));
        }
    }

    /** 1250-EDIT-SIGNED-9V2. */
    private record Amount(String name, CACTUPA field) implements Edit {
        @Override
        public void apply(Run run) {
            run.judge(field, run.signedAmount(name, field));
        }
    }

    /** EDIT-DATE-CCYYMMDD from CSUTLDPY. */
    private record DateOf(String name, CACTUPA year, CACTUPA month, CACTUPA day) implements Edit {
        @Override
        public void apply(Run run) {
            run.date(name, year, month, day);
        }
    }

    /** EDIT-DATE-CCYYMMDD, then EDIT-DATE-OF-BIRTH once the date is valid. */
    private record BirthDate(String name, CACTUPA year, CACTUPA month, CACTUPA day) implements Edit {
        @Override
        public void apply(Run run) {
            if (run.date(name, year, month, day)) {
                run.notInFuture(name, year, month, day);
            }
        }
    }

    /** 1265-EDIT-US-SSN. */
    private record Ssn() implements Edit {
        @Override
        public void apply(Run run) {
            run.ssn();
        }
    }

    /** 1245-EDIT-NUM-REQD: exactly {@code digits} digits, not all zeros. */
    private record Digits(String name, CACTUPA field, int digits) implements Edit {
        @Override
        public void apply(Run run) {
            run.judge(field, run.digits(name, field, digits));
        }
    }

    /** 1245-EDIT-NUM-REQD, then 1275-EDIT-FICO-SCORE once it passes. */
    private record FicoScore(String name, CACTUPA field) implements Edit {
        @Override
        public void apply(Run run) {
            Verdict verdict = run.digits(name, field, 3);
            int score = verdict == Verdict.VALID ? Integer.parseInt(run.text(field)) : 0;
            if (verdict == Verdict.VALID && (score < 300 || score > 850)) {
                verdict = run.fail(Verdict.NOT_OK, name + ": should be between 300 and 850");
            }
            run.judge(field, verdict);
        }
    }

    /** 1225-EDIT-ALPHA-REQD, or 1235-EDIT-ALPHA-OPT when the field may be left blank. */
    private record Alphabetic(String name, CACTUPA field, boolean required) implements Edit {
        @Override
        public void apply(Run run) {
            run.judge(field, run.alphabetic(name, field, required));
        }
    }

    /** 1215-EDIT-MANDATORY. */
    private record Mandatory(String name, CACTUPA field) implements Edit {
        @Override
        public void apply(Run run) {
            run.judge(field, run.text(field) == null ? run.fail(Verdict.BLANK, name + " must be supplied.") : Verdict.VALID);
        }
    }

    /** 1225-EDIT-ALPHA-REQD, then 1270-EDIT-US-STATE-CD once it passes. */
    private record StateCode(String name, CACTUPA field) implements Edit {
        @Override
        public void apply(Run run) {
            Verdict verdict = run.alphabetic(name, field, true);
            if (verdict == Verdict.VALID && !LookupCodes.STATE_CODES.contains(run.text(field))) {
                verdict = run.fail(Verdict.NOT_OK, name + ": is not a valid state code");
            }
            run.judge(field, verdict);
        }
    }

    /** 1260-EDIT-US-PHONE-NUM. */
    private record Phone(String name, CACTUPA area, CACTUPA prefix, CACTUPA line) implements Edit {
        @Override
        public void apply(Run run) {
            run.phone(name, area, prefix, line);
        }
    }

    /** 1280-EDIT-US-STATE-ZIP-CD, run only when the state and the zip each passed. */
    private record StateZip(CACTUPA state, CACTUPA zip) implements Edit {
        @Override
        public void apply(Run run) {
            if (run.verdict(state) == Verdict.VALID && run.verdict(zip) == Verdict.VALID
                    && !LookupCodes.STATE_ZIP_PREFIXES.contains(run.text(state) + run.text(zip).substring(0, 2))) {
                run.fail(Verdict.NOT_OK, "Invalid zip code for state");
                run.judge(state, Verdict.NOT_OK);
                run.judge(zip, Verdict.NOT_OK);
            }
        }
    }

    private static final List<Edit> IN_ORDER = List.of(
            new YesNo("Account Status", ACSTTUS),
            new DateOf("Open Date", OPNYEAR, OPNMON, OPNDAY),
            new Amount("Credit Limit", ACRDLIM),
            new DateOf("Expiry Date", EXPYEAR, EXPMON, EXPDAY),
            new Amount("Cash Credit Limit", ACSHLIM),
            new DateOf("Reissue Date", RISYEAR, RISMON, RISDAY),
            new Amount("Current Balance", ACURBAL),
            new Amount("Current Cycle Credit Limit", ACRCYCR),
            new Amount("Current Cycle Debit Limit", ACRCYDB),
            new Ssn(),
            new BirthDate("Date of Birth", DOBYEAR, DOBMON, DOBDAY),
            new FicoScore("FICO Score", ACSTFCO),
            new Alphabetic("First Name", ACSFNAM, true),
            new Alphabetic("Middle Name", ACSMNAM, false),
            new Alphabetic("Last Name", ACSLNAM, true),
            new Mandatory("Address Line 1", ACSADL1),
            new StateCode("State", ACSSTTE),
            new Digits("Zip", ACSZIPC, 5),
            new Alphabetic("City", ACSCITY, true),
            new Alphabetic("Country", ACSCTRY, true),
            new Phone("Phone Number 1", ACSPH1A, ACSPH1B, ACSPH1C),
            new Phone("Phone Number 2", ACSPH2A, ACSPH2B, ACSPH2C),
            new Digits("EFT Account Id", ACSEFTC, 10),
            new YesNo("Primary Card Holder", ACSPFLG),
            new StateZip(ACSSTTE, ACSZIPC));

    private static final Pattern ALPHABETS = Pattern.compile("[A-Za-z ]*");
    private static final Pattern FOUR_DIGITS = Pattern.compile("\\d{4}");
    private static final Set<Integer> THIRTY_ONE_DAY_MONTHS = Set.of(1, 3, 5, 7, 8, 10, 12);

    private final EnumMap<CACTUPA, Verdict> verdicts = new EnumMap<>(CACTUPA.class);

    private AccountEdits() {}

    /** {@code MOVE LOW-VALUES TO WS-NON-KEY-FLAGS}. */
    static AccountEdits none() {
        return new AccountEdits();
    }

    @LegacyDefect(id = "COACTUPC-edits-unchanged-fields", goldens = "account-update-seed-customer-unchanged-fails",
            behavior = "once anything changed, every field is edited, changed or not, so stored data that breaks an "
                    + "edit blocks every save; seed account 1 fails on its FICO score 274, its NC zip 12546 and its "
                    + "phone 2 area code 373",
            fix = "edit only the fields that changed, or clean the seed data")
    static AccountEdits run(Entered entered, ReturnMessage message, LocalDate today) {
        AccountEdits edits = new AccountEdits();
        Run run = edits.new Run(entered, message, today);
        IN_ORDER.forEach(edit -> edit.apply(run));
        return edits;
    }

    /** INPUT-ERROR. */
    boolean anyFailed() {
        return verdicts.values().stream().anyMatch(Verdict::failed);
    }

    boolean failed(CACTUPA field) {
        Verdict verdict = verdicts.get(field);
        return verdict != null && verdict.failed();
    }

    boolean blank(CACTUPA field) {
        return verdicts.get(field) == Verdict.BLANK;
    }

    /** The first failing field in map order, the order of 3300's cursor EVALUATE. */
    Optional<CACTUPA> firstFailed() {
        return verdicts.keySet().stream().filter(this::failed).findFirst();
    }

    /** One run of the edits over what the terminal sent. */
    private final class Run {
        private final Entered entered;
        private final ReturnMessage message;
        private final LocalDate today;

        Run(Entered entered, ReturnMessage message, LocalDate today) {
            this.entered = entered;
            this.message = message;
            this.today = today;
        }

        String text(CACTUPA field) {
            return entered.text(field);
        }

        Verdict verdict(CACTUPA field) {
            return verdicts.get(field);
        }

        void judge(CACTUPA field, Verdict verdict) {
            verdicts.put(field, verdict);
        }

        /** SET INPUT-ERROR, and the message if it is the first. */
        Verdict fail(Verdict verdict, String text) {
            message.offer(text);
            return verdict;
        }

        Verdict yesNo(String name, CACTUPA field) {
            String text = text(field);
            if (text == null || text.equals("0")) {
                return fail(Verdict.BLANK, name + " must be supplied.");
            }
            return text.equals("Y") || text.equals("N") ? Verdict.VALID : fail(Verdict.NOT_OK, name + " must be Y or N.");
        }

        Verdict signedAmount(String name, CACTUPA field) {
            String text = text(field);
            if (text == null) {
                return fail(Verdict.BLANK, name + " must be supplied.");
            }
            return Intrinsics.testNumvalC(text) ? Verdict.VALID : fail(Verdict.NOT_OK, name + " is not valid");
        }

        Verdict digits(String name, CACTUPA field, int digits) {
            String text = text(field);
            if (text == null) {
                return fail(Verdict.BLANK, name + " must be supplied.");
            }
            if (text.length() != digits || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return fail(Verdict.NOT_OK, name + " must be all numeric.");
            }
            return Long.parseLong(text) == 0 ? fail(Verdict.NOT_OK, name + " must not be zero.") : Verdict.VALID;
        }

        Verdict alphabetic(String name, CACTUPA field, boolean required) {
            String text = text(field);
            if (text == null) {
                return required ? fail(Verdict.BLANK, name + " must be supplied.") : Verdict.VALID;
            }
            return ALPHABETS.matcher(text).matches() ? Verdict.VALID : fail(Verdict.NOT_OK, name + " can have alphabets only.");
        }

        /** EDIT-DATE-CCYYMMDD; true when year, month and day all passed. */
        boolean date(String name, CACTUPA yearField, CACTUPA monthField, CACTUPA dayField) {
            String year = text(yearField);
            String month = text(monthField);
            String day = text(dayField);
            Verdict y = year(name, year);

            Verdict m = Verdict.VALID;
            int mm = (int) Intrinsics.zoned(month, 2);
            if (month == null) {
                m = fail(Verdict.BLANK, name + " : Month must be supplied.");
            } else if (mm < 1 || mm > 12 || !Intrinsics.testNumval(month)) {
                m = fail(Verdict.NOT_OK, name + ": Month must be a number between 1 and 12.");
            } else {
                mm = Intrinsics.unsigned(Intrinsics.numval(month), 2);
            }

            Verdict d = Verdict.VALID;
            int dd = (int) Intrinsics.zoned(day, 2);
            if (day == null) {
                d = fail(Verdict.BLANK, name + " : Day must be supplied.");
            } else if (!Intrinsics.testNumval(day)) {
                d = fail(Verdict.NOT_OK, name + ":day must be a number between 1 and 31.");
            } else {
                dd = Intrinsics.unsigned(Intrinsics.numval(day), 2);
                if (dd < 1 || dd > 31) {
                    d = fail(Verdict.NOT_OK, name + ":day must be a number between 1 and 31.");
                }
            }

            if (!THIRTY_ONE_DAY_MONTHS.contains(mm) && dd == 31) {
                m = d = fail(Verdict.NOT_OK, name + ":Cannot have 31 days in this month.");
            } else if (mm == 2 && dd == 30) {
                m = d = fail(Verdict.NOT_OK, name + ":Cannot have 30 days in this month.");
            } else if (mm == 2 && dd == 29 && !leapYear(year)) {
                y = m = d = fail(Verdict.NOT_OK, name + ":Not a leap year.Cannot have 29 days in this month.");
            }
            judge(yearField, y);
            judge(monthField, m);
            judge(dayField, d);
            return y == Verdict.VALID && m == Verdict.VALID && d == Verdict.VALID;
        }

        private Verdict year(String name, String year) {
            if (year == null) {
                return fail(Verdict.BLANK, name + " : Year must be supplied.");
            }
            if (!FOUR_DIGITS.matcher(year).matches()) {
                return fail(Verdict.NOT_OK, name + " must be 4 digit number.");
            }
            if (!year.startsWith("19") && !year.startsWith("20")) {
                return fail(Verdict.NOT_OK, name + " : Century is not valid.");
            }
            return Verdict.VALID;
        }

        /** The DIVIDE in EDIT-DAY-MONTH-YEAR, on the zoned year whatever its edit said. */
        private static boolean leapYear(String year) {
            long yy = year == null || year.length() <= 2 ? 0 : Intrinsics.zoned(year.substring(2), 2);
            return Intrinsics.zoned(year, 4) % (yy == 0 ? 400 : 4) == 0;
        }

        /** EDIT-DATE-OF-BIRTH. */
        void notInFuture(String name, CACTUPA year, CACTUPA month, CACTUPA day) {
            if (!today.isAfter(entered.date(year, month, day))) {
                fail(Verdict.NOT_OK, name + ":cannot be in the future ");
                judge(year, Verdict.NOT_OK);
                judge(month, Verdict.NOT_OK);
                judge(day, Verdict.NOT_OK);
            }
        }

        void ssn() {
            Verdict first = digits("SSN: First 3 chars", ACTSSN1, 3);
            int area = first == Verdict.VALID ? Integer.parseInt(text(ACTSSN1)) : -1;
            judge(ACTSSN1, area == 0 || area == 666 || area >= 900
                    ? fail(Verdict.NOT_OK, "SSN: First 3 chars: should not be 000, 666, or between 900 and 999")
                    : first);
            if (editsRestOfSsn(first)) {
                judge(ACTSSN2, digits("SSN 4th & 5th chars", ACTSSN2, 2));
                judge(ACTSSN3, digits("SSN Last 4 chars", ACTSSN3, 4));
            }
        }

        @LegacyDefect(id = "COACTUPC-ssn-parts-skipped",
                behavior = "a missing END-IF in 1265-EDIT-US-SSN nests the edits of the 4th-5th and last four SSN "
                        + "characters under the first part's IF, so they are skipped when the first three are blank "
                        + "or not numeric",
                fix = "close the IF after the 000/666/9xx check of the first part")
        private static boolean editsRestOfSsn(Verdict firstPart) {
            return firstPart == Verdict.VALID;
        }

        void phone(String name, CACTUPA area, CACTUPA prefix, CACTUPA line) {
            if (text(area) == null && text(prefix) == null && text(line) == null) {
                judge(area, Verdict.VALID);
                judge(prefix, Verdict.VALID);
                judge(line, Verdict.VALID);
                return;
            }
            String code = text(area);
            Verdict a = phonePart(name + ": Area code", code, 3);
            if (a == Verdict.VALID && !LookupCodes.GENERAL_PURPOSE_AREA_CODES.contains(code)) {
                a = fail(Verdict.NOT_OK, name + ": Not valid North America general purpose area code");
            }
            judge(area, a);
            judge(prefix, phonePart(name + ": Prefix code", text(prefix), 3));
            judge(line, phonePart(name + ": Line number code", text(line), 4));
        }

        private Verdict phonePart(String part, String text, int digits) {
            if (text == null) {
                return fail(Verdict.BLANK, part + " must be supplied.");
            }
            if (text.length() != digits || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return fail(Verdict.NOT_OK, part + " must be A " + digits + " digit number.");
            }
            return Integer.parseInt(text) == 0 ? fail(Verdict.NOT_OK, part + " cannot be zero") : Verdict.VALID;
        }
    }
}
