package com.carddemo.db2;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Embedded SQL against the Db2 tables, with the Db2 behavior the extension's programs test for. Each statement
 * runs in the current unit of work under its own savepoint, so a failing statement backs out alone. A failure
 * is an SQLCODE in the returned {@link Sqlca}, never an exception: a searched UPDATE or DELETE of no row and a
 * query with no row are +100, and SQLSTATEs map to the SQLCODEs the programs compare against. Host variables
 * are {@code ?} markers, bound untyped as the precompiler's text would be, so a CHAR column compares blank-padded.
 */
@Component
public final class Db2 {

    enum Verb { SELECT, INSERT, UPDATE, DELETE, OPEN, FETCH, CLOSE }

    static final int UNMAPPED = -9999;

    private static final Logger log = LoggerFactory.getLogger(Db2.class);

    private static final Map<String, Integer> SQLCODES = Map.of(
            "23505", -803, "23502", -407, "22001", -404, "42P03", -502, "34000", -501,
            "40P01", -911, "55P03", -911, "21000", -811, "22P02", -420);

    private final DataSource dataSource;
    private final TransactionTemplate unitOfWork;

    public Db2(DataSource dataSource, PlatformTransactionManager transactions) {
        this.dataSource = dataSource;
        this.unitOfWork = new TransactionTemplate(transactions);
    }

    /**
     * Runs {@code work} as one unit of work, as the DSN attach does for a batch program: committed when it
     * returns, rolled back when it throws (an abend). Inside a unit of work already begun it joins that one.
     */
    public <T> T unitOfWork(Supplier<T> work) {
        return unitOfWork.execute(status -> work.get());
    }

    /** {@code EXEC CICS SYNCPOINT}: commits the unit of work so far; the task goes on in a new one. */
    public void syncpoint() {
        Connection connection = connection();
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("SYNCPOINT failed", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /** An INSERT, a searched UPDATE or a searched DELETE. */
    public Sqlca execute(String sql, Object... hostVariables) {
        Verb verb = Verb.valueOf(sql.strip().split("\\s+", 2)[0].toUpperCase(Locale.ROOT));
        return run(verb, sql, null, hostVariables).sqlca();
    }

    /** {@code SELECT ... INTO}: +100 with no row, -811 with more than one (the first row still comes back). */
    public <T> Fetched<T> selectInto(String sql, RowMapper<T> into, Object... hostVariables) {
        return run(Verb.SELECT, sql, into, hostVariables);
    }

    /** {@code DECLARE name CURSOR FOR select}. Nothing runs until {@link Cursor#open}. */
    public <T> Cursor<T> declare(String name, String select, RowMapper<T> into) {
        return new Cursor<>(this, name, select, into);
    }

    static int sqlcode(String sqlstate, Verb verb) {
        if (sqlstate.equals("23503")) {
            return verb == Verb.DELETE ? -532 : -530;
        }
        return SQLCODES.getOrDefault(sqlstate, UNMAPPED);
    }

    <T> Fetched<T> run(Verb verb, String sql, RowMapper<T> into, Object[] hostVariables) {
        Connection connection = connection();
        try {
            Savepoint savepoint = connection.setSavepoint();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < hostVariables.length; i++) {
                    bind(statement, i + 1, hostVariables[i]);
                }
                boolean query = statement.execute();
                int rows = 0;
                Optional<T> first = Optional.empty();
                if (query) {
                    try (ResultSet result = statement.getResultSet()) {
                        while (result.next()) {
                            if (rows == 0 && into != null) {
                                first = Optional.of(into.mapRow(result, 0));
                            }
                            rows++;
                        }
                    }
                } else {
                    rows = Math.max(statement.getUpdateCount(), 0);
                }
                connection.releaseSavepoint(savepoint);
                if (rows == 0 && (query || verb == Verb.UPDATE || verb == Verb.DELETE)) {
                    return new Fetched<>(Sqlca.notFound(), first);
                }
                if (verb == Verb.SELECT && rows > 1) {
                    return new Fetched<>(new Sqlca(-811, "21000", "", rows), first);
                }
                return new Fetched<>(Sqlca.ok(rows), first);
            } catch (SQLException e) {
                connection.rollback(savepoint);
                String state = e.getSQLState() == null ? "HY000" : e.getSQLState();
                int code = sqlcode(state, verb);
                if (code == UNMAPPED) {
                    log.warn("unmapped SQLSTATE {} for {}", state, sql, e);
                }
                return new Fetched<>(new Sqlca(code, state, primaryMessage(e), 0), Optional.empty());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the unit of work cannot set or roll back a savepoint", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private Connection connection() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Db2 statements run inside a unit of work: a CICS task or Db2.unitOfWork");
        }
        return DataSourceUtils.getConnection(dataSource);
    }

    /** Postgres text cannot hold X'00', so LOW-VALUES go as X'01', which orders the same against every key. */
    private static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.OTHER);
            return;
        }
        String text = value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
        statement.setObject(index, text.replace('\0', '\u0001'), Types.OTHER);
    }

    private static String primaryMessage(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("");
        return message.startsWith("ERROR: ") ? message.substring("ERROR: ".length()) : message;
    }
}
