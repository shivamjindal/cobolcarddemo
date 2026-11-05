package com.carddemo.account;

import static com.carddemo.online.maps.CACTUPA.AADDGRP;
import static com.carddemo.online.maps.CACTUPA.ACCTSID;
import static com.carddemo.online.maps.CACTUPA.ACRCYCR;
import static com.carddemo.online.maps.CACTUPA.ACRCYDB;
import static com.carddemo.online.maps.CACTUPA.ACRDLIM;
import static com.carddemo.online.maps.CACTUPA.ACSADL1;
import static com.carddemo.online.maps.CACTUPA.ACSADL2;
import static com.carddemo.online.maps.CACTUPA.ACSCITY;
import static com.carddemo.online.maps.CACTUPA.ACSCTRY;
import static com.carddemo.online.maps.CACTUPA.ACSEFTC;
import static com.carddemo.online.maps.CACTUPA.ACSFNAM;
import static com.carddemo.online.maps.CACTUPA.ACSGOVT;
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
import static com.carddemo.online.maps.CACTUPA.ACSTNUM;
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

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

import com.carddemo.LegacyDefect;
import com.carddemo.accounts.AccountRecord;
import com.carddemo.accounts.CustomerRecord;
import com.carddemo.online.maps.CACTUPA;

/**
 * ACUP-OLD-DETAILS: the account and customer as 9000-READ-ACCT found them. The COMMAREA keeps them so
 * a later turn can tell whether the screen changed anything (1205) and whether someone else changed
 * the records before the save (9700).
 *
 * @param accountId the account number the records were read by
 * @param account   null when ACCTDAT had no record; COACTUPC then carries on with whatever
 *                  ACCOUNT-RECORD held, which this port shows blank and never counts as unchanged
 */
record FetchedDetails(long accountId, Account account, Customer customer) implements Serializable {

    /** A date as COACTUPC splits its 'YYYY-MM-DD' text: (1:4), (6:2) and (9:2). */
    record DateParts(String year, String month, String day) implements Serializable {
        static DateParts of(LocalDate date) {
            String text = date.toString();
            return new DateParts(text.substring(0, 4), text.substring(5, 7), text.substring(8, 10));
        }
    }

    record Account(String activeStatus, BigDecimal currBal, BigDecimal creditLimit, BigDecimal cashCreditLimit,
            BigDecimal currCycCredit, BigDecimal currCycDebit, DateParts opened, DateParts expires,
            DateParts reissued, String groupId) implements Serializable {

        static Account of(AccountRecord record) {
            return new Account(record.activeStatus(), record.currBal(), record.creditLimit(),
                    record.cashCreditLimit(), record.currCycCredit(), record.currCycDebit(),
                    DateParts.of(record.openDate()), DateParts.of(record.expirationDate()),
                    DateParts.of(record.reissueDate()), record.groupId());
        }
    }

    record Customer(int id, String firstName, String middleName, String lastName, String addrLine1,
            String addrLine2, String addrLine3, String stateCd, String countryCd, String addrZip, String phoneNum1,
            String phoneNum2, int ssn, String govtIssuedId, DateParts born, String eftAccountId,
            String priCardHolderInd, int ficoCreditScore) implements Serializable {

        static Customer of(CustomerRecord record) {
            return new Customer(record.id(), record.firstName(), record.middleName(), record.lastName(),
                    record.addrLine1(), record.addrLine2(), record.addrLine3(), record.stateCd(), record.countryCd(),
                    record.addrZip(), record.phoneNum1(), record.phoneNum2(), record.ssn(), record.govtIssuedId(),
                    DateParts.of(record.dob()), record.eftAccountId(), record.priCardHolderInd(),
                    record.ficoCreditScore());
        }

        /** ACUP-OLD-CUST-ID-X. */
        String idText() {
            return "%09d".formatted(id);
        }

        /** ACUP-OLD-CUST-SSN-X. */
        String ssnText() {
            return "%09d".formatted(ssn);
        }

        /** ACUP-OLD-CUST-FICO-SCORE-X. */
        String ficoText() {
            return "%03d".formatted(ficoCreditScore);
        }
    }

