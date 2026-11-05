package com.carddemo.dli;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import com.carddemo.vsam.Field;

/**
 * DBPAUTP0's segments as rows of {@code pending_auth_summaries} and {@code pending_auth_details}. A detail row
 * carries its parent's ACCNTID, which the segment's I/O area does not.
 */
public final class SegmentTables {

    public static final String SUMMARIES = "pending_auth_summaries";
    public static final String DETAILS = "pending_auth_details";

    public static final String SUMMARY_COLUMNS = "acct_id, cust_id, auth_status, account_status_1, account_status_2, "
            + "account_status_3, account_status_4, account_status_5, credit_limit, cash_limit, credit_balance, "
            + "cash_balance, approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt, filler";

    public static final String DETAIL_COLUMNS = "acct_id, auth_date_9c, auth_time_9c, auth_orig_date, auth_orig_time, "
            + "card_num, auth_type, card_expiry_date, message_type, message_source, auth_id_code, auth_resp_code, "
            + "auth_resp_reason, processing_code, transaction_amt, approved_amt, merchant_catagory_code, "
            + "acqr_country_code, pos_entry_mode, merchant_id, merchant_name, merchant_city, merchant_state, "
            + "merchant_zip, transaction_id, match_status, auth_fraud, fraud_rpt_date, filler";

    public static final String INSERT_SUMMARY = insert(SUMMARIES, SUMMARY_COLUMNS);
    public static final String INSERT_DETAIL = insert(DETAILS, DETAIL_COLUMNS);

    /** A PAUTDTL1 occurrence with the root it hangs under. */
    public record Child(long acctId, PendingAuthDetail detail) {}

    private SegmentTables() {}

    public static PendingAuthSummary summary(ResultSet row) throws SQLException {
        return new PendingAuthSummary(
                row.getLong("acct_id"),
                row.getInt("cust_id"),
                text(row, "auth_status", PendingAuthSummary.AUTH_STATUS),
                List.of(text(row, "account_status_1", PendingAuthSummary.ACCOUNT_STATUS.get(0)),
                        text(row, "account_status_2", PendingAuthSummary.ACCOUNT_STATUS.get(1)),
                        text(row, "account_status_3", PendingAuthSummary.ACCOUNT_STATUS.get(2)),
                        text(row, "account_status_4", PendingAuthSummary.ACCOUNT_STATUS.get(3)),
                        text(row, "account_status_5", PendingAuthSummary.ACCOUNT_STATUS.get(4))),
                row.getBigDecimal("credit_limit"),
                row.getBigDecimal("cash_limit"),
                row.getBigDecimal("credit_balance"),
                row.getBigDecimal("cash_balance"),
                row.getInt("approved_auth_cnt"),
                row.getInt("declined_auth_cnt"),
                row.getBigDecimal("approved_auth_amt"),
                row.getBigDecimal("declined_auth_amt"),
                text(row, "filler", PendingAuthSummary.FILLER));
    }

    public static Child child(ResultSet row) throws SQLException {
        return new Child(row.getLong("acct_id"), new PendingAuthDetail(
                new AuthKey(row.getInt("auth_date_9c"), row.getInt("auth_time_9c")),
                text(row, "auth_orig_date", PendingAuthDetail.AUTH_ORIG_DATE),
                text(row, "auth_orig_time", PendingAuthDetail.AUTH_ORIG_TIME),
                text(row, "card_num", PendingAuthDetail.CARD_NUM),
                text(row, "auth_type", PendingAuthDetail.AUTH_TYPE),
                text(row, "card_expiry_date", PendingAuthDetail.CARD_EXPIRY_DATE),
                text(row, "message_type", PendingAuthDetail.MESSAGE_TYPE),
                text(row, "message_source", PendingAuthDetail.MESSAGE_SOURCE),
                text(row, "auth_id_code", PendingAuthDetail.AUTH_ID_CODE),
                text(row, "auth_resp_code", PendingAuthDetail.AUTH_RESP_CODE),
                text(row, "auth_resp_reason", PendingAuthDetail.AUTH_RESP_REASON),
                row.getInt("processing_code"),
                row.getBigDecimal("transaction_amt"),
                row.getBigDecimal("approved_amt"),
                text(row, "merchant_catagory_code", PendingAuthDetail.MERCHANT_CATAGORY_CODE),
                text(row, "acqr_country_code", PendingAuthDetail.ACQR_COUNTRY_CODE),
                row.getInt("pos_entry_mode"),
                text(row, "merchant_id", PendingAuthDetail.MERCHANT_ID),
                text(row, "merchant_name", PendingAuthDetail.MERCHANT_NAME),
                text(row, "merchant_city", PendingAuthDetail.MERCHANT_CITY),
                text(row, "merchant_state", PendingAuthDetail.MERCHANT_STATE),
                text(row, "merchant_zip", PendingAuthDetail.MERCHANT_ZIP),
                text(row, "transaction_id", PendingAuthDetail.TRANSACTION_ID),
                text(row, "match_status", PendingAuthDetail.MATCH_STATUS),
                text(row, "auth_fraud", PendingAuthDetail.AUTH_FRAUD),
                text(row, "fraud_rpt_date", PendingAuthDetail.FRAUD_RPT_DATE),
                text(row, "filler", PendingAuthDetail.FILLER)));
    }

    /** Values in {@link #SUMMARY_COLUMNS} order. */
    public static Object[] values(PendingAuthSummary s) {
        List<String> status = s.accountStatus();
        return new Object[] {s.acctId(), s.custId(), stored(s.authStatus()), stored(status.get(0)),
                stored(status.get(1)), stored(status.get(2)), stored(status.get(3)), stored(status.get(4)),
                s.creditLimit(), s.cashLimit(), s.creditBalance(), s.cashBalance(), s.approvedAuthCnt(),
                s.declinedAuthCnt(), s.approvedAuthAmt(), s.declinedAuthAmt(), stored(s.filler())};
    }

    /** Values in {@link #DETAIL_COLUMNS} order. */
    public static Object[] values(Child child) {
        PendingAuthDetail d = child.detail();
        return new Object[] {child.acctId(), d.key().authDate9c(), d.key().authTime9c(), stored(d.authOrigDate()),
                stored(d.authOrigTime()), stored(d.cardNum()), stored(d.authType()), stored(d.cardExpiryDate()),
                stored(d.messageType()), stored(d.messageSource()), stored(d.authIdCode()), stored(d.authRespCode()),
                stored(d.authRespReason()), d.processingCode(), d.transactionAmt(), d.approvedAmt(),
                stored(d.merchantCatagoryCode()), stored(d.acqrCountryCode()), d.posEntryMode(),
                stored(d.merchantId()), stored(d.merchantName()), stored(d.merchantCity()), stored(d.merchantState()),
                stored(d.merchantZip()), stored(d.transactionId()), stored(d.matchStatus()), stored(d.authFraud()),
                stored(d.fraudRptDate()), stored(d.filler())};
    }

    private static String text(ResultSet row, String column, Field field) throws SQLException {
        String value = row.getString(column);
        return value == null ? Image.lowValues(field) : value;
    }

    /** Postgres text cannot hold NUL, and a segment's text admits NUL only as a field of all LOW-VALUES. */
    private static String stored(String text) {
        return !text.isEmpty() && text.chars().allMatch(c -> c == 0) ? null : text;
    }

    private static String insert(String table, String columns) {
        String placeholders = String.join(", ", Collections.nCopies(columns.split(", ").length, "?"));
        return "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")";
    }
}
