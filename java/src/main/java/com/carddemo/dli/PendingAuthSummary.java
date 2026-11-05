package com.carddemo.dli;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import com.carddemo.records.Fields;
import com.carddemo.vsam.Field;
import com.carddemo.vsam.Pic;

/** Root segment PAUTSUM0, copybook CIPAUSMY: one account's pending authorization totals. Key ACCNTID = acctId. */
public record PendingAuthSummary(
        long acctId,
        int custId,
        String authStatus,
        List<String> accountStatus,
        BigDecimal creditLimit,
        BigDecimal cashLimit,
        BigDecimal creditBalance,
        BigDecimal cashBalance,
        int approvedAuthCnt,
        int declinedAuthCnt,
        BigDecimal approvedAuthAmt,
        BigDecimal declinedAuthAmt,
        String filler) implements Segment {

    public static final int LENGTH = 100;

    static final Field ACCT_ID = new Field("PA-ACCT-ID", 0, 6, new Pic.Packed(11, 0, true), "S9(11) COMP-3");
    static final Field CUST_ID = new Field("PA-CUST-ID", 6, 9, new Pic.Zoned(9, 0, false), "9(09)");
    static final Field AUTH_STATUS = new Field("PA-AUTH-STATUS", 15, 1, new Pic.Alnum(1), "X(01)");
    static final List<Field> ACCOUNT_STATUS = List.of(
            new Field("PA-ACCOUNT-STATUS(1)", 16, 2, new Pic.Alnum(2), "X(02)"),
            new Field("PA-ACCOUNT-STATUS(2)", 18, 2, new Pic.Alnum(2), "X(02)"),
            new Field("PA-ACCOUNT-STATUS(3)", 20, 2, new Pic.Alnum(2), "X(02)"),
            new Field("PA-ACCOUNT-STATUS(4)", 22, 2, new Pic.Alnum(2), "X(02)"),
            new Field("PA-ACCOUNT-STATUS(5)", 24, 2, new Pic.Alnum(2), "X(02)"));
    static final Field CREDIT_LIMIT = amount("PA-CREDIT-LIMIT", 26);
    static final Field CASH_LIMIT = amount("PA-CASH-LIMIT", 32);
    static final Field CREDIT_BALANCE = amount("PA-CREDIT-BALANCE", 38);
    static final Field CASH_BALANCE = amount("PA-CASH-BALANCE", 44);
    static final Field APPROVED_AUTH_CNT = count("PA-APPROVED-AUTH-CNT", 50);
    static final Field DECLINED_AUTH_CNT = count("PA-DECLINED-AUTH-CNT", 52);
    static final Field APPROVED_AUTH_AMT = amount("PA-APPROVED-AUTH-AMT", 54);
    static final Field DECLINED_AUTH_AMT = amount("PA-DECLINED-AUTH-AMT", 60);
    static final Field FILLER = new Field("FILLER", 66, 34, new Pic.Alnum(34), "X(34)");

    /** Every field of CIPAUSMY in offset order. */
    public static final List<Field> LAYOUT = List.of(ACCT_ID, CUST_ID, AUTH_STATUS, ACCOUNT_STATUS.get(0),
            ACCOUNT_STATUS.get(1), ACCOUNT_STATUS.get(2), ACCOUNT_STATUS.get(3), ACCOUNT_STATUS.get(4), CREDIT_LIMIT,
            CASH_LIMIT, CREDIT_BALANCE, CASH_BALANCE, APPROVED_AUTH_CNT, DECLINED_AUTH_CNT, APPROVED_AUTH_AMT,
            DECLINED_AUTH_AMT, FILLER);

    public PendingAuthSummary {
        Image.checkInteger(acctId, ACCT_ID);
        if (acctId < 0) {
            throw new IllegalArgumentException("ACCNTID must not be negative: " + acctId);
        }
        Image.checkInteger(custId, CUST_ID);
        Image.checkText(authStatus, AUTH_STATUS);
        if (accountStatus.size() != ACCOUNT_STATUS.size()) {
            throw new IllegalArgumentException("PA-ACCOUNT-STATUS OCCURS 5 TIMES, not " + accountStatus.size());
        }
        accountStatus = List.copyOf(accountStatus);
        for (int i = 0; i < accountStatus.size(); i++) {
            Image.checkText(accountStatus.get(i), ACCOUNT_STATUS.get(i));
        }
        creditLimit = Image.checkNumber(creditLimit, CREDIT_LIMIT);
        cashLimit = Image.checkNumber(cashLimit, CASH_LIMIT);
        creditBalance = Image.checkNumber(creditBalance, CREDIT_BALANCE);
        cashBalance = Image.checkNumber(cashBalance, CASH_BALANCE);
        Image.checkInteger(approvedAuthCnt, APPROVED_AUTH_CNT);
        Image.checkInteger(declinedAuthCnt, DECLINED_AUTH_CNT);
        approvedAuthAmt = Image.checkNumber(approvedAuthAmt, APPROVED_AUTH_AMT);
        declinedAuthAmt = Image.checkNumber(declinedAuthAmt, DECLINED_AUTH_AMT);
        Image.checkText(filler, FILLER);
    }

    /**
     * The segment a program builds for a new account: {@code INITIALIZE PENDING-AUTH-SUMMARY REPLACING NUMERIC
     * DATA BY ZERO}, so text is spaces and every number zero, then the two ids.
     */
    public static PendingAuthSummary initialized(long acctId, int custId) {
        return new PendingAuthSummary(acctId, custId, "", List.of("", "", "", "", ""), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, "");
    }

    public static PendingAuthSummary of(byte[] image) {
        if (image.length != LENGTH) {
            throw new IllegalArgumentException("PAUTSUM0 is " + LENGTH + " bytes, not " + image.length);
        }
        return new PendingAuthSummary(
                Image.number(image, ACCT_ID).longValueExact(),
                Image.number(image, CUST_ID).intValueExact(),
                Image.text(image, AUTH_STATUS),
                ACCOUNT_STATUS.stream().map(field -> Image.text(image, field)).toList(),
                Image.number(image, CREDIT_LIMIT),
                Image.number(image, CASH_LIMIT),
                Image.number(image, CREDIT_BALANCE),
                Image.number(image, CASH_BALANCE),
                Image.number(image, APPROVED_AUTH_CNT).intValueExact(),
                Image.number(image, DECLINED_AUTH_CNT).intValueExact(),
                Image.number(image, APPROVED_AUTH_AMT),
                Image.number(image, DECLINED_AUTH_AMT),
                Image.text(image, FILLER));
    }

    /** The 6-byte ACCNTID key image of an account, as the concatenated key begins. */
    public static byte[] keyImage(long acctId) {
        byte[] key = new byte[ACCT_ID.length()];
        Image.number(key, ACCT_ID, BigDecimal.valueOf(acctId));
        return key;
    }

    /** The ACCNTID in the key image at {@code offset} of {@code image}. */
    public static long keyOf(byte[] image, int offset) {
        return Image.number(Arrays.copyOfRange(image, offset, offset + ACCT_ID.length()), ACCT_ID).longValueExact();
    }

    @Override
    public SegmentType type() {
        return SegmentType.PAUTSUM0;
    }

    @Override
    public byte[] image() {
        byte[] image = Fields.spaces(LENGTH);
        Image.number(image, ACCT_ID, BigDecimal.valueOf(acctId));
        Image.number(image, CUST_ID, BigDecimal.valueOf(custId));
        Image.text(image, AUTH_STATUS, authStatus);
        for (int i = 0; i < ACCOUNT_STATUS.size(); i++) {
            Image.text(image, ACCOUNT_STATUS.get(i), accountStatus.get(i));
        }
        Image.number(image, CREDIT_LIMIT, creditLimit);
        Image.number(image, CASH_LIMIT, cashLimit);
        Image.number(image, CREDIT_BALANCE, creditBalance);
        Image.number(image, CASH_BALANCE, cashBalance);
        Image.number(image, APPROVED_AUTH_CNT, BigDecimal.valueOf(approvedAuthCnt));
        Image.number(image, DECLINED_AUTH_CNT, BigDecimal.valueOf(declinedAuthCnt));
        Image.number(image, APPROVED_AUTH_AMT, approvedAuthAmt);
        Image.number(image, DECLINED_AUTH_AMT, declinedAuthAmt);
        Image.text(image, FILLER, filler);
        return image;
    }

    private static Field amount(String name, int offset) {
        return new Field(name, offset, 6, new Pic.Packed(11, 2, true), "S9(09)V99 COMP-3");
    }

    private static Field count(String name, int offset) {
        return new Field(name, offset, 2, new Pic.Binary(4, 0, true), "S9(04) COMP");
    }
}
