package com.carddemo.transactions;

import com.carddemo.vsam.RecordLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** TCATBALF over tran_cat_balances. Writers lock the owning account first (see {@code AccountRepository}). */
@Repository
public class TranCatBalanceRepository {

    private static final String COLUMNS = "trancat_acct_id, trancat_type_cd, trancat_cd, tran_cat_bal";
    private static final String KEY_ORDER = "trancat_acct_id, trancat_type_cd COLLATE \"C\", trancat_cd";
    private static final RowMapper<TranCatBalance> ROW = (rs, n) ->
            new TranCatBalance(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4));

    private final JdbcTemplate jdbc;

    public TranCatBalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A sequential read of the whole file, in key order. */
    public List<TranCatBalance> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM tran_cat_balances ORDER BY " + KEY_ORDER, ROW);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TranCatBalance> lock(long accountId, String typeCode, int categoryCode) {
        return RecordLock.read(jdbc, "SELECT " + COLUMNS + " FROM tran_cat_balances WHERE trancat_acct_id = ?"
                + " AND trancat_type_cd = ? AND trancat_cd = ? FOR UPDATE", ROW, accountId, typeCode, categoryCode);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(TranCatBalance b) {
        jdbc.update("INSERT INTO tran_cat_balances (" + COLUMNS + ") VALUES (?, ?, ?, ?)",
                b.accountId(), b.typeCode(), b.categoryCode(), b.balance());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rewrite(TranCatBalance b) {
        RecordLock.expectOne(jdbc.update("UPDATE tran_cat_balances SET tran_cat_bal = ? WHERE trancat_acct_id = ?"
                        + " AND trancat_type_cd = ? AND trancat_cd = ?",
                b.balance(), b.accountId(), b.typeCode(), b.categoryCode()), "tran cat balance " + b.key());
    }
}
