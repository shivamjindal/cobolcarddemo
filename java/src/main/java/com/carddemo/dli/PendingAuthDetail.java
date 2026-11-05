package com.carddemo.dli;

import java.math.BigDecimal;
import java.util.List;

import com.carddemo.records.Fields;
import com.carddemo.vsam.Field;
import com.carddemo.vsam.Pic;

/** Child segment PAUTDTL1, copybook CIPAUDTY: one authorization of the parent account. Key PAUT9CTS = key. */
public record PendingAuthDetail(
        AuthKey key,
        String authOrigDate,
        String authOrigTime,
        String cardNum,
        String authType,
        String cardExpiryDate,
        String messageType,
        String messageSource,
        String authIdCode,
        String authRespCode,
        String authRespReason,
        int processingCode,
        BigDecimal transactionAmt,
        BigDecimal approvedAmt,
        String merchantCatagoryCode,
        String acqrCountryCode,
        int posEntryMode,
        String merchantId,
        String merchantName,
        String merchantCity,
        String merchantState,
        String merchantZip,
        String transactionId,
        String matchStatus,
        String authFraud,
        String fraudRptDate,
        String filler) implements Segment {

    public static final int LENGTH = 200;

    static final Field AUTH_DATE_9C = new Field("PA-AUTH-DATE-9C", 0, 3, new Pic.Packed(5, 0, true), "S9(05) COMP-3");
    static final Field AUTH_TIME_9C = new Field("PA-AUTH-TIME-9C", 3, 5, new Pic.Packed(9, 0, true), "S9(09) COMP-3");
    static final Field AUTH_ORIG_DATE = text("PA-AUTH-ORIG-DATE", 8, 6);
    static final Field AUTH_ORIG_TIME = text("PA-AUTH-ORIG-TIME", 14, 6);
    static final Field CARD_NUM = text("PA-CARD-NUM", 20, 16);
    static final Field AUTH_TYPE = text("PA-AUTH-TYPE", 36, 4);
    static final Field CARD_EXPIRY_DATE = text("PA-CARD-EXPIRY-DATE", 40, 4);
    static final Field MESSAGE_TYPE = text("PA-MESSAGE-TYPE", 44, 6);
    static final Field MESSAGE_SOURCE = text("PA-MESSAGE-SOURCE", 50, 6);
    static final Field AUTH_ID_CODE = text("PA-AUTH-ID-CODE", 56, 6);
    static final Field AUTH_RESP_CODE = text("PA-AUTH-RESP-CODE", 62, 2);
    static final Field AUTH_RESP_REASON = text("PA-AUTH-RESP-REASON", 64, 4);
    static final Field PROCESSING_CODE = new Field("PA-PROCESSING-CODE", 68, 6, new Pic.Zoned(6, 0, false), "9(06)");
    static final Field TRANSACTION_AMT = amount("PA-TRANSACTION-AMT", 74);
    static final Field APPROVED_AMT = amount("PA-APPROVED-AMT", 81);
    static final Field MERCHANT_CATAGORY_CODE = text("PA-MERCHANT-CATAGORY-CODE", 88, 4);
    static final Field ACQR_COUNTRY_CODE = text("PA-ACQR-COUNTRY-CODE", 92, 3);
    static final Field POS_ENTRY_MODE = new Field("PA-POS-ENTRY-MODE", 95, 2, new Pic.Zoned(2, 0, false), "9(02)");
    static final Field MERCHANT_ID = text("PA-MERCHANT-ID", 97, 15);
    static final Field MERCHANT_NAME = text("PA-MERCHANT-NAME", 112, 22);
    static final Field MERCHANT_CITY = text("PA-MERCHANT-CITY", 134, 13);
    static final Field MERCHANT_STATE = text("PA-MERCHANT-STATE", 147, 2);
    static final Field MERCHANT_ZIP = text("PA-MERCHANT-ZIP", 149, 9);
    static final Field TRANSACTION_ID = text("PA-TRANSACTION-ID", 158, 15);
    static final Field MATCH_STATUS = text("PA-MATCH-STATUS", 173, 1);
    static final Field AUTH_FRAUD = text("PA-AUTH-FRAUD", 174, 1);
    static final Field FRAUD_RPT_DATE = text("PA-FRAUD-RPT-DATE", 175, 8);
    static final Field FILLER = text("FILLER", 183, 17);

    /** Every field of CIPAUDTY in offset order. */
    public static final List<Field> LAYOUT = List.of(AUTH_DATE_9C, AUTH_TIME_9C, AUTH_ORIG_DATE, AUTH_ORIG_TIME,
            CARD_NUM, AUTH_TYPE, CARD_EXPIRY_DATE, MESSAGE_TYPE, MESSAGE_SOURCE, AUTH_ID_CODE, AUTH_RESP_CODE,
            AUTH_RESP_REASON, PROCESSING_CODE, TRANSACTION_AMT, APPROVED_AMT, MERCHANT_CATAGORY_CODE,
            ACQR_COUNTRY_CODE, POS_ENTRY_MODE, MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_STATE,
            MERCHANT_ZIP, TRANSACTION_ID, MATCH_STATUS, AUTH_FRAUD, FRAUD_RPT_DATE, FILLER);

    public PendingAuthDetail {
        if (key == null) {
            throw new IllegalArgumentException("PAUTDTL1 needs its key");
        }
        Image.checkText(authOrigDate, AUTH_ORIG_DATE);
        Image.checkText(authOrigTime, AUTH_ORIG_TIME);
        Image.checkText(cardNum, CARD_NUM);
        Image.checkText(authType, AUTH_TYPE);
        Image.checkText(cardExpiryDate, CARD_EXPIRY_DATE);
        Image.checkText(messageType, MESSAGE_TYPE);
        Image.checkText(messageSource, MESSAGE_SOURCE);
        Image.checkText(authIdCode, AUTH_ID_CODE);
        Image.checkText(authRespCode, AUTH_RESP_CODE);
        Image.checkText(authRespReason, AUTH_RESP_REASON);
        Image.checkInteger(processingCode, PROCESSING_CODE);
        transactionAmt = Image.checkNumber(transactionAmt, TRANSACTION_AMT);
        approvedAmt = Image.checkNumber(approvedAmt, APPROVED_AMT);
        Image.checkText(merchantCatagoryCode, MERCHANT_CATAGORY_CODE);
        Image.checkText(acqrCountryCode, ACQR_COUNTRY_CODE);
        Image.checkInteger(posEntryMode, POS_ENTRY_MODE);
        Image.checkText(merchantId, MERCHANT_ID);
        Image.checkText(merchantName, MERCHANT_NAME);
        Image.checkText(merchantCity, MERCHANT_CITY);
        Image.checkText(merchantState, MERCHANT_STATE);
        Image.checkText(merchantZip, MERCHANT_ZIP);
        Image.checkText(transactionId, TRANSACTION_ID);
        Image.checkText(matchStatus, MATCH_STATUS);
        Image.checkText(authFraud, AUTH_FRAUD);
        Image.checkText(fraudRptDate, FRAUD_RPT_DATE);
        Image.checkText(filler, FILLER);
    }

    public static PendingAuthDetail of(byte[] image) {
        if (image.length != LENGTH) {
            throw new IllegalArgumentException("PAUTDTL1 is " + LENGTH + " bytes, not " + image.length);
        }
        return new PendingAuthDetail(
                keyOf(image, 0),
                Image.text(image, AUTH_ORIG_DATE),
                Image.text(image, AUTH_ORIG_TIME),
                Image.text(image, CARD_NUM),
                Image.text(image, AUTH_TYPE),
                Image.text(image, CARD_EXPIRY_DATE),
                Image.text(image, MESSAGE_TYPE),
                Image.text(image, MESSAGE_SOURCE),
                Image.text(image, AUTH_ID_CODE),
                Image.text(image, AUTH_RESP_CODE),
                Image.text(image, AUTH_RESP_REASON),
                Image.number(image, PROCESSING_CODE).intValueExact(),
                Image.number(image, TRANSACTION_AMT),
                Image.number(image, APPROVED_AMT),
                Image.text(image, MERCHANT_CATAGORY_CODE),
                Image.text(image, ACQR_COUNTRY_CODE),
                Image.number(image, POS_ENTRY_MODE).intValueExact(),
                Image.text(image, MERCHANT_ID),
                Image.text(image, MERCHANT_NAME),
                Image.text(image, MERCHANT_CITY),
                Image.text(image, MERCHANT_STATE),
                Image.text(image, MERCHANT_ZIP),
                Image.text(image, TRANSACTION_ID),
                Image.text(image, MATCH_STATUS),
                Image.text(image, AUTH_FRAUD),
                Image.text(image, FRAUD_RPT_DATE),
                Image.text(image, FILLER));
    }

    /** The PAUT9CTS key whose 8-byte image starts at {@code offset}. */
    public static AuthKey keyOf(byte[] image, int offset) {
        byte[] key = new byte[AUTH_DATE_9C.length() + AUTH_TIME_9C.length()];
        System.arraycopy(image, offset, key, 0, key.length);
        return new AuthKey(Image.number(key, AUTH_DATE_9C).intValueExact(), Image.number(key, AUTH_TIME_9C).intValueExact());
    }

    @Override
    public SegmentType type() {
        return SegmentType.PAUTDTL1;
    }

    @Override
    public byte[] image() {
        byte[] image = Fields.spaces(LENGTH);
        Image.number(image, AUTH_DATE_9C, BigDecimal.valueOf(key.authDate9c()));
        Image.number(image, AUTH_TIME_9C, BigDecimal.valueOf(key.authTime9c()));
        Image.text(image, AUTH_ORIG_DATE, authOrigDate);
        Image.text(image, AUTH_ORIG_TIME, authOrigTime);
        Image.text(image, CARD_NUM, cardNum);
        Image.text(image, AUTH_TYPE, authType);
        Image.text(image, CARD_EXPIRY_DATE, cardExpiryDate);
        Image.text(image, MESSAGE_TYPE, messageType);
        Image.text(image, MESSAGE_SOURCE, messageSource);
        Image.text(image, AUTH_ID_CODE, authIdCode);
        Image.text(image, AUTH_RESP_CODE, authRespCode);
        Image.text(image, AUTH_RESP_REASON, authRespReason);
        Image.number(image, PROCESSING_CODE, BigDecimal.valueOf(processingCode));
        Image.number(image, TRANSACTION_AMT, transactionAmt);
        Image.number(image, APPROVED_AMT, approvedAmt);
        Image.text(image, MERCHANT_CATAGORY_CODE, merchantCatagoryCode);
        Image.text(image, ACQR_COUNTRY_CODE, acqrCountryCode);
        Image.number(image, POS_ENTRY_MODE, BigDecimal.valueOf(posEntryMode));
        Image.text(image, MERCHANT_ID, merchantId);
        Image.text(image, MERCHANT_NAME, merchantName);
        Image.text(image, MERCHANT_CITY, merchantCity);
        Image.text(image, MERCHANT_STATE, merchantState);
        Image.text(image, MERCHANT_ZIP, merchantZip);
        Image.text(image, TRANSACTION_ID, transactionId);
        Image.text(image, MATCH_STATUS, matchStatus);
        Image.text(image, AUTH_FRAUD, authFraud);
        Image.text(image, FRAUD_RPT_DATE, fraudRptDate);
        Image.text(image, FILLER, filler);
        return image;
    }

    private static Field text(String name, int offset, int length) {
        return new Field(name, offset, length, new Pic.Alnum(length), "X(" + (length < 10 ? "0" : "") + length + ")");
    }

    private static Field amount(String name, int offset) {
        return new Field(name, offset, 7, new Pic.Packed(12, 2, true), "S9(10)V99 COMP-3");
    }
}
