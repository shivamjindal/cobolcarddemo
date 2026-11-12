package com.carddemo.online;

import com.carddemo.mq.MessageProgram;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jms.core.JmsTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Replays the trigger cases of every MQ-triggered program through the embedded broker: the request goes to the
 * program's input queue with this test's reply queue as MQMD-REPLYTOQ, and each expected reply is taken from its
 * queue. A reply is put only after the database commits, so file effects are read once the replies are in.
 */
@SpringBootTest
@Import(TestDatabase.class)
class MessageSpecReplayTest {

    private static final Tally TALLY = new Tally();
    private static final String REPLY_TO = "CARDDEMO.SPEC.REPLY";
    private static final long WAIT_MILLIS = 10_000;

    @Autowired
    ObjectProvider<MessageProgram> programs;

    @Autowired
    Datasets datasets;

    @Autowired
    JmsTemplate jms;

    @TestFactory
    Stream<DynamicContainer> replay() {
        return programs.orderedStream().map(program -> dynamicContainer(program.program(),
                SpecCase.load(program.program()).stream().map(c -> dynamicTest(c.toString(), () -> replay(program, c)))));
    }

    @AfterAll
    static void printTally() {
        TALLY.print();
    }

    @SuppressWarnings("unchecked")
    private void replay(MessageProgram program, SpecCase c) throws Exception {
        if (c.kind() == SpecCase.Kind.SKIPPED) {
            TALLY.record(c);
            abort(c.reason());
        }
        List<Map<String, Object>> expected = (List<Map<String, Object>>) c.then().getOrDefault("replies", List.of());
        Set<String> queues = new LinkedHashSet<>();
        expected.forEach(r -> queues.add(queue((String) r.get("queue"))));
        queues.add(REPLY_TO);
        queues.forEach(this::drain);
        datasets.reset(c.files());
        var before = datasets.snapshot();

        String correlationId = SpecCase.text(c.when().getOrDefault("correlation_id", UUID.randomUUID().toString()));
        jms.send(program.requestQueue(), session -> {
            TextMessage request = session.createTextMessage(SpecCase.text(c.when().get("message")));
            request.setJMSCorrelationID(correlationId);
            request.setJMSReplyTo(session.createQueue(REPLY_TO));
            return request;
        });

        SoftAssertions soft = new SoftAssertions();
        jms.setReceiveTimeout(WAIT_MILLIS);
        for (Map<String, Object> reply : expected) {
            Message got = jms.receive(queue((String) reply.get("queue")));
            soft.assertThat(got).as("reply on %s", reply.get("queue")).isNotNull();
            if (got == null) {
                continue;
            }
            soft.assertThat(((TextMessage) got).getText().stripTrailing()).as("reply on %s", reply.get("queue"))
                    .matches(SpecCase.pattern(SpecCase.text(reply.get("message")).stripTrailing()));
            if (reply.containsKey("correlation_id")) {
                soft.assertThat(got.getJMSCorrelationID()).as("correlation id")
                        .isEqualTo(SpecCase.text(reply.get("correlation_id")));
            }
        }
        jms.setReceiveTimeout(JmsTemplate.RECEIVE_TIMEOUT_NO_WAIT);
        queues.forEach(q -> soft.assertThat(jms.receive(q)).as("extra message on %s", q).isNull());
        soft.assertAll();

        c.verify(new Observed(200, null, null, null, Map.of(), null, datasets.effectsSince(before)), datasets);
        TALLY.record(c);
    }

    private static String queue(String specQueue) {
        return specQueue.equals("{reply-to}") ? REPLY_TO : specQueue;
    }

    private void drain(String queue) {
        jms.setReceiveTimeout(JmsTemplate.RECEIVE_TIMEOUT_NO_WAIT);
        while (jms.receive(queue) != null) {
            Thread.onSpinWait();
        }
    }
}
