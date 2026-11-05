package com.carddemo.cics;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code EXEC CICS SYNCPOINT} and {@code SYNCPOINT ROLLBACK} inside the one database transaction a task runs in.
 * A syncpoint commits the task's work so far and a rollback undoes everything since the last one, while the task
 * itself goes on in the same transaction, so an abend after a syncpoint keeps what it committed. Work done
 * outside a transaction is already committed, so both are then no-ops. Triggers of syncpointed MQ puts still
 * fire when the task ends.
 */
@Component
public final class Syncpoint {

    private final DataSource db;

    public Syncpoint(DataSource db) {
        this.db = db;
    }

    /** {@code EXEC CICS SYNCPOINT}. */
    public void commit() {
        onTaskConnection(Connection::commit);
    }

    /** {@code EXEC CICS SYNCPOINT ROLLBACK}. */
    public void rollback() {
        onTaskConnection(Connection::rollback);
    }

    private interface Call {
        void on(Connection connection) throws SQLException;
    }

    private void onTaskConnection(Call call) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        Connection connection = DataSourceUtils.getConnection(db);
        try {
            call.on(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("syncpoint failed", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, db);
        }
    }
}
