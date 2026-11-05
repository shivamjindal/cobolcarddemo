package com.carddemo.accounts;

/** CARD-XREF-RECORD (CVACT03Y). */
public record XrefRecord(String cardNum, int custId, long acctId) {}
