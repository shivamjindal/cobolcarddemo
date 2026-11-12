package com.carddemo.authorizations;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * COPAUS2C: records an authorization reported as fraud, or cleared, in AUTHFRDS. A second report of the same
 * authorization updates the flag and the report date of the row the first one inserted (SQLCODE -803).
 */
@Component
class FraudReports {

    static final DateTimeFormatter REPORT_DATE = DateTimeFormatter.ofPattern("MM/dd/yy");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    FraudReports(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** PA-FRAUD-RPT-DATE of a report made now: FORMATTIME MMDDYY with DATESEP. */
    String today() {
        return REPORT_DATE.format(LocalDate.now(clock));
    }

    /**
     * Inserts or updates the authorization's row with its PA-AUTH-FRAUD, inside the caller's transaction. AUTH_TS
     * is the request's date in the current century at the time of day of the authorization key.
     */
    void record(Authorization a, long accountId, int customerId) {
        AuthorizationRequest r = a.request();
        LocalDate today = LocalDate.now(clock);
        LocalDateTime authorized = LocalDate.of(today.getYear() / 100 * 100 + Integer.parseInt(r.date().substring(0, 2)),
                Integer.parseInt(r.date().substring(2, 4)), Integer.parseInt(r.date().substring(4, 6))).atTime(a.key().time());
        jdbc.update("INSERT INTO authfrds (card_num, auth_ts, auth_type, card_expiry_date, message_type, message_source,"
                        + " auth_id_code, auth_resp_code, auth_resp_reason, processing_code, transaction_amt, approved_amt,"
                        + " merchant_catagory_code, acqr_country_code, pos_entry_mode, merchant_id, merchant_name,"
                        + " merchant_city, merchant_state, merchant_zip, transaction_id, match_status, auth_fraud,"
                        + " fraud_rpt_date, acct_id, cust_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (card_num, auth_ts) DO UPDATE SET auth_fraud = excluded.auth_fraud,"
                        + " fraud_rpt_date = excluded.fraud_rpt_date",
                r.cardNumber(), Timestamp.valueOf(authorized), r.authType(), r.cardExpiryDate(), r.messageType(),
                r.messageSource(), a.authIdCode(), a.responseCode(), a.responseReason(),
                "%06d".formatted(r.processingCode()), r.amount(), a.approvedAmount(), r.merchantCategoryCode(),
                r.acquirerCountryCode(), r.posEntryMode(), r.merchantId(), r.merchantName(), r.merchantCity(),
                r.merchantState(), r.merchantZip(), r.transactionId(), a.matchStatus(), a.fraud(), today, accountId,
                customerId);
    }

    /** WS-FRD-ACT-MSG of a failed insert or update. */
    static String failure(DataAccessException e) {
        int code = e.getCause() instanceof SQLException sql ? sql.getErrorCode() : 0;
        String state = e.getCause() instanceof SQLException sql ? sql.getSQLState() : "";
        return " SYSTEM ERROR DB2: CODE:%+07d, STATE: %s".formatted(code, state);
    }
}
