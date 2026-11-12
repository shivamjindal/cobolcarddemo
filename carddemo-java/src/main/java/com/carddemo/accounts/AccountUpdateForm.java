package com.carddemo.accounts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The COACTUP input fields in screen order: how each shows an account and customer, and how 1205 decides
 * whether the screen differs from what was fetched. The two keys are shown but never read back.
 */
final class AccountUpdateForm {

    static final String ACCOUNT_ID = "ACCTSID";

    private enum Compare { KEY, EXACT, IGNORING_CASE, AMOUNT }

    private record Field(String name, Compare compare, BiFunction<Account, Customer, String> shown) {

        boolean differs(String typed, Account account, Customer customer) {
            String fetched = shown.apply(account, customer);
            return switch (compare) {
                case KEY -> false;
                case EXACT -> !typed.equals(fetched);
                case IGNORING_CASE -> !typed.strip().toUpperCase(Locale.ROOT).equals(fetched.strip().toUpperCase(Locale.ROOT));
                case AMOUNT -> !Pictures.parseAmount(typed).equals(Pictures.parseAmount(fetched));
            };
        }
    }

    private static final List<Field> FIELDS = List.of(
            new Field(ACCOUNT_ID, Compare.KEY, (a, c) -> Pictures.accountId(a.id())),
            new Field("ACSTTUS", Compare.IGNORING_CASE, (a, c) -> a.activeStatus()),
            new Field("OPNYEAR", Compare.EXACT, (a, c) -> year(a.openDate())),
            new Field("OPNMON", Compare.EXACT, (a, c) -> month(a.openDate())),
            new Field("OPNDAY", Compare.EXACT, (a, c) -> day(a.openDate())),
            new Field("ACRDLIM", Compare.AMOUNT, (a, c) -> Pictures.amount(a.creditLimit())),
            new Field("EXPYEAR", Compare.EXACT, (a, c) -> year(a.expirationDate())),
            new Field("EXPMON", Compare.EXACT, (a, c) -> month(a.expirationDate())),
            new Field("EXPDAY", Compare.EXACT, (a, c) -> day(a.expirationDate())),
            new Field("ACSHLIM", Compare.AMOUNT, (a, c) -> Pictures.amount(a.cashCreditLimit())),
            new Field("RISYEAR", Compare.EXACT, (a, c) -> year(a.reissueDate())),
            new Field("RISMON", Compare.EXACT, (a, c) -> month(a.reissueDate())),
            new Field("RISDAY", Compare.EXACT, (a, c) -> day(a.reissueDate())),
            new Field("ACURBAL", Compare.AMOUNT, (a, c) -> Pictures.amount(a.currentBalance())),
            new Field("ACRCYCR", Compare.AMOUNT, (a, c) -> Pictures.amount(a.currentCycleCredit())),
            new Field("AADDGRP", Compare.IGNORING_CASE, (a, c) -> a.groupId()),
            new Field("ACRCYDB", Compare.AMOUNT, (a, c) -> Pictures.amount(a.currentCycleDebit())),
            new Field("ACSTNUM", Compare.KEY, (a, c) -> Pictures.customerId(c.id())),
            new Field("ACTSSN1", Compare.EXACT, (a, c) -> ssn(c).substring(0, 3)),
            new Field("ACTSSN2", Compare.EXACT, (a, c) -> ssn(c).substring(3, 5)),
            new Field("ACTSSN3", Compare.EXACT, (a, c) -> ssn(c).substring(5)),
            new Field("DOBYEAR", Compare.EXACT, (a, c) -> year(c.dateOfBirth())),
            new Field("DOBMON", Compare.EXACT, (a, c) -> month(c.dateOfBirth())),
            new Field("DOBDAY", Compare.EXACT, (a, c) -> day(c.dateOfBirth())),
            new Field("ACSTFCO", Compare.EXACT, (a, c) -> String.format("%03d", c.ficoScore())),
            new Field("ACSFNAM", Compare.IGNORING_CASE, (a, c) -> c.firstName()),
            new Field("ACSMNAM", Compare.IGNORING_CASE, (a, c) -> c.middleName()),
            new Field("ACSLNAM", Compare.IGNORING_CASE, (a, c) -> c.lastName()),
            new Field("ACSADL1", Compare.IGNORING_CASE, (a, c) -> c.addressLine1()),
            new Field("ACSSTTE", Compare.IGNORING_CASE, (a, c) -> c.stateCode()),
            new Field("ACSADL2", Compare.IGNORING_CASE, (a, c) -> c.addressLine2()),
            new Field("ACSZIPC", Compare.IGNORING_CASE, (a, c) -> c.zip()),
            new Field("ACSCITY", Compare.IGNORING_CASE, (a, c) -> c.addressLine3()),
            new Field("ACSCTRY", Compare.IGNORING_CASE, (a, c) -> c.countryCode()),
            new Field("ACSPH1A", Compare.EXACT, (a, c) -> phonePart(c.phone1(), 1, 4)),
            new Field("ACSPH1B", Compare.EXACT, (a, c) -> phonePart(c.phone1(), 5, 8)),
            new Field("ACSPH1C", Compare.EXACT, (a, c) -> phonePart(c.phone1(), 9, 13)),
            new Field("ACSGOVT", Compare.IGNORING_CASE, (a, c) -> c.governmentId()),
            new Field("ACSPH2A", Compare.EXACT, (a, c) -> phonePart(c.phone2(), 1, 4)),
            new Field("ACSPH2B", Compare.EXACT, (a, c) -> phonePart(c.phone2(), 5, 8)),
            new Field("ACSPH2C", Compare.EXACT, (a, c) -> phonePart(c.phone2(), 9, 13)),
            new Field("ACSEFTC", Compare.EXACT, (a, c) -> c.eftAccountId()),
            new Field("ACSPFLG", Compare.IGNORING_CASE, (a, c) -> c.primaryCardHolder()));

