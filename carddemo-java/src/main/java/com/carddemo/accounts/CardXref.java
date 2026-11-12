package com.carddemo.accounts;

/** CARD-XREF-RECORD (CVACT03Y): which customer and account a card belongs to. */
public record CardXref(String cardNumber, int customerId, long accountId) {
}
