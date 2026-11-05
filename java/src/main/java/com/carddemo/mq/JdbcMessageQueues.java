package com.carddemo.mq;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The default {@link MessageQueues}: messages are rows of {@code mq_messages}, so a syncpointed call is one more
 * statement in the caller's Spring transaction, and one without syncpoint runs in a transaction of its own. Every
 * queue name is defined; {@code mq_queues} only adds trigger definitions. A put with MQMI-NONE gets msgId
 * "CDMQ" and the message's sequence number in 20 digits, so ids are unique and repeatable.
 */
@Component
public final class JdbcMessageQueues implements MessageQueues {

    private static final Duration POLL = Duration.ofMillis(100);

    private final JdbcClient jdbc;
    private final TransactionTemplate own;
    private final MqTriggerMonitor monitor;
    private final Duration maxWait;
    private final Map<Integer, String> open = new ConcurrentHashMap<>();
    private final AtomicInteger nextHobj = new AtomicInteger();

    JdbcMessageQueues(JdbcClient jdbc, PlatformTransactionManager transactions, MqTriggerMonitor monitor,
            @Value("${carddemo.mq.max-wait:PT5S}") Duration maxWait) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.monitor = monitor;
        this.maxWait = maxWait;
    }

    @Override
    public Opened open(String queue) {
        if (queue.isBlank() || queue.length() > QueueHandle.NAME_LENGTH) {
            return Opened.failed(Reason.UNKNOWN_OBJECT_NAME);
        }
        String name = queue.stripTrailing();
        int hobj = nextHobj.incrementAndGet();
        open.put(hobj, name);
        return Opened.ok(new QueueHandle(hobj, name));
    }

    /** Waits for at most {@code carddemo.mq.max-wait}, however long the program asks to wait. */
    @Override
    public Got get(QueueHandle handle, GetOptions options) {
        if (!isOpen(handle)) {
            return Got.failed(Reason.HOBJ_ERROR);
        }
        long deadline = System.nanoTime() + min(options.waitInterval(), maxWait).toNanos();
        while (true) {
            Optional<Message> taken = options.syncpoint()
                    ? take(handle.queue(), options)
                    : own.execute(s -> take(handle.queue(), options));
            if (taken.isPresent()) {
                return Got.ok(taken.get());
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return Got.failed(Reason.NO_MSG_AVAILABLE);
            }
            try {
                Thread.sleep(Math.max(1, Math.min(POLL.toMillis(), Duration.ofNanos(left).toMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Got.failed(Reason.NO_MSG_AVAILABLE);
            }
        }
    }

    @Override
    public Reason put(QueueHandle handle, Message message, boolean syncpoint) {
        if (!isOpen(handle)) {
            return Reason.HOBJ_ERROR;
        }
        return in(syncpoint, () -> insert(handle.queue(), message));
    }

    @Override
    public Reason put1(String queue, Message message, boolean syncpoint) {
        if (queue.isBlank() || queue.length() > QueueHandle.NAME_LENGTH) {
            return Reason.UNKNOWN_OBJECT_NAME;
        }
        return in(syncpoint, () -> insert(queue.stripTrailing(), message));
    }

    @Override
    public Reason close(QueueHandle handle) {
        return open.remove(handle.hobj(), handle.queue()) ? Reason.NONE : Reason.HOBJ_ERROR;
    }

    @Override
    public List<Message> browse(String queue) {
        return own.execute(s -> jdbc.sql("""
                SELECT data, msg_id, correl_id, reply_to_q FROM mq_messages WHERE queue = ? ORDER BY sequence""")
                .param(queue.stripTrailing())
                .query((row, n) -> new Message(row.getBytes("data"), row.getBytes("msg_id"), row.getBytes("correl_id"),
                        row.getString("reply_to_q")))
                .list());
    }

    private boolean isOpen(QueueHandle handle) {
        return handle.queue().equals(open.get(handle.hobj()));
    }

    private Reason in(boolean syncpoint, Supplier<Reason> work) {
        return syncpoint ? work.get() : own.execute(s -> work.get());
    }

    private Optional<Message> take(String queue, GetOptions options) {
        byte[] msgId = options.msgId();
        byte[] correlId = options.correlId();
        return jdbc.sql("""
                DELETE FROM mq_messages WHERE sequence = (
                    SELECT sequence FROM mq_messages
                    WHERE queue = ? AND (? OR msg_id = ?) AND (? OR correl_id = ?)
                    ORDER BY sequence LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING data, msg_id, correl_id, reply_to_q""")
                .params(queue, Arrays.equals(msgId, Message.NONE), msgId, Arrays.equals(correlId, Message.NONE), correlId)
                .query((row, n) -> new Message(row.getBytes("data"), row.getBytes("msg_id"), row.getBytes("correl_id"),
                        row.getString("reply_to_q")))
                .optional();
    }

    /** A FIRST trigger fires when the put finds the queue empty, as far as this transaction can see. */
    private Reason insert(String queue, Message message) {
        Optional<Trigger> trigger = jdbc.sql("""
                SELECT trigger_transid, trigger_data FROM mq_queues
                WHERE name = ? AND (trigger_type = 'EVERY' OR NOT EXISTS (SELECT 1 FROM mq_messages WHERE queue = ?))""")
                .params(queue, queue)
                .query((row, n) -> new Trigger(queue, row.getString("trigger_transid"), row.getString("trigger_data")))
                .optional();
        long sequence = jdbc.sql("SELECT nextval(pg_get_serial_sequence('mq_messages', 'sequence'))")
                .query(Long.class).single();
        byte[] msgId = Arrays.equals(message.msgId(), Message.NONE)
                ? String.format("CDMQ%020d", sequence).getBytes(StandardCharsets.US_ASCII)
                : message.msgId();
        jdbc.sql("""
                INSERT INTO mq_messages (sequence, queue, msg_id, correl_id, reply_to_q, data)
                OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, ?, ?)""")
                .params(sequence, queue, msgId, message.correlId(), message.replyToQ(), message.data())
                .update();
        trigger.ifPresent(monitor::fireAfterCommit);
        return Reason.NONE;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