    private static final Set<String> ALL = FIELDS.stream().map(Field::name).collect(Collectors.toUnmodifiableSet());

    private AccountUpdateForm() {
    }

    /**
     * 3300-SETUP-SCREEN-ATTRS: only the account id before a record is fetched or after a failed save, every field
     * but the two keys and the country while editing, and nothing in confirmation or after a save.
     */
    static Set<String> readOnly(AccountUpdatePhase phase) {
        return switch (phase) {
            case NOT_FETCHED, LOCK_FAILED, SAVE_FAILED ->
                    ALL.stream().filter(name -> !name.equals(ACCOUNT_ID)).collect(Collectors.toUnmodifiableSet());
            case SHOWN, INVALID -> Set.of(ACCOUNT_ID, "ACSTNUM", "ACSCTRY");
            case CONFIRM, SAVED -> ALL;
        };
    }

    /** 1100-RECEIVE-MAP: a field holding only '*', the blank marker, reads as blank. */
    static Map<String, String> input(Map<String, String> fields) {
        Map<String, String> input = new HashMap<>();
        fields.forEach((name, value) -> input.put(name, value.equals("*") ? "" : value));
        return input;
    }

    static Map<String, String> shown(Account account, Customer customer) {
        Map<String, String> fields = new LinkedHashMap<>();
        FIELDS.forEach(f -> fields.put(f.name, f.shown.apply(account, customer)));
        return fields;
    }

    static boolean changed(Map<String, String> input, Account account, Customer customer) {
        return FIELDS.stream().anyMatch(f -> f.differs(input.getOrDefault(f.name, ""), account, customer));
    }

