package com.carddemo.accounts;

import java.time.LocalDate;

/**
 * CARD-RECORD (CVACT02Y). Text is rstripped. {@code cvv} is null when CARD-CVV-CD holds spaces, which
 * COCRDUPC's REWRITE leaves there; {@code filler} is null when it is all LOW-VALUES.
 */
public record CardRecord(String num, long acctId, Integer cvv, String embossedName, LocalDate expirationDate,
        String activeStatus, String filler) {}
