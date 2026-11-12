package com.carddemo.mq;

/** One message as the legacy programs see it: the text, MQMD-CORRELID, and MQMD-REPLYTOQ (null when unset). */
public record MqMessage(String text, String correlationId, String replyTo) {

    /** An MQPUT or MQPUT1 of {@code message} to {@code queue}. */
    public record Put(String queue, MqMessage message) {
    }

    public static Put put(String queue, String text, String correlationId) {
        return new Put(queue, new MqMessage(text, correlationId, null));
    }
}
