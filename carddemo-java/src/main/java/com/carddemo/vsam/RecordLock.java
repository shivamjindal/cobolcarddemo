package com.carddemo.vsam;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Arrays;
import java.util.Optional;

/**
 * CICS READ UPDATE / REWRITE on Postgres, shared by every repository that rewrites a record.
 *
 * <p>READ UPDATE is {@code SELECT ... FOR UPDATE} and REWRITE is an {@code UPDATE} of the same row, both in
 * the caller's transaction; the row lock lasts until it commits, like a CICS lock lasts until SYNCPOINT.
 * Repositories mark both methods {@code Propagation.MANDATORY}, so a lock outside a transaction fails
 * instead of silently autocommitting. A pseudo-conversational update (show, edit, confirm) keeps the record
 * it showed and, after locking, rewrites only if the locked row still equals it. Batch and bill payment
 * lock, compute from the locked row and rewrite in one transaction. Writers that touch several records lock
 * account, then customer, then card, so two writers never wait on each other in a cycle.
 */
public final class RecordLock {

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private RecordLock() {
    }

    /**
     * Runs a {@code SELECT ... FOR UPDATE} of one row. A lock held by another transaction for longer than
     * two seconds throws {@link CannotAcquireLockException} instead of hanging the request.
     */
    public static <T> Optional<T> read(JdbcTemplate jdbc, String sql, RowMapper<T> row, Object... key) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        try {
            return jdbc.query(sql, row, key).stream().findFirst();
        } catch (UncategorizedSQLException e) {
            if (e.getSQLException() != null && LOCK_NOT_AVAILABLE.equals(e.getSQLException().getSQLState())) {
                throw new CannotAcquireLockException("READ UPDATE timed out: " + Arrays.toString(key), e);
            }
            throw e;
        }
    }

    public static void expectOne(int updated, String record) {
        if (updated != 1) {
            throw new IncorrectResultSizeDataAccessException("REWRITE of " + record + " matched " + updated + " rows", 1, updated);
        }
    }
}
