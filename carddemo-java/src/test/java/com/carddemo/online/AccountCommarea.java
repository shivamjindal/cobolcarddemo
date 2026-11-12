package com.carddemo.online;

import com.carddemo.accounts.Account;
import com.carddemo.accounts.AccountUpdatePhase;
import com.carddemo.accounts.AccountUpdateState;
import com.carddemo.accounts.Customer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.carddemo.online.SpecCase.text;

/**
 * COACTUPC's ACUP-CHANGE-ACTION, ACUP-OLD-* and ACUP-NEW-* as AccountUpdateState; COACTVWC keeps no state.
 * An ACUP-NEW-* field a case leaves out equals its ACUP-OLD-* field, since the legacy program refilled
 * ACUP-NEW-* from the screen on every key.
 */
class AccountCommarea implements CommareaMapping {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private static final Map<String, Function<Account, String>> ACCOUNT = Map.ofEntries(
            Map.entry("ACCT-ID", a -> String.format("%011d", a.id())),
            Map.entry("ACTIVE-STATUS", Account::activeStatus),
            Map.entry("CURR-BAL-N", a -> a.currentBalance().toPlainString()),
            Map.entry("CREDIT-LIMIT-N", a -> a.creditLimit().toPlainString()),
            Map.entry("CASH-CREDIT-LIMIT-N", a -> a.cashCreditLimit().toPlainString()),
            Map.entry("OPEN-DATE", a -> date(a.openDate())),
            Map.entry("EXPIRAION-DATE", a -> date(a.expirationDate())),
            Map.entry("REISSUE-DATE", a -> date(a.reissueDate())),
            Map.entry("CURR-CYC-CREDIT-N", a -> a.currentCycleCredit().toPlainString()),
            Map.entry("CURR-CYC-DEBIT-N", a -> a.currentCycleDebit().toPlainString()),
            Map.entry("GROUP-ID", Account::groupId));

    private static final Map<String, Function<Customer, String>> CUSTOMER = Map.ofEntries(
            Map.entry("CUST-ID", c -> String.format("%09d", c.id())),
            Map.entry("CUST-FIRST-NAME", Customer::firstName),
            Map.entry("CUST-MIDDLE-NAME", Customer::middleName),
            Map.entry("CUST-LAST-NAME", Customer::lastName),
            Map.entry("CUST-ADDR-LINE-1", Customer::addressLine1),
            Map.entry("CUST-ADDR-LINE-2", Customer::addressLine2),
            Map.entry("CUST-ADDR-LINE-3", Customer::addressLine3),
            Map.entry("CUST-ADDR-STATE-CD", Customer::stateCode),
            Map.entry("CUST-ADDR-COUNTRY-CD", Customer::countryCode),
            Map.entry("CUST-ADDR-ZIP", Customer::zip),
            Map.entry("CUST-PHONE-NUM-1", Customer::phone1),
            Map.entry("CUST-PHONE-NUM-2", Customer::phone2),
            Map.entry("CUST-SSN", c -> String.format("%09d", c.ssn())),
            Map.entry("CUST-GOVT-ISSUED-ID", Customer::governmentId),
            Map.entry("CUST-DOB-YYYY-MM-DD", c -> date(c.dateOfBirth())),
            Map.entry("CUST-EFT-ACCOUNT-ID", Customer::eftAccountId),
            Map.entry("CUST-PRI-HOLDER-IND", Customer::primaryCardHolder),
            Map.entry("CUST-FICO-SCORE", c -> String.format("%03d", c.ficoScore())));

    @Override
    public void load(String program, Map<String, Object> commarea, Conversation conversation) {
        if (!program.equals("COACTUPC")) {
            return;
        }
        AccountUpdatePhase phase = AccountUpdatePhase.of(text(commarea.getOrDefault("ACUP-CHANGE-ACTION", "")));
        Map<String, String> old = fields(commarea, "ACUP-OLD-");
        Map<String, String> updated = new HashMap<>(old);
        updated.putAll(fields(commarea, "ACUP-NEW-"));
        conversation.put(switch (phase) {
            case NOT_FETCHED -> AccountUpdateState.notFetched();
            case SHOWN -> AccountUpdateState.shown(account(old), customer(old));
            case INVALID -> AccountUpdateState.invalid(account(old), customer(old));
            case CONFIRM -> AccountUpdateState.confirm(account(old), customer(old), account(updated), customer(updated));
            case SAVED, LOCK_FAILED, SAVE_FAILED -> AccountUpdateState.ended(phase);
        });
    }

