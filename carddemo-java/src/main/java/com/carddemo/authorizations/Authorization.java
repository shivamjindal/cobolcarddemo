package com.carddemo.authorizations;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * A PAUTDTL1 segment (CIPAUDTY) under its account's summary: the request, the decision COPAUA0C made on it
 * (PA-AUTH-ID-CODE, response code and reason, approved amount), and its match and fraud status.
 */
public record Authorization(long accountId, AuthorizationKey key, AuthorizationRequest request, String authIdCode,
                            String responseCode, String responseReason, BigDecimal approvedAmount,
                            String matchStatus, String fraud, String fraudReportDate) implements Serializable {

    static final String APPROVED = "00";
    static final String FRAUD_CONFIRMED = "F";
    static final String FRAUD_REMOVED = "R";

    boolean approved() {
        return APPROVED.equals(responseCode);
    }

    Authorization withFraud(String status, String reportDate) {
        return new Authorization(accountId, key, request, authIdCode, responseCode, responseReason, approvedAmount,
                matchStatus, status, reportDate);
    }
}
