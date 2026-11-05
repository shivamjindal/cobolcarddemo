package com.carddemo.transactions;

import java.math.BigDecimal;

/**
 * TRAN-RECORD (CVTRA05Y). Text is rstripped. {@code cardNum} is null when the field is all LOW-VALUES,
 * which COBIL00C writes for an account with no card cross-reference. {@code origTs} and {@code procTs}
 * are the 26-character fields as written: COTRN02C writes a bare {@code yyyy-MM-dd}, COBIL00C a full
 * {@code yyyy-MM-dd HH:mm:ss.SSSSSS}.
 */
public record TransactionRecord(String id, String typeCd, int catCd, String source, String desc, BigDecimal amount,
        int merchantId, String merchantName, String merchantCity, String merchantZip, String cardNum,
        String origTs, String procTs) {}
