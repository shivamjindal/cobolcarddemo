package com.carddemo.mq;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.carddemo.TestDatabase;
import com.carddemo.cics.CicsRegion;
import com.carddemo.cics.Extension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Postgres queue manager: unit-of-work rules, selection, handles and triggers. Queue names start with ZZ. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "carddemo.mq.max-wait=PT0.3S")
class MessageQueuesIT {

    private static final BlockingQueue<Trigger> STARTED = new LinkedBlockingQueue<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @TestConfiguration
    static class Programs {

        /** ZZTR, in no extension: records its MQTM and drains the queue, or abends on data ABEND. */
        @Bean
        TriggeredProgram recorder(MessageQueues queues) {
            return new TriggeredProgram() {
                @Override
                public String transid() {
                    return "ZZTR";
                }

                @Override
                public String program() {
                    return "ZZTRACE";
                }

                @Override
                public void run(Trigger trigger) {
                    QueueHandle in = queues.open(trigger.queue()).handle().orElseThrow();
                    for (Got got = queues.get(in, GetOptions.waitMillis(0, true)); got.ok();
                            got = queues.get(in, GetOptions.waitMillis(0, true))) {
                        if (text(got.message().orElseThrow().data()).equals("ABEND")) {
                            throw new TaskEnd.AbendException("TEST");
                        }
                    }
                    STARTED.add(trigger);
                }
            };
        }
    }

    @Autowired
    private MessageQueues queues;

    @Autowired
    private MqTriggerMonitor monitor;

