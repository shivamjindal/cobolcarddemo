package com.carddemo.cics;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code EXEC CICS WRITEQ TD} to an extrapartition queue such as CSSL, held in table {@code td_queue_records}. The
 * queues are not recoverable, so a record is written in a transaction of its own and stays when the task rolls back.
 */
@Component
public final class TransientData {

    private final JdbcClient jdbc;
    private final TransactionTemplate own;

    public TransientData(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** {@code WRITEQ TD QUEUE(queue) FROM(record) NOHANDLE}. */
    public void writeq(String queue, byte[] record) {
        own.executeWithoutResult(status -> jdbc.sql("INSERT INTO td_queue_records (queue, data) VALUES (?, ?)")
                .params(queue, record).update());
    }

    /** The records written to {@code queue}, oldest first. */
    public List<byte[]> records(String queue) {
        return jdbc.sql("SELECT data FROM td_queue_records WHERE queue = ? ORDER BY sequence")
                .param(queue).query((rs, n) -> rs.getBytes(1)).list();
    }
}
