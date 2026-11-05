package com.carddemo.characterization;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.cics.CicsRegion;
import com.carddemo.cics.Extension;
import com.carddemo.mq.GetOptions;
import com.carddemo.mq.Got;
import com.carddemo.mq.Message;
import com.carddemo.mq.MessageQueues;
import com.carddemo.mq.QueueHandle;
import com.carddemo.mq.TaskEnd;
import com.carddemo.mq.Trigger;
import com.carddemo.mq.TriggeredProgram;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The online {@code mq} step from the adapter through the warm server's endpoint, observed as the harness does. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("characterization")
class MqStepIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @TestConfiguration
    static class Programs {

        /** ZZEC, in no extension: answers one request on the reply queue with its correlId, or abends on data ABEND. */
        @Bean
        TriggeredProgram echo(MessageQueues queues) {
            return new TriggeredProgram() {
                @Override
                public String transid() {
                    return "ZZEC";
                }

                @Override
                public String program() {
                    return "ZZECHO";
                }

                @Override
                public void run(Trigger trigger) {
                    QueueHandle in = queues.open(trigger.queue()).handle().orElseThrow();
                    Got got = queues.get(in, GetOptions.waitMillis(5000, true));
                    Message request = got.message().orElseThrow();
                    String data = new String(request.data(), StandardCharsets.ISO_8859_1);
                    queues.put1(request.replyToQ(), new Message(("ECHO " + data + "   ").getBytes(StandardCharsets.ISO_8859_1),
                            Message.NONE, request.correlId(), ""), true);
                    if (data.equals("ABEND")) {
                        throw new TaskEnd.AbendException("TEST");
                    }
                }
            };
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CicsRegion region;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void installVsamMq() {
        jdbc.sql("DELETE FROM mq_messages WHERE queue LIKE 'ZZ%'").update();
        region.install(EnumSet.of(Extension.VSAM_MQ));
    }

    @AfterEach
    void restoreDefault() {
        jdbc.sql("DELETE FROM mq_messages WHERE queue LIKE 'ZZ%'").update();
        region.install(EnumSet.allOf(Extension.class));
    }

    @Test
    void reportsTheProgramsPutsAndTheCasesUnreadMessages() throws Exception {
        try (Connection db = dataSource.getConnection()) {
            Adapter.Browser browser = new Adapter.Browser(URI.create("http://127.0.0.1:" + port));
            int[] put = {0};
            Map<String, Object> first = Adapter.mqStep(browser, db, JSON.readTree("""
                    {"queue": "ZZ.REQ", "trigger": "ZZEC", "messages": [
                        {"data": "one", "reply_to": "ZZ.REPLY", "correl_id": "C1"},
                        {"data": "two", "reply_to": "ZZ.REPLY"}]}"""), put);
            assertEquals(Map.of("mq", Map.of("ZZ.REPLY", List.of(Map.of("data", "ECHO one", "correl_id", "C1"))),
                    "unread", Map.of("ZZ.REQ", 1)), first);

            Map<String, Object> second = Adapter.mqStep(browser, db, JSON.readTree("""
                    {"queue": "ZZ.REQ", "trigger": "ZZEC", "messages": [{"data": "ABEND", "reply_to": "ZZ.REPLY"}]}"""),
                    put);
            assertEquals(Map.of("mq", Map.of("ZZ.REPLY", List.of(Map.of("data", "ECHO two", "correl_id", ""))),
                    "unread", Map.of("ZZ.REQ", 1)), second);
            assertEquals(3, put[0]);

            Map<String, Object> third = Adapter.mqStep(browser, db, JSON.readTree("""
                    {"queue": "ZZ.REQ", "trigger": "ZZEC", "messages": []}"""), put);
            assertEquals(Map.of("mq", Map.of(), "unread", Map.of("ZZ.REQ", 1), "abend", "TEST"), third);

            region.install(EnumSet.noneOf(Extension.class));
            Map<String, Object> undefined = Adapter.mqStep(browser, db, JSON.readTree("""
                    {"queue": "ZZ.REQ", "trigger": "CDRD"}"""), put);
            assertEquals("transaction CDRD is not defined", undefined.get("runtime_error"));
        }
    }
}
