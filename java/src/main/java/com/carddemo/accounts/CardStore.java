package com.carddemo.accounts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.carddemo.ksds.KeyedBrowse;

/** CARDDAT, table {@code cards}, and its alternate index CARDAIX on the account id. */
@Component
public final class CardStore {

    private static final String COLUMNS = "card_num, card_acct_id, card_cvv_cd, card_embossed_name, "
            + "card_expiraion_date, card_active_status, card_filler";

    private static final KeyedBrowse.Table<CardRecord> TABLE =
            new KeyedBrowse.Table<>("cards", "card_num", COLUMNS, CardStore::row, CardRecord::num);

    private final JdbcClient jdbc;
    private final KeyedFile<CardRecord> file;

    public CardStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.file = new KeyedFile<>(jdbc, "CARDDAT", "cards", COLUMNS, "card_num", CardStore::row);
    }

    /** {@code READ FILE(CARDDAT) RIDFLD(cardNum)}, the card number without trailing blanks. */
    public Read<CardRecord> find(String cardNum) {
        return file.read(cardNum);
    }

    /** {@code READ FILE(CARDAIX) RIDFLD(acctId)}. */
    public Read<CardRecord> findByAccount(long acctId) {
        return file.readByAlternateKey("CARDAIX", "card_acct_id", acctId);
    }

    public ForUpdate<CardRecord> findForUpdate(String cardNum) {
        return file.readForUpdate(cardNum);
    }

    public Write rewrite(Held<CardRecord> held, CardRecord edited) {
        if (!edited.num().equals(held.record().num())) {
            throw new IllegalArgumentException("REWRITE changes the key " + held.record().num() + " to " + edited.num());
        }
        return file.rewrite(edited.num(), "card_acct_id = ?, card_cvv_cd = ?, card_embossed_name = ?, "
                        + "card_expiraion_date = ?, card_active_status = ?, card_filler = ?",
                edited.acctId(), edited.cvv(), edited.embossedName(), edited.expirationDate(), edited.activeStatus(),
                edited.filler());
    }

    /** A browse for {@code STARTBR DATASET(CARDDAT)}. */
    public KeyedBrowse<CardRecord> browse() {
        return new KeyedBrowse<>(jdbc, TABLE);
    }

    private static CardRecord row(ResultSet rs, int rowNum) throws SQLException {
        return new CardRecord(rs.getString("card_num"), rs.getLong("card_acct_id"),
                rs.getObject("card_cvv_cd", Integer.class), rs.getString("card_embossed_name"),
                rs.getObject("card_expiraion_date", LocalDate.class), rs.getString("card_active_status"),
                rs.getString("card_filler"));
    }
}
