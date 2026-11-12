package com.carddemo.accounts;

import java.io.Serializable;
import java.time.LocalDate;

/** CUSTOMER-RECORD (CVCUS01Y). */
public record Customer(int id, String firstName, String middleName, String lastName, String addressLine1,
                       String addressLine2, String addressLine3, String stateCode, String countryCode,
                       String zip, String phone1, String phone2, int ssn, String governmentId,
                       LocalDate dateOfBirth, String eftAccountId, String primaryCardHolder, int ficoScore)
        implements Serializable {
}