    /**
     * 3203-SHOW-UPDATED-VALUES after a failed edit: what was typed, valid amounts in their display picture,
     * and '*' in every field flagged blank. The keys still come from the fetched record.
     */
    static Map<String, String> echo(Map<String, String> input, Map<String, AccountEdits.Flag> flags, Account account,
                                    Customer customer) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            String typed = input.getOrDefault(f.name, "");
            String shown = switch (f.compare) {
                case KEY -> f.shown.apply(account, customer);
                case AMOUNT -> Pictures.parseAmount(typed).map(Pictures::amount).orElse(typed);
                case EXACT, IGNORING_CASE -> typed;
            };
            fields.put(f.name, flags.get(f.name) == AccountEdits.Flag.BLANK ? "*" : shown);
        }
        return fields;
    }

    /** 3300-SETUP-SCREEN-ATTRS: the first flagged field in screen order. */
    static String cursor(Map<String, AccountEdits.Flag> flags) {
        return FIELDS.stream().map(Field::name).filter(flags::containsKey).findFirst().orElse(ACCOUNT_ID);
    }

    /** The account as edited; keys and ACCT-ADDR-ZIP, which no field shows, stay as fetched. */
    static Account account(Map<String, String> input, Account fetched) {
        return new Account(fetched.id(), input.get("ACSTTUS"), amount(input, "ACURBAL"), amount(input, "ACRDLIM"),
                amount(input, "ACSHLIM"), date(input, "OPN"), date(input, "EXP"), date(input, "RIS"),
                amount(input, "ACRCYCR"), amount(input, "ACRCYDB"), fetched.addressZip(),
                input.getOrDefault("AADDGRP", ""));
    }

    /** The customer as edited, text as typed. An all-blank phone is stored blank. */
    static Customer customer(Map<String, String> input, Customer fetched) {
        return new Customer(fetched.id(), input.get("ACSFNAM"), input.getOrDefault("ACSMNAM", ""),
                input.get("ACSLNAM"), input.get("ACSADL1"), input.getOrDefault("ACSADL2", ""), input.get("ACSCITY"),
                input.get("ACSSTTE"), input.get("ACSCTRY"), input.get("ACSZIPC"), phone(input, "ACSPH1"),
                phone(input, "ACSPH2"),
                Integer.parseInt(input.get("ACTSSN1") + input.get("ACTSSN2") + input.get("ACTSSN3")),
                input.getOrDefault("ACSGOVT", ""), date(input, "DOB"), input.get("ACSEFTC"), input.get("ACSPFLG"),
                Integer.parseInt(input.get("ACSTFCO")));
    }

    private static BigDecimal amount(Map<String, String> input, String field) {
        return Pictures.parseAmount(input.get(field)).orElseThrow();
    }

    private static LocalDate date(Map<String, String> input, String prefix) {
        return LocalDate.of(Integer.parseInt(input.get(prefix + "YEAR")), Integer.parseInt(input.get(prefix + "MON")),
                Integer.parseInt(input.get(prefix + "DAY")));
    }

    private static String phone(Map<String, String> input, String prefix) {
        String area = input.getOrDefault(prefix + "A", "");
        String exchange = input.getOrDefault(prefix + "B", "");
        String line = input.getOrDefault(prefix + "C", "");
        return area.isEmpty() && exchange.isEmpty() && line.isEmpty() ? "" : "(" + area + ")" + exchange + "-" + line;
    }

    private static String year(LocalDate date) {
        return date == null ? "" : String.format("%04d", date.getYear());
    }

    private static String month(LocalDate date) {
        return date == null ? "" : String.format("%02d", date.getMonthValue());
    }

    private static String day(LocalDate date) {
        return date == null ? "" : String.format("%02d", date.getDayOfMonth());
    }

    private static String ssn(Customer customer) {
        return String.format("%09d", customer.ssn());
    }

    /** CUST-PHONE-NUM is '(AAA)BBB-CCCC'; a stored value too short for a part shows that part blank. */
    private static String phonePart(String phone, int from, int to) {
        return phone.length() < to ? "" : phone.substring(from, to).strip();
    }
}
