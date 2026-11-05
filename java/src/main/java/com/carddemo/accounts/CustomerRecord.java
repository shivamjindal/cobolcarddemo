package com.carddemo.accounts;

import java.time.LocalDate;

/** CUSTOMER-RECORD (CVCUS01Y). Text is rstripped; {@code filler} is null when it is all LOW-VALUES. */
public record CustomerRecord(int id, String firstName, String middleName, String lastName, String addrLine1,
        String addrLine2, String addrLine3, String stateCd, String countryCd, String addrZip, String phoneNum1,
        String phoneNum2, int ssn, String govtIssuedId, LocalDate dob, String eftAccountId,
        String priCardHolderInd, int ficoCreditScore, String filler) {}
