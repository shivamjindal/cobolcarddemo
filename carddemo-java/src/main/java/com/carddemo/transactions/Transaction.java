package com.carddemo.transactions;

import com.carddemo.vsam.Table;
import com.carddemo.vsam.VsamDataset;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** TRAN-RECORD (CVTRA05Y). The amount is scale 2, as numeric(11, 2) returns it; the timestamps may be null. */
public record Transaction(String id, String typeCode, int categoryCode, String source, String description,
                          BigDecimal amount, long merchantId, String merchantName, String merchantCity,
                          String merchantZip, String cardNumber, LocalDateTime originated, LocalDateTime processed) {

    private static final Table TRANSACT = Table.of(VsamDataset.TRANSACTIONS);

    /** A 350-byte TRAN-RECORD (or DALYTRAN-RECORD, which has the same layout). */
    public static Transaction decode(String record) {
        Object[] r = TRANSACT.decode(record);
        return new Transaction((String) r[0], (String) r[1], (Integer) r[2], (String) r[3], (String) r[4],
                (BigDecimal) r[5], ((Number) r[6]).longValue(), (String) r[7], (String) r[8], (String) r[9],
                (String) r[10], (LocalDateTime) r[11], (LocalDateTime) r[12]);
    }

    public String encode() {
        return TRANSACT.encode(new Object[] {id, typeCode, categoryCode, source, description, amount, merchantId,
                merchantName, merchantCity, merchantZip, cardNumber, originated, processed});
    }

    public Transaction processedAt(LocalDateTime timestamp) {
        return new Transaction(id, typeCode, categoryCode, source, description, amount, merchantId, merchantName,
                merchantCity, merchantZip, cardNumber, originated, timestamp);
    }
}
