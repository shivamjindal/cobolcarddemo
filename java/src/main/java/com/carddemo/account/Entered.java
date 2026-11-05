package com.carddemo.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.CACTUPA;

/**
 * ACUP-NEW-DETAILS as 1100-RECEIVE-MAP moves them in: a field left blank or holding only '*' becomes
 * LOW-VALUES, here null.
 */
record Entered(SymbolicMap<CACTUPA> received) {

    String text(CACTUPA field) {
        String text = received.text(field);
        return text.isEmpty() || text.equals("*") ? null : text;
    }

    /** ACUP-NEW-xxx-N of an amount, which 1100 computes only when the text passes TEST-NUMVAL-C. */
    Optional<BigDecimal> amount(CACTUPA field) {
        String text = text(field);
        return text != null && Intrinsics.testNumvalC(text)
                ? Optional.of(Intrinsics.signed9v2(Intrinsics.numvalC(text)))
                : Optional.empty();
    }

    /** A date whose three parts passed CSUTLDPY: the year as typed, month and day as NUMVAL reads them. */
    LocalDate date(CACTUPA year, CACTUPA month, CACTUPA day) {
        return LocalDate.of(Integer.parseInt(text(year)), Intrinsics.unsigned(Intrinsics.numval(text(month)), 2),
                Intrinsics.unsigned(Intrinsics.numval(text(day)), 2));
    }
}