    static FetchedDetails of(long accountId, Optional<AccountRecord> account, CustomerRecord customer) {
        return new FetchedDetails(accountId, account.map(Account::of).orElse(null), Customer.of(customer));
    }

    /** COBOL reference modification {@code (start:length)} of a field padded with spaces. */
    static String slice(String field, int start, int length) {
        String padded = String.format("%-" + (start - 1 + length) + "s", field);
        return padded.substring(start - 1, start - 1 + length).stripTrailing();
    }

    /** 1205-COMPARE-OLD-NEW: the screen still holds what was fetched. */
    boolean unchangedIn(Entered entered) {
        return account != null && accountUnchangedIn(entered) && customerUnchangedIn(entered);
    }

    private boolean accountUnchangedIn(Entered entered) {
        return same(entered.text(ACCTSID), "%011d".formatted(accountId))
                && sameIgnoringCase(entered.text(ACSTTUS), account.activeStatus())
                && sameAmount(entered.amount(ACURBAL), account.currBal())
                && sameAmount(entered.amount(ACRDLIM), account.creditLimit())
                && sameAmount(entered.amount(ACSHLIM), account.cashCreditLimit())
                && sameDate(entered, OPNYEAR, OPNMON, OPNDAY, account.opened())
                && sameDate(entered, EXPYEAR, EXPMON, EXPDAY, account.expires())
                && sameDate(entered, RISYEAR, RISMON, RISDAY, account.reissued())
                && sameAmount(entered.amount(ACRCYCR), account.currCycCredit())
                && sameAmount(entered.amount(ACRCYDB), account.currCycDebit())
                && sameIgnoringCase(entered.text(AADDGRP), account.groupId());
    }

    private boolean customerUnchangedIn(Entered entered) {
        String ssn = customer.ssnText();
        return sameIgnoringCase(entered.text(ACSTNUM), customer.idText())
                && sameIgnoringCase(entered.text(ACSFNAM), customer.firstName())
                && sameIgnoringCase(entered.text(ACSMNAM), customer.middleName())
                && sameIgnoringCase(entered.text(ACSLNAM), customer.lastName())
                && sameIgnoringCase(entered.text(ACSADL1), customer.addrLine1())
                && sameIgnoringCase(entered.text(ACSADL2), customer.addrLine2())
                && sameIgnoringCase(entered.text(ACSCITY), customer.addrLine3())
                && sameIgnoringCase(entered.text(ACSSTTE), customer.stateCd())
                && sameIgnoringCase(entered.text(ACSCTRY), customer.countryCd())
                && sameIgnoringCase(entered.text(ACSZIPC), customer.addrZip())
                && samePhone(entered, ACSPH1A, ACSPH1B, ACSPH1C, customer.phoneNum1())
                && samePhone(entered, ACSPH2A, ACSPH2B, ACSPH2C, customer.phoneNum2())
                && same(entered.text(ACTSSN1), slice(ssn, 1, 3))
                && same(entered.text(ACTSSN2), slice(ssn, 4, 2))
                && same(entered.text(ACTSSN3), slice(ssn, 6, 4))
                && sameIgnoringCase(entered.text(ACSGOVT), customer.govtIssuedId())
                && sameDate(entered, DOBYEAR, DOBMON, DOBDAY, customer.born())
                && same(entered.text(ACSEFTC), customer.eftAccountId())
                && sameIgnoringCase(entered.text(ACSPFLG), customer.priCardHolderInd())
                && same(entered.text(ACSTFCO), customer.ficoText());
    }