    @Override
    public Optional<String> read(String name, Conversation conversation) {
        Optional<AccountUpdateState> state = conversation.get(AccountUpdateState.class);
        if (name.equals("ACUP-CHANGE-ACTION")) {
            return Optional.of(state.map(s -> s.phase().code()).orElse(""));
        }
        if (name.startsWith("ACUP-OLD-")) {
            return Optional.of(value(name.substring("ACUP-OLD-".length()),
                    state.map(AccountUpdateState::account), state.map(AccountUpdateState::customer)));
        }
        if (name.startsWith("ACUP-NEW-")) {
            return Optional.of(value(name.substring("ACUP-NEW-".length()),
                    state.map(AccountUpdateState::newAccount), state.map(AccountUpdateState::newCustomer)));
        }
        return switch (name) {
            // COACTVWC keeps nothing, and COACTUPC holds no account before a fetch.
            case "CDEMO-ACCT-ID" -> state.map(s -> Optional.ofNullable(s.account())
                    .map(a -> String.format("%011d", a.id())).orElse("00000000000"));
            // The customer and card a fetch found live in the state's records; nothing else reads them here.
            case "CDEMO-CUST-ID", "CDEMO-CUST-FNAME", "CDEMO-CUST-MNAME", "CDEMO-CUST-LNAME", "CDEMO-ACCT-STATUS",
                 "CDEMO-CARD-NUM" -> Optional.empty();
            // Screens are templates, so there is no last map to remember.
            case "CDEMO-LAST-MAPSET", "CDEMO-LAST-MAP" -> Optional.empty();
            default -> throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
        };
    }

    private static Map<String, String> fields(Map<String, Object> commarea, String prefix) {
        Map<String, String> fields = new HashMap<>();
        commarea.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                fields.put(key.substring(prefix.length()), text(value));
            }
        });
        return fields;
    }

    /** ACUP-OLD-* has no ACCT-ADDR-ZIP; COACTUPC keeps the stored one, so it is left blank here. */
    private static Account account(Map<String, String> f) {
        return new Account(Long.parseLong(f.getOrDefault("ACCT-ID", "0")), f.getOrDefault("ACTIVE-STATUS", ""),
                amount(f, "CURR-BAL-N"), amount(f, "CREDIT-LIMIT-N"), amount(f, "CASH-CREDIT-LIMIT-N"),
                date(f, "OPEN-DATE"), date(f, "EXPIRAION-DATE"), date(f, "REISSUE-DATE"),
                amount(f, "CURR-CYC-CREDIT-N"), amount(f, "CURR-CYC-DEBIT-N"), "", f.getOrDefault("GROUP-ID", ""));
    }

    private static Customer customer(Map<String, String> f) {
        return new Customer(Integer.parseInt(f.getOrDefault("CUST-ID", "0")), f.getOrDefault("CUST-FIRST-NAME", ""),
                f.getOrDefault("CUST-MIDDLE-NAME", ""), f.getOrDefault("CUST-LAST-NAME", ""),
                f.getOrDefault("CUST-ADDR-LINE-1", ""), f.getOrDefault("CUST-ADDR-LINE-2", ""),
                f.getOrDefault("CUST-ADDR-LINE-3", ""), f.getOrDefault("CUST-ADDR-STATE-CD", ""),
                f.getOrDefault("CUST-ADDR-COUNTRY-CD", ""), f.getOrDefault("CUST-ADDR-ZIP", ""),
                f.getOrDefault("CUST-PHONE-NUM-1", ""), f.getOrDefault("CUST-PHONE-NUM-2", ""),
                Integer.parseInt(f.getOrDefault("CUST-SSN", "0")), f.getOrDefault("CUST-GOVT-ISSUED-ID", ""),
                date(f, "CUST-DOB-YYYY-MM-DD"), f.getOrDefault("CUST-EFT-ACCOUNT-ID", ""),
                f.getOrDefault("CUST-PRI-HOLDER-IND", ""), Integer.parseInt(f.getOrDefault("CUST-FICO-SCORE", "0")));
    }

    private static String value(String field, Optional<Account> account, Optional<Customer> customer) {
        if (ACCOUNT.containsKey(field)) {
            return account.map(ACCOUNT.get(field)).orElse("");
        }
        if (CUSTOMER.containsKey(field)) {
            return customer.map(CUSTOMER.get(field)).orElse("");
        }
        throw new IllegalArgumentException("No Java counterpart for ACUP field " + field);
    }

    private static BigDecimal amount(Map<String, String> f, String field) {
        return new BigDecimal(f.getOrDefault(field, "0")).setScale(2);
    }

    private static LocalDate date(Map<String, String> f, String field) {
        String value = f.getOrDefault(field, "");
        return value.isBlank() ? null : LocalDate.parse(value, YYYYMMDD);
    }

    private static String date(LocalDate date) {
        return date == null ? "" : date.format(YYYYMMDD);
    }
}
