package com.carddemo.authorization;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.carddemo.dli.PendingAuthDetail;

/**
 * COPAUS2C, which COPAUS1C LINKs to: records an authorization reported as fraud, or the removal of that report,
 * in Db2 table AUTHFRDS. The row is keyed by card and AUTH-TS; a second report of the same authorization finds
 * the row there (SQLCODE -803) and updates its flag and date instead. Its work belongs to the caller's unit of
 * work, so the caller's SYNCPOINT ROLLBACK undoes it.
 */
@Component
public final class FraudRegister {

    private static final DateTimeFormatter MMDDYY = DateTimeFormatter.ofPattern("MM/dd/yy");

    private final JdbcClient jdbc;
    private final Clock clock;

    FraudRegister(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * WS-FRAUD-STATUS-RECORD after the LINK.
     *
     * @param detail the COMMAREA's copy of the authorization, its PA-FRAUD-RPT-DATE now today as MM/DD/YY
     */
    record Tagged(boolean success, String message, PendingAuthDetail detail) {}

    /**
     * @param action 'F' to report the authorization as fraud, 'R' to remove the report
     */
    Tagged tag(long acctId, int custId, PendingAuthDetail detail, String action) {
        LocalDate today = LocalDate.now(clock);
        PendingAuthDetail dated = withFraudRptDate(detail, today.format(MMDDYY));
        LocalDateTime authTs = authTs(detail);
        int inserted = jdbc.sql("""
                INSERT INTO authfrds (card_num, auth_ts, auth_type, card_expiry_date, message_type, message_source,
                    auth_id_code, auth_resp_code, auth_resp_reason, processing_code, transaction_amt, approved_amt,
                    merchant_catagory_code, acqr_country_code, pos_entry_mode, merchant_id, merchant_name,
                    merchant_city, merchant_state, merchant_zip, transaction_id, match_status, auth_fraud,
                    fraud_rpt_date, acct_id, cust_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (card_num, auth_ts) DO NOTHING""")
                .params(text(detail.cardNum()), authTs, text(detail.authType()), text(detail.cardExpiryDate()),
                        text(detail.messageType()), text(detail.messageSource()), text(detail.authIdCode()),
                        text(detail.authRespCode()), text(detail.authRespReason()),
                        "%06d".formatted(detail.processingCode()), detail.transactionAmt(), detail.approvedAmt(),
                        text(detail.merchantCatagoryCode()), text(detail.acqrCountryCode()), detail.posEntryMode(),
                        text(detail.merchantId()), text(detail.merchantName()), text(detail.merchantCity()),
                        text(detail.merchantState()), text(detail.merchantZip()), text(detail.transactionId()),
                        text(detail.matchStatus()), action, today, acctId, custId)
                .update();
        if (inserted == 1) {
            return new Tagged(true, "ADD SUCCESS", dated);
        }
        jdbc.sql("UPDATE authfrds SET auth_fraud = ?, fraud_rpt_date = ? WHERE card_num = ? AND auth_ts = ?")
                .params(action, today, text(detail.cardNum()), authTs)
                .update();
        return new Tagged(true, "UPDT SUCCESS", dated);
    }

    /**
     * {@code TIMESTAMP_FORMAT(WS-AUTH-TS, 'YY-MM-DD HH24.MI.SSNNNNNN')}: the date is PA-AUTH-ORIG-DATE, but the time
     * is {@code 999999999 - PA-AUTH-TIME-9C}, the time the authorization was stored, not PA-AUTH-ORIG-TIME.
     */
    static LocalDateTime authTs(PendingAuthDetail detail) {
        String date = AuthPicture.pad(detail.authOrigDate(), 6);
        String time = "%09d".formatted(999_999_999 - detail.key().authTime9c());
        return LocalDateTime.of(2000 + Integer.parseInt(date.substring(0, 2)), Integer.parseInt(date.substring(2, 4)),
                Integer.parseInt(date.substring(4, 6)), Integer.parseInt(time.substring(0, 2)),
                Integer.parseInt(time.substring(2, 4)), Integer.parseInt(time.substring(4, 6)),
                Integer.parseInt(time.substring(6, 9)) * 1_000_000);
    }

    private static String text(String value) {
        return Objects.toString(value, "").stripTrailing();
    }

    static PendingAuthDetail withFraud(PendingAuthDetail d, String authFraud) {
        return new PendingAuthDetail(d.key(), d.authOrigDate(), d.authOrigTime(), d.cardNum(), d.authType(),
                d.cardExpiryDate(), d.messageType(), d.messageSource(), d.authIdCode(), d.authRespCode(),
                d.authRespReason(), d.processingCode(), d.transactionAmt(), d.approvedAmt(), d.merchantCatagoryCode(),
                d.acqrCountryCode(), d.posEntryMode(), d.merchantId(), d.merchantName(), d.merchantCity(),
                d.merchantState(), d.merchantZip(), d.transactionId(), d.matchStatus(), authFraud, d.fraudRptDate(),
                d.filler());
    }

    private static PendingAuthDetail withFraudRptDate(PendingAuthDetail d, String fraudRptDate) {
        return new PendingAuthDetail(d.key(), d.authOrigDate(), d.authOrigTime(), d.cardNum(), d.authType(),
                d.cardExpiryDate(), d.messageType(), d.messageSource(), d.authIdCode(), d.authRespCode(),
                d.authRespReason(), d.processingCode(), d.transactionAmt(), d.approvedAmt(), d.merchantCatagoryCode(),
                d.acqrCountryCode(), d.posEntryMode(), d.merchantId(), d.merchantName(), d.merchantCity(),
                d.merchantState(), d.merchantZip(), d.transactionId(), d.matchStatus(), d.authFraud(), fraudRptDate,
                d.filler());
    }
}
