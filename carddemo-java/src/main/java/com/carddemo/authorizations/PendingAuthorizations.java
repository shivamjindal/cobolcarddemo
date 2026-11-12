package com.carddemo.authorizations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * DBPAUTP0 over pending_auth_summaries and pending_auth_details. Details of one summary come in IMS order, which
 * is ascending PA-AUTHORIZATION-KEY: newest first.
 */
@Repository
class PendingAuthorizations {

    private static final String SUMMARY_COLUMNS = "pa_acct_id, pa_cust_id, pa_auth_status, pa_account_status,"
            + " pa_credit_limit, pa_cash_limit, pa_credit_balance, pa_cash_balance, pa_approved_auth_cnt,"
            + " pa_declined_auth_cnt, pa_approved_auth_amt, pa_declined_auth_amt";

    private static final String DETAIL_COLUMNS = "pa_acct_id, pa_auth_date_9c, pa_auth_time_9c, pa_auth_orig_date,"
            + " pa_auth_orig_time, pa_card_num, pa_auth_type, pa_card_expiry_date, pa_message_type,"
            + " pa_message_source, pa_processing_code, pa_transaction_amt, pa_merchant_catagory_code,"
            + " pa_acqr_country_code, pa_pos_entry_mode, pa_merchant_id, pa_merchant_name, pa_merchant_city,"
            + " pa_merchant_state, pa_merchant_zip, pa_transaction_id, pa_auth_id_code, pa_auth_resp_code,"
            + " pa_auth_resp_reason, pa_approved_amt, pa_match_status, pa_auth_fraud, pa_fraud_rpt_date";

    private static final String IMS_ORDER = " ORDER BY pa_auth_date_9c, pa_auth_time_9c";

    private static final RowMapper<AuthorizationSummary> SUMMARY = (rs, n) -> new AuthorizationSummary(rs.getLong(1),
            rs.getInt(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6),
            rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getInt(9), rs.getInt(10), rs.getBigDecimal(11),
            rs.getBigDecimal(12));

    private static final RowMapper<Authorization> DETAIL = PendingAuthorizations::detail;

    private final JdbcTemplate jdbc;

    PendingAuthorizations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** GU PAUTSUM0 WHERE ACCNTID. */
    Optional<AuthorizationSummary> summary(long accountId) {
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM pending_auth_summaries WHERE pa_acct_id = ?",
                SUMMARY, accountId).stream().findFirst();
    }

    /** ISRT of a new summary, or REPL of the one read. */
    void save(AuthorizationSummary s) {
        jdbc.update("INSERT INTO pending_auth_summaries (" + SUMMARY_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (pa_acct_id) DO UPDATE SET pa_cust_id = excluded.pa_cust_id,"
                        + " pa_auth_status = excluded.pa_auth_status, pa_account_status = excluded.pa_account_status,"
                        + " pa_credit_limit = excluded.pa_credit_limit, pa_cash_limit = excluded.pa_cash_limit,"
                        + " pa_credit_balance = excluded.pa_credit_balance, pa_cash_balance = excluded.pa_cash_balance,"
                        + " pa_approved_auth_cnt = excluded.pa_approved_auth_cnt,"
                        + " pa_declined_auth_cnt = excluded.pa_declined_auth_cnt,"
                        + " pa_approved_auth_amt = excluded.pa_approved_auth_amt,"
                        + " pa_declined_auth_amt = excluded.pa_declined_auth_amt",
                s.accountId(), s.customerId(), s.authStatus(), s.accountStatus(), s.creditLimit(), s.cashLimit(),
                s.creditBalance(), s.cashBalance(), s.approvedCount(), s.declinedCount(), s.approvedAmount(),
                s.declinedAmount());
    }

    /** ISRT PAUTDTL1 under its account's summary. */
    void insert(Authorization a) {
        AuthorizationRequest r = a.request();
        jdbc.update("INSERT INTO pending_auth_details (" + DETAIL_COLUMNS + ") VALUES"
                        + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                a.accountId(), a.key().date9c(), a.key().time9c(), r.date(), r.time(), r.cardNumber(), r.authType(),
                r.cardExpiryDate(), r.messageType(), r.messageSource(), r.processingCode(), r.amount(),
                r.merchantCategoryCode(), r.acquirerCountryCode(), r.posEntryMode(), r.merchantId(),
                r.merchantName(), r.merchantCity(), r.merchantState(), r.merchantZip(), r.transactionId(),
                a.authIdCode(), a.responseCode(), a.responseReason(), a.approvedAmount(), a.matchStatus(), a.fraud(),
                a.fraudReportDate());
    }

    /** GU PAUTDTL1 WHERE PAUT9CTS under the account's summary. */
    Optional<Authorization> find(long accountId, AuthorizationKey key) {
        return jdbc.query("SELECT " + DETAIL_COLUMNS + " FROM pending_auth_details WHERE pa_acct_id = ?"
                        + " AND pa_auth_date_9c = ? AND pa_auth_time_9c = ?",
                DETAIL, accountId, key.date9c(), key.time9c()).stream().findFirst();
    }

    /** GNP PAUTDTL1 from the first detail of the account, up to {@code limit} of them. */
    List<Authorization> first(long accountId, int limit) {
        return jdbc.query("SELECT " + DETAIL_COLUMNS + " FROM pending_auth_details WHERE pa_acct_id = ?"
                + IMS_ORDER + " LIMIT ?", DETAIL, accountId, limit);
    }

    /** GNP PAUTDTL1 from {@code key} on, or after it, up to {@code limit} details. */
    List<Authorization> from(long accountId, AuthorizationKey key, boolean inclusive, int limit) {
        return jdbc.query("SELECT " + DETAIL_COLUMNS + " FROM pending_auth_details WHERE pa_acct_id = ?"
                        + " AND (pa_auth_date_9c, pa_auth_time_9c) " + (inclusive ? ">=" : ">") + " (?, ?)"
                        + IMS_ORDER + " LIMIT ?",
                DETAIL, accountId, key.date9c(), key.time9c(), limit);
    }

    /** REPL PAUTDTL1 of the fraud fields, the only ones COPAUS1C changes. */
    void rewriteFraud(Authorization a) {
        jdbc.update("UPDATE pending_auth_details SET pa_auth_fraud = ?, pa_fraud_rpt_date = ? WHERE pa_acct_id = ?"
                        + " AND pa_auth_date_9c = ? AND pa_auth_time_9c = ?",
                a.fraud(), a.fraudReportDate(), a.accountId(), a.key().date9c(), a.key().time9c());
    }

    private static Authorization detail(ResultSet rs, int n) throws SQLException {
        AuthorizationRequest request = new AuthorizationRequest(rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11),
                rs.getBigDecimal(12), rs.getString(13), rs.getString(14), rs.getInt(15), rs.getString(16),
                rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20), rs.getString(21));
        return new Authorization(rs.getLong(1), new AuthorizationKey(rs.getInt(2), rs.getInt(3)), request,
                rs.getString(22), rs.getString(23), rs.getString(24), rs.getBigDecimal(25), rs.getString(26),
                rs.getString(27), rs.getString(28));
    }
}
