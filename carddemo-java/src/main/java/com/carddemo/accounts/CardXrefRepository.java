package com.carddemo.accounts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** CCXREF over card_xref; CXACAIX is its alternate index on the account id (card_xref_xref_acct_id_idx). */
@Repository
public class CardXrefRepository {

    private static final RowMapper<CardXref> ROW = (rs, n) -> new CardXref(rs.getString(1), rs.getInt(2), rs.getLong(3));

    private final JdbcTemplate jdbc;

    public CardXrefRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** READ CCXREF by the card number. */
    public Optional<CardXref> find(String cardNumber) {
        return jdbc.query("SELECT xref_card_num, xref_cust_id, xref_acct_id FROM card_xref WHERE xref_card_num = ?",
                ROW, cardNumber).stream().findFirst();
    }

    /** Sequential READ of CCXREF: every record, in card number byte order. */
    public List<CardXref> all() {
        return jdbc.query("SELECT xref_card_num, xref_cust_id, xref_acct_id FROM card_xref"
                + " ORDER BY xref_card_num COLLATE \"C\"", ROW);
    }

    /** READ CXACAIX: the AIX is non-unique, and a keyed read returns its first duplicate, in card order. */
    public Optional<CardXref> findByAccount(long accountId) {
        return jdbc.query("SELECT xref_card_num, xref_cust_id, xref_acct_id FROM card_xref WHERE xref_acct_id = ?"
                + " ORDER BY xref_card_num COLLATE \"C\" LIMIT 1", ROW, accountId).stream().findFirst();
    }
}
