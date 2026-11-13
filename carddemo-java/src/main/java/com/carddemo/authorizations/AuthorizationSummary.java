package com.carddemo.authorizations;

import java.math.BigDecimal;

/** A PAUTSUM0 segment (CIPAUSMY): one account's limits and the totals of its pending authorizations. */
public record AuthorizationSummary(long accountId, int customerId, String authStatus, String accountStatus,
                                   BigDecimal creditLimit, BigDecimal cashLimit, BigDecimal creditBalance,
                                   BigDecimal cashBalance, int approvedCount, int declinedCount,
                                   BigDecimal approvedAmount, BigDecimal declinedAmount) {

    /** INITIALIZE PENDING-AUTH-SUMMARY REPLACING NUMERIC DATA BY ZERO, with the account and customer. */
    static AuthorizationSummary empty(long accountId, int customerId) {
        BigDecimal zero = new BigDecimal("0.00");
        return new AuthorizationSummary(accountId, customerId, "", "", zero, zero, zero, zero, 0, 0, zero, zero);
    }

    BigDecimal available() {
        return creditLimit.subtract(creditBalance);
    }

    AuthorizationSummary withLimits(BigDecimal credit, BigDecimal cash) {
        return new AuthorizationSummary(accountId, customerId, authStatus, accountStatus, credit, cash,
                creditBalance, cashBalance, approvedCount, declinedCount, approvedAmount, declinedAmount);
    }

    AuthorizationSummary approved(BigDecimal amount) {
        return new AuthorizationSummary(accountId, customerId, authStatus, accountStatus, creditLimit, cashLimit,
                creditBalance.add(amount), new BigDecimal("0.00"), approvedCount + 1, declinedCount,
                approvedAmount.add(amount), declinedAmount);
    }

    AuthorizationSummary declined(BigDecimal amount) {
        return new AuthorizationSummary(accountId, customerId, authStatus, accountStatus, creditLimit, cashLimit,
                creditBalance, cashBalance, approvedCount, declinedCount + 1, approvedAmount,
                declinedAmount.add(amount));
    }
}
