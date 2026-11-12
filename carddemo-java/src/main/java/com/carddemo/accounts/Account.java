package com.carddemo.accounts;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** ACCOUNT-RECORD (CVACT01Y). Amounts are scale 2, as numeric(12, 2) returns them. */
public record Account(long id, String activeStatus, BigDecimal currentBalance, BigDecimal creditLimit,
                      BigDecimal cashCreditLimit, LocalDate openDate, LocalDate expirationDate,
                      LocalDate reissueDate, BigDecimal currentCycleCredit, BigDecimal currentCycleDebit,
                      String addressZip, String groupId) implements Serializable {

    public Account withAddressZip(String zip) {
        return new Account(id, activeStatus, currentBalance, creditLimit, cashCreditLimit, openDate, expirationDate,
                reissueDate, currentCycleCredit, currentCycleDebit, zip, groupId);
    }

    public Account withBalances(BigDecimal balance, BigDecimal cycleCredit, BigDecimal cycleDebit) {
        return new Account(id, activeStatus, balance, creditLimit, cashCreditLimit, openDate, expirationDate,
                reissueDate, cycleCredit, cycleDebit, addressZip, groupId);
    }

    public Account withCurrentBalance(BigDecimal balance) {
        return new Account(id, activeStatus, balance, creditLimit, cashCreditLimit, openDate, expirationDate,
                reissueDate, currentCycleCredit, currentCycleDebit, addressZip, groupId);
    }
}
