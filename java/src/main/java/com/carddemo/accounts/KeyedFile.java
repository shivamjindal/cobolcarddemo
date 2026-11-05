package com.carddemo.accounts;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The READ, READ UPDATE and REWRITE one KSDS table shares with the others. Every call runs in the request's transaction. */
final class KeyedFile<T> {

    private static final Logger log = LoggerFactory.getLogger(KeyedFile.class);

    private final JdbcClient jdbc;
    private final String file;
    private final String table;
    private final String columns;
    private final String keyColumn;
    private final RowMapper<T> rows;

    KeyedFile(JdbcClient jdbc, String file, String table, String columns, String keyColumn, RowMapper<T> rows) {
        this.jdbc = jdbc;
        this.file = file;
        this.table = table;
        this.columns = columns;
        this.keyColumn = keyColumn;
        this.rows = rows;
    }

    /** {@code READ FILE(file) RIDFLD(key)}. */
    Read<T> read(Object key) {
        try {
            return jdbc.sql(select() + " where " + keyColumn + " = ?").param(key).query(rows).optional()
                    .<Read<T>>map(Read.Found::new)
                    .orElseGet(Read.Missing::new);
        } catch (DataAccessException e) {
            log.warn("READ {} {} failed", file, key, e);
            return new Read.Failed<>(Read.IOERR, 0);
        }
    }

    /**
     * {@code READ FILE(path) RIDFLD(key)} through a non-unique alternate index: the first record in
     * alternate-key then primary-key order, and DUPKEY when another record has the same alternate key.
     */
    Read<T> readByAlternateKey(String path, String alternateColumn, Object key) {
        try {
            List<T> found = jdbc.sql(select() + " where " + alternateColumn + " = ? order by " + keyColumn
                    + " collate \"C\" limit 2").param(key).query(rows).list();
            if (found.isEmpty()) {
                return new Read.Missing<>();
            }
            return found.size() == 1 ? new Read.Found<>(found.get(0)) : new Read.Failed<>(Read.DUPKEY, 0);
        } catch (DataAccessException e) {
            log.warn("READ {} {} failed", path, key, e);
            return new Read.Failed<>(Read.IOERR, 0);
        }
    }

    /** {@code READ FILE(file) RIDFLD(key) UPDATE}. */
    ForUpdate<T> readForUpdate(Object key) {
        try {
            return jdbc.sql(select() + " where " + keyColumn + " = ? for update").param(key).query(rows).optional()
                    .<ForUpdate<T>>map(Held::new)
                    .orElseGet(Read.Missing::new);
        } catch (DataAccessException e) {
            log.warn("READ UPDATE {} {} failed", file, key, e);
            return new Read.Failed<>(Read.IOERR, 0);
        }
    }

    /** {@code REWRITE FILE(file)} of the held record: {@code assignments} sets every non-key column. */
    Write rewrite(Object key, String assignments, Object... values) {
        Object[] params = new Object[values.length + 1];
        System.arraycopy(values, 0, params, 0, values.length);
        params[values.length] = key;
        try {
            int updated = jdbc.sql("update " + table + " set " + assignments + " where " + keyColumn + " = ?")
                    .params(params).update();
            if (updated != 1) {
                throw new IllegalStateException("held " + file + " record " + key + " is gone");
            }
            return Write.DONE;
        } catch (DataAccessException e) {
            log.warn("REWRITE {} {} failed", file, key, e);
            return Write.FAILED;
        }
    }

    private String select() {
        return "select " + columns + " from " + table;
    }
}