    @Autowired
    private CicsRegion region;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.sql("DELETE FROM mq_messages WHERE queue LIKE 'ZZ%'").update();
        jdbc.sql("DELETE FROM mq_queues WHERE name LIKE 'ZZ%'").update();
        region.install(EnumSet.allOf(Extension.class));
        STARTED.clear();
    }

    @Test
    void getsInPutOrderAndThenReportsNoMessage() {
        QueueHandle q = open("ZZ.ORDER");
        assertEquals(Reason.NONE, queues.put(q, Message.of(bytes("one")), false));
        assertEquals(Reason.NONE, queues.put1("ZZ.ORDER", Message.of(bytes("two")), false));

        assertEquals("one", text(queues.get(q, GetOptions.waitMillis(0, false)).message().orElseThrow().data()));
        assertEquals("two", text(queues.get(q, GetOptions.waitMillis(0, false)).message().orElseThrow().data()));
        long started = System.nanoTime();
        assertEquals(Reason.NO_MSG_AVAILABLE, queues.get(q, GetOptions.waitMillis(5000, false)).reason());
        long waited = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertTrue(waited >= 250 && waited < 3000, "waited " + waited + " ms, capped by carddemo.mq.max-wait");
    }

    @Test
    void rollbackPutsASyncpointedGetBackButNotAnUnsyncpointedOne() {
        QueueHandle q = open("ZZ.UOW");
        queues.put(q, Message.of(bytes("kept")), false);
        transactions.executeWithoutResult(status -> {
            assertTrue(queues.get(q, GetOptions.waitMillis(0, true)).ok());
            status.setRollbackOnly();
        });
        assertEquals(1, depth("ZZ.UOW"));

        transactions.executeWithoutResult(status -> {
            assertTrue(queues.get(q, GetOptions.waitMillis(0, false)).ok());
            status.setRollbackOnly();
        });
        assertEquals(0, depth("ZZ.UOW"));
    }

    @Test
    void rollbackDropsASyncpointedPutButNotAnUnsyncpointedOne() {
        transactions.executeWithoutResult(status -> {
            queues.put1("ZZ.PUT", Message.of(bytes("dropped")), true);
            queues.put1("ZZ.PUT", Message.of(bytes("sent")), false);
            status.setRollbackOnly();
        });
        QueueHandle q = open("ZZ.PUT");
        assertEquals("sent", text(queues.get(q, GetOptions.waitMillis(0, false)).message().orElseThrow().data()));
        assertEquals(0, depth("ZZ.PUT"));
    }

    @Test
    void selectsByCorrelIdAndKeepsTheIds() {
        byte[] correl = Arrays.copyOf(bytes("REQ2"), Message.ID_LENGTH);
        queues.put1("ZZ.SELECT", new Message(bytes("first"), Message.NONE, Message.NONE, ""), false);
        queues.put1("ZZ.SELECT", new Message(bytes("second"), Message.NONE, correl, "ZZ.REPLY"), false);
        QueueHandle q = open("ZZ.SELECT");

        Message got = queues.get(q, new GetOptions(Duration.ZERO, false, Message.NONE, correl)).message().orElseThrow();
        assertEquals("second", text(got.data()));
        assertArrayEquals(correl, got.correlId());
        assertEquals("ZZ.REPLY", got.replyToQ());
        assertTrue(text(got.msgId()).matches("CDMQ\\d{20}"), text(got.msgId()));
        assertFalse(queues.get(q, new GetOptions(Duration.ZERO, false, got.msgId(), Message.NONE)).ok());
    }

    @Test
    void browseShowsEveryMessageInPutOrderAndTakesNone() {
        queues.put1("ZZ.BROWSE", Message.of(bytes("one")), false);
        queues.put1("ZZ.BROWSE", new Message(bytes("two"), Message.NONE, Message.NONE, "ZZ.REPLY"), false);

        List<Message> browsed = queues.browse("ZZ.BROWSE");
        assertEquals(List.of("one", "two"), browsed.stream().map(m -> text(m.data())).toList());
        assertEquals("ZZ.REPLY", browsed.get(1).replyToQ());
        assertEquals(2, depth("ZZ.BROWSE"));
    }

    @Test
    void aClosedHandleIsAnError() {
        QueueHandle q = open("ZZ.CLOSE");
        assertEquals(Reason.NONE, queues.close(q));
        assertEquals(Reason.HOBJ_ERROR, queues.close(q));
        assertEquals(Reason.HOBJ_ERROR, queues.put(q, Message.of(bytes("x")), false));
        assertEquals(Reason.HOBJ_ERROR, queues.get(q, GetOptions.waitMillis(0, false)).reason());
        assertEquals(2019, Reason.HOBJ_ERROR.code());
        assertEquals(Reason.MQCC_FAILED, Reason.HOBJ_ERROR.completionCode());
    }

    @Test
    void aTriggerStartsItsTransidOnlyAfterThePutCommits() throws InterruptedException {
        jdbc.sql("INSERT INTO mq_queues (name, trigger_type, trigger_transid, trigger_data) VALUES ('ZZ.TRIG', 'FIRST', 'ZZTR', 'DATA')")
                .update();
        transactions.executeWithoutResult(status -> {
            queues.put1("ZZ.TRIG", Message.of(bytes("rolled back")), true);
            status.setRollbackOnly();
        });
        assertNull(STARTED.poll(300, TimeUnit.MILLISECONDS));

        transactions.executeWithoutResult(status -> {
            queues.put1("ZZ.TRIG", Message.of(bytes("a")), true);
            queues.put1("ZZ.TRIG", Message.of(bytes("b")), true);
            assertTrue(STARTED.isEmpty());
        });
        assertEquals(new Trigger("ZZ.TRIG", "ZZTR", "DATA"), STARTED.poll(5, TimeUnit.SECONDS));
        assertNull(STARTED.poll(300, TimeUnit.MILLISECONDS), "FIRST fires once for a queue that was empty");
        assertEquals(0, depth("ZZ.TRIG"));
    }

    @Test
    void anAbendRollsTheTaskBackAndAnUninstalledTransidIsNotDefined() {
        queues.put1("ZZ.ABEND", Message.of(bytes("ABEND")), false);
        assertEquals(new TaskEnd.Abended("TEST"), monitor.start(new Trigger("ZZ.ABEND", "ZZTR", "")));
        assertEquals(1, depth("ZZ.ABEND"));

        region.install(EnumSet.of(Extension.PENDING_AUTHORIZATIONS));
        assertEquals(new TaskEnd.NotDefined("CDRA"), monitor.start(new Trigger("ZZ.ABEND", "CDRA", "")));
        assertEquals(new TaskEnd.NotDefined("ZZZZ"), monitor.start(new Trigger("ZZ.ABEND", "ZZZZ", "")));
    }

    private QueueHandle open(String queue) {
        Opened opened = queues.open(queue);
        assertEquals(Reason.NONE, opened.reason());
        return opened.handle().orElseThrow();
    }

    private int depth(String queue) {
        return jdbc.sql("SELECT count(*) FROM mq_messages WHERE queue = ?").param(queue).query(Integer.class).single();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
