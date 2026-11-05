package com.carddemo.accounts;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ACCOUNT-RECORD (CVACT01Y). Text is rstripped. {@code addrZip}, {@code groupId} and {@code filler}
 * are null when the field is all LOW-VALUES, which COACTUPC's REWRITE leaves there.
 */
public record AccountRecord(long id, String activeStatus, BigDecimal currBal, BigDecimal creditLimit,
        BigDecimal cashCreditLimit, LocalDate openDate, LocalDate expirationDate, LocalDate reissueDate,
        BigDecimal currCycCredit, BigDecimal currCycDebit, String addrZip, String groupId, String filler) {}
