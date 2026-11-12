package com.carddemo.cards;

import com.carddemo.vsam.RecordLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CARDDAT over the cards table; locks per {@link RecordLock}, after the account and customer. Browses are
 * keyset pages in byte order (COLLATE "C"), the order of the KSDS key.
 */
@Repository
public class CardRepository {

    static final String COLUMNS = "card_num, card_acct_id, card_cvv_cd, card_embossed_name, card_expiraion_date,"
            + " card_active_status";

    static final RowMapper<Card> ROW = (rs, n) -> new Card(rs.getString(1), rs.getLong(2), rs.getInt(3),
            rs.getString(4), rs.getObject(5, LocalDate.class), rs.getString(6));

    private static final String NUMBER = "card_num COLLATE \"C\"";

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

    /** STARTBR GTEQ and READNEXT: up to {@code limit} cards from {@code startKey} on, in key order. */
    public List<Card> pageFrom(String startKey, CardFilter filter, int limit) {
        return browse(">=", "", startKey, filter, limit);
    }

    /** READPREV: up to {@code limit} cards before {@code key}, nearest first. */
    public List<Card> pageBefore(String key, CardFilter filter, int limit) {
        return browse("<", " DESC", key, filter, limit);
    }

    private List<Card> browse(String comparison, String order, String key, CardFilter filter, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM cards WHERE " + NUMBER + " " + comparison + " ?");
        List<Object> args = new ArrayList<>(List.of(key));
        if (filter.accountId() != null) {
            sql.append(" AND card_acct_id = ?");
            args.add(filter.accountId());
        }
        if (filter.cardNumber() != null) {
            sql.append(" AND card_num = ?");
            args.add(filter.cardNumber());
        }
        sql.append(" ORDER BY ").append(NUMBER).append(order).append(" LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), ROW, args.toArray());
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