    /** 9700-CHECK-CHANGE-IN-REC: the records locked for the save still hold what was fetched. */
    boolean stillIn(AccountRecord lockedAccount, CustomerRecord lockedCustomer) {
        Account now = Account.of(lockedAccount);
        Customer locked = Customer.of(lockedCustomer);
        return account != null
                && Objects.equals(now.activeStatus(), account.activeStatus())
                && now.currBal().compareTo(account.currBal()) == 0
                && now.creditLimit().compareTo(account.creditLimit()) == 0
                && now.cashCreditLimit().compareTo(account.cashCreditLimit()) == 0
                && now.currCycCredit().compareTo(account.currCycCredit()) == 0
                && now.currCycDebit().compareTo(account.currCycDebit()) == 0
                && now.opened().equals(account.opened())
                && now.expires().equals(account.expires())
                && now.reissued().equals(account.reissued())
                && equalIgnoringCase(now.groupId(), account.groupId())
                && equalIgnoringCase(locked.firstName(), customer.firstName())
                && equalIgnoringCase(locked.middleName(), customer.middleName())
                && equalIgnoringCase(locked.lastName(), customer.lastName())
                && equalIgnoringCase(locked.addrLine1(), customer.addrLine1())
                && equalIgnoringCase(locked.addrLine2(), customer.addrLine2())
                && equalIgnoringCase(locked.addrLine3(), customer.addrLine3())
                && equalIgnoringCase(locked.stateCd(), customer.stateCd())
                && equalIgnoringCase(locked.countryCd(), customer.countryCd())
                && Objects.equals(locked.addrZip(), customer.addrZip())
                && Objects.equals(locked.phoneNum1(), customer.phoneNum1())
                && Objects.equals(locked.phoneNum2(), customer.phoneNum2())
                && locked.ssn() == customer.ssn()
                && equalIgnoringCase(locked.govtIssuedId(), customer.govtIssuedId())
                && locked.born().equals(customer.born())
                && Objects.equals(locked.eftAccountId(), customer.eftAccountId())
                && Objects.equals(locked.priCardHolderInd(), customer.priCardHolderInd())
                && locked.ficoCreditScore() == customer.ficoCreditScore();
    }

    private static boolean sameDate(Entered entered, CACTUPA year, CACTUPA month, CACTUPA day, DateParts stored) {
        return same(entered.text(year), stored.year())
                && same(entered.text(month), stored.month())
                && same(entered.text(day), stored.day());
    }

    private static boolean samePhone(Entered entered, CACTUPA area, CACTUPA prefix, CACTUPA line, String stored) {
        return same(entered.text(area), slice(stored, 2, 3))
                && same(entered.text(prefix), slice(stored, 6, 3))
                && same(entered.text(line), slice(stored, 10, 4));
    }

    /** NEW-N = OLD-N: an amount that failed TEST-NUMVAL-C left NEW as SPACES, which equals nothing. */
    private static boolean sameAmount(Optional<BigDecimal> entered, BigDecimal stored) {
        return entered.map(amount -> amount.compareTo(stored) == 0).orElse(false);
    }

    private static boolean same(String entered, String stored) {
        return entered == null ? blankMatches(stored) : stored != null && entered.equals(stored.stripTrailing());
    }

    /** {@code UPPER-CASE(TRIM(NEW)) = UPPER-CASE(TRIM(OLD))}. */
    private static boolean sameIgnoringCase(String entered, String stored) {
        return entered == null ? blankMatches(stored) : stored != null && entered.strip().equalsIgnoreCase(stored.strip());
    }

    @LegacyDefect(id = "COACTUPC-blank-never-unchanged", goldens = "account-update-blank-group-always-changed",
            behavior = "1100 turns a blank field into LOW-VALUES and 1205 compares that with the stored SPACES, so a "
                    + "record with any blank field, such as an empty group id, counts as changed on every ENTER",
            fix = "compare a blank field as SPACES")
    private static boolean blankMatches(String stored) {
        return stored == null;
    }

    private static boolean equalIgnoringCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }
}
