package com.carddemo.transactions;

import java.math.BigDecimal;

/** TRAN-CAT-BAL-RECORD (CVTRA01Y): an account's running balance for one transaction type and category. */
public record TranCatBalance(long accountId, String typeCode, int categoryCode, BigDecimal balance) {

    /** FD-TRAN-CAT-KEY as the 17 characters POSTTRAN displays. */
    public String key() {
        return "%011d%s%04d".formatted(accountId, typeCode, categoryCode);
    }

    public TranCatBalance plus(BigDecimal amount) {
        return new TranCatBalance(accountId, typeCode, categoryCode, balance.add(amount));
    }
}
