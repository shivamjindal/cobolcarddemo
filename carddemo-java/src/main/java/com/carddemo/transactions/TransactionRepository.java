package com.carddemo.transactions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** TRANSACT over the transactions table. Browses run in key byte order, like a KSDS. */
@Repository
public class TransactionRepository {

    private static final String COLUMNS = "tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt,"
            + " tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip, tran_card_num,"
            + " tran_orig_ts, tran_proc_ts";
    private static final String ID = "tran_id COLLATE \"C\"";

    private static final RowMapper<Transaction> ROW = (rs, n) -> new Transaction(rs.getString(1), rs.getString(2),
            rs.getInt(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getLong(7), rs.getString(8),
            rs.getString(9), rs.getString(10), rs.getString(11), rs.getObject(12, LocalDateTime.class),
            rs.getObject(13, LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Transaction> find(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transactions WHERE tran_id = ?", ROW, id).stream().findFirst();
    }

    /** STARTBR at {@code startKey} (GTEQ), then READNEXT. */
    public List<Transaction> pageFrom(String startKey, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transactions WHERE " + ID + " >= ? ORDER BY " + ID + " LIMIT ?",
                ROW, startKey, limit);
    }

    public List<Transaction> pageAfter(String key, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transactions WHERE " + ID + " > ? ORDER BY " + ID + " LIMIT ?",
                ROW, key, limit);
    }

    /** Nearest first, like READPREV. */
    public List<Transaction> pageBefore(String key, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transactions WHERE " + ID + " < ? ORDER BY " + ID + " DESC LIMIT ?",
                ROW, key, limit);
    }

    /** STARTBR HIGH-VALUES and one READPREV: the highest key on file. */
    public Optional<Transaction> last() {
        return jdbc.query("SELECT " + COLUMNS + " FROM transactions ORDER BY " + ID + " DESC LIMIT 1", ROW)
                .stream().findFirst();
    }

    /**
     * Writes {@code record} under the next TRAN-ID: the highest 16-digit id plus one, as the online programs
     * number new records. Adds hold a transaction-scoped advisory lock from reading that id until commit, so two
     * concurrent adds never compute the same id. Ids that are not 16 digits (none in the sample data) are skipped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Transaction add(Function<String, Transaction> record) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('transactions.tran_id'))", Object.class);
        long last = jdbc.query("SELECT tran_id FROM transactions WHERE tran_id ~ '^[0-9]{16}$'"
                        + " ORDER BY tran_id DESC LIMIT 1", (rs, n) -> Long.parseLong(rs.getString(1)))
                .stream().findFirst().orElse(0L);
        Transaction t = record.apply("%016d".formatted(last + 1));
        jdbc.update("INSERT INTO transactions (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                t.id(), t.typeCode(), t.categoryCode(), t.source(), t.description(), t.amount(), t.merchantId(),
                t.merchantName(), t.merchantCity(), t.merchantZip(), t.cardNumber(), t.originated(), t.processed());
        return t;
    }
}
