package com.carddemo.mq;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.springframework.context.SmartLifecycle;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.SessionAwareMessageListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Starts one listener per {@link MessageProgram}, the way CKTI starts the program on a trigger. The get and the puts
 * share a transacted JMS session that commits after the database transaction, so a failure anywhere before the
 * database commit redelivers the request and sends nothing. A failure between the two commits redelivers a request
 * whose effects are already stored.
 */
@Component
class MessagePrograms implements SmartLifecycle {

    private final List<DefaultMessageListenerContainer> containers;
    private volatile boolean running;

    MessagePrograms(List<MessageProgram> programs, ConnectionFactory connections, PlatformTransactionManager database) {
        TransactionTemplate transaction = new TransactionTemplate(database);
        containers = programs.stream().map(program -> {
            DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
            container.setConnectionFactory(connections);
            container.setDestinationName(program.requestQueue());
            container.setSessionTransacted(true);
            container.setBeanName(program.program());
            container.setMessageListener((SessionAwareMessageListener<Message>) (message, session) ->
                    transaction.executeWithoutResult(status -> putAll(session, program.process(read(message)))));
            return container;
        }).toList();
    }

    private static MqMessage read(Message message) {
        try {
            String replyTo = message.getJMSReplyTo() instanceof Queue queue ? queue.getQueueName() : null;
            return new MqMessage(((TextMessage) message).getText(), message.getJMSCorrelationID(), replyTo);
        } catch (JMSException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void putAll(Session session, List<MqMessage.Put> puts) {
        try {
            for (MqMessage.Put put : puts) {
                TextMessage out = session.createTextMessage(put.message().text());
                out.setJMSCorrelationID(put.message().correlationId());
                try (MessageProducer producer = session.createProducer(session.createQueue(put.queue()))) {
                    producer.send(out);
                }
            }
        } catch (JMSException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void start() {
        containers.forEach(container -> {
            container.afterPropertiesSet();
            container.start();
        });
        running = true;
    }

    @Override
    public void stop() {
        containers.forEach(DefaultMessageListenerContainer::shutdown);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
