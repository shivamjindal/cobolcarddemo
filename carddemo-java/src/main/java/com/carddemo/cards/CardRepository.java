package com.carddemo.cards;

import com.carddemo.vsam.RecordLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/** CARDDAT over the cards table; locks per {@link RecordLock}, after the account and customer. */
@Repository
public class CardRepository {

    static final String COLUMNS = "card_num, card_acct_id, card_cvv_cd, card_embossed_name, card_expiraion_date,"
            + " card_active_status";

    static final RowMapper<Card> ROW = (rs, n) -> new Card(rs.getString(1), rs.getLong(2), rs.getInt(3),
            rs.getString(4), rs.getObject(5, LocalDate.class), rs.getString(6));

    private final JdbcTemplate jdbc;

    public CardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Card> find(String number) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cards WHERE card_num = ?", ROW, number).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Card> lock(String number) {
        return RecordLock.read(jdbc, "SELECT " + COLUMNS + " FROM cards WHERE card_num = ? FOR UPDATE", ROW, number);
    }

    /** The card number and account are the key and the owner; neither changes on REWRITE. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rewrite(Card c) {
        RecordLock.expectOne(jdbc.update("UPDATE cards SET card_cvv_cd = ?, card_embossed_name = ?,"
                        + " card_expiraion_date = ?, card_active_status = ? WHERE card_num = ? AND card_acct_id = ?",
                c.cvv(), c.embossedName(), c.expirationDate(), c.activeStatus(), c.number(), c.accountId()),
                "card " + c.number());
    }
}
