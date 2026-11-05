package com.carddemo.vsammq;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.carddemo.cics.Syncpoint;
import com.carddemo.mq.MessageQueues;

/** CODATE01, transid CDRD: answers any request with the CICS date and time on CARD.DEMO.REPLY.DATE. */
@Component
public final class SystemDateInquiry extends MqInquiryProgram {

    static final String TRANSID = "CDRD";
    static final String REPLY_QUEUE = "CARD.DEMO.REPLY.DATE";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd-yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Clock clock;

    SystemDateInquiry(MessageQueues queues, Syncpoint syncpoint, Clock clock) {
        super(queues, syncpoint);
        this.clock = clock;
    }

    @Override
    public String transid() {
        return TRANSID;
    }

    @Override
    public String program() {
        return "CODATE01";
    }

    @Override
    String replyQueue() {
        return REPLY_QUEUE;
    }

    @Override
    Answer answer(byte[] request) {
        LocalDateTime now = LocalDateTime.now(clock);
        return new Answer.Reply("SYSTEM DATE : " + now.format(DATE) + "SYSTEM TIME : " + now.format(TIME));
    }
}
