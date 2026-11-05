package com.carddemo.accounts;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** CCXREF, table {@code card_xref}, read by card number or through its alternate index CXACAIX on the account id. */
@Component
public final class CardXrefStore {

    private final KeyedFile<XrefRecord> file;

    public CardXrefStore(JdbcClient jdbc) {
        this.file = new KeyedFile<>(jdbc, "CCXREF", "card_xref", "xref_card_num, xref_cust_id, xref_acct_id",
                "xref_card_num", CardXrefStore::row);
    }

    /** {@code READ DATASET(CCXREF) RIDFLD(cardNum)}, the card number without trailing blanks. */
    public Read<XrefRecord> find(String cardNum) {
        return file.read(cardNum);
    }

    /** {@code READ DATASET(CXACAIX) RIDFLD(acctId)}. */
    public Read<XrefRecord> findByAccount(long acctId) {
        return file.readByAlternateKey("CXACAIX", "xref_acct_id", acctId);
    }

    private static XrefRecord row(ResultSet rs, int rowNum) throws SQLException {
        return new XrefRecord(rs.getString("xref_card_num"), rs.getInt("xref_cust_id"), rs.getLong("xref_acct_id"));
    }
}
