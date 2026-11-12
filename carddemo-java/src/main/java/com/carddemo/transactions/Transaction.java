package com.carddemo.transactions;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** TRAN-RECORD (CVTRA05Y). The amount is scale 2, as numeric(11, 2) returns it; the timestamps may be null. */
public record Transaction(String id, String typeCode, int categoryCode, String source, String description,
                          BigDecimal amount, long merchantId, String merchantName, String merchantCity,
                          String merchantZip, String cardNumber, LocalDateTime originated, LocalDateTime processed) {
}
