package com.carddemo.characterization;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.mq.Message;
import com.carddemo.mq.MessageQueues;
import com.carddemo.mq.MqTriggerMonitor;
import com.carddemo.mq.Reason;
import com.carddemo.mq.TaskEnd;
import com.carddemo.mq.Trigger;

/**
 * The online {@code mq} step of a case: puts the case's messages on the queue in one unit of work, then runs the
 * trigger transid once on this thread with an MQTM naming the queue. The reply's {@code putThrough} is the last
 * message sequence the step itself put, so the adapter can tell the programs' puts from the case's.
 */
@RestController
@Profile("characterization")
class MqStepController {

    record Put(String data, String msgId, String correlId, String replyTo) {}

    record Step(String queue, String trigger, List<Put> messages) {}

    private final MessageQueues queues;
    private final MqTriggerMonitor monitor;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    MqStepController(MessageQueues queues, MqTriggerMonitor monitor, JdbcClient jdbc,
            PlatformTransactionManager transactions) {
        this.queues = queues;
        this.monitor = monitor;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
    }

    @PostMapping("/characterization/mq")
    Map<String, Object> run(@RequestBody Step step) {
        HexFormat hex = HexFormat.of();
        transactions.executeWithoutResult(status -> {
            for (Put put : step.messages()) {
                Reason reason = queues.put1(step.queue(), new Message(hex.parseHex(put.data()), hex.parseHex(put.msgId()),
                        hex.parseHex(put.correlId()), put.replyTo()), true);
                if (reason != Reason.NONE) {
                    throw new IllegalStateException("MQPUT1 to " + step.queue() + " failed with " + reason);
                }
            }
        });
        long putThrough = jdbc.sql("SELECT coalesce(max(sequence), 0) FROM mq_messages").query(Long.class).single();
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("putThrough", putThrough);
        switch (monitor.start(new Trigger(step.queue(), step.trigger(), ""))) {
            case TaskEnd.Normal normal -> {
            }
            case TaskEnd.Abended abended -> reply.put("abend", abended.code());
            case TaskEnd.NotDefined undefined -> reply.put("notDefined", undefined.transid());
        }
        return reply;
    }
}
