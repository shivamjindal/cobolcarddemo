package com.carddemo.online;

import com.carddemo.mq.MessageProgram;
import com.carddemo.mq.MqMessage;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A crash after the database commits and before the broker does: the first time COPAUA0C commits, the listener
 * fails before the JMS session commits, so the broker redelivers a request whose authorization is already stored.
 */
@SpringBootTest
@Import({TestDatabase.class, MessageRedeliveryTest.CrashAfterFirstCommit.class})
class MessageRedeliveryTest {

    private static final String REPLY_TO = "CARDDEMO.REDELIVERY.REPLY";
    private static final AtomicBoolean CRASHED = new AtomicBoolean();
    private static final AtomicInteger PROCESSED = new AtomicInteger();

    @TestConfiguration
    static class CrashAfterFirstCommit {

        @Bean
        static BeanPostProcessor crashAfterFirstCommit() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof MessageProgram program) || !program.program().equals("COPAUA0C")) {
                        return bean;
                    }
                    return new MessageProgram() {
                        @Override
                        public String program() {
                            return program.program();
                        }

                        @Override
                        public String requestQueue() {
                            return program.requestQueue();
                        }

                        @Override
                        public List<MqMessage.Put> process(MqMessage request) {
                            PROCESSED.incrementAndGet();
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    if (CRASHED.compareAndSet(false, true)) {
                                        throw new IllegalStateException("crash before the broker commits");
                                    }
                                }
                            });
                            return program.process(request);
                        }
                    };
                }
            };
        }
    }

    @Autowired
    Datasets datasets;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JmsTemplate jms;

    @Autowired
    List<MessageProgram> programs;

    @Test
    void aRedeliveredAuthorizationIsRepliedToAgainButStoredAndCountedOnce() throws Exception {
        datasets.reset(Map.of(
                "CCXREF", List.of(Map.of("XREF-CARD-NUM", "4111000011112222", "XREF-CUST-ID", "000000001",
                        "XREF-ACCT-ID", "00000000001")),
                "ACCTDAT", List.of(Map.of("ACCT-ID", "00000000001", "ACCT-ACTIVE-STATUS", "Y",
                        "ACCT-CURR-BAL", "300.00", "ACCT-CREDIT-LIMIT", "2000.00", "ACCT-CASH-CREDIT-LIMIT", "500.00")),
                "CUSTDAT", List.of(Map.of("CUST-ID", "000000001", "CUST-FIRST-NAME", "JOHN",
                        "CUST-LAST-NAME", "SMITH"))));
        MessageProgram authorizations = programs.stream().filter(p -> p.program().equals("COPAUA0C")).findFirst().orElseThrow();

        jms.send(authorizations.requestQueue(), session -> {
            TextMessage request = session.createTextMessage("260115,103015,4111000011112222,0100,1228,0200,POS,000000,"
                    + "125.50,5411,USA,01,123501000675423,CORNER GROCERY,SEATTLE,WA,98101,TXN000000000101");
            request.setJMSCorrelationID("REQ00001");
            request.setJMSReplyTo(session.createQueue(REPLY_TO));
            return request;
        });

        jms.setReceiveTimeout(10_000);
        Message reply = jms.receive(REPLY_TO);
        assertThat(reply).isNotNull();
        assertThat(((TextMessage) reply).getText()).startsWith("4111000011112222,TXN000000000101,");
        assertThat(reply.getJMSCorrelationID()).isEqualTo("REQ00001");
        jms.setReceiveTimeout(1_000);
        assertThat(jms.receive(REPLY_TO)).as("a second reply").isNull();

        assertThat(CRASHED).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_auth_details WHERE pa_acct_id = 1", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT pa_approved_auth_cnt, pa_approved_auth_amt::text AS amt"
                + " FROM pending_auth_summaries WHERE pa_acct_id = 1"))
                .containsEntry("pa_approved_auth_cnt", 1).containsEntry("amt", "125.50");
        assertThat(PROCESSED).hasValue(1);
    }
}
