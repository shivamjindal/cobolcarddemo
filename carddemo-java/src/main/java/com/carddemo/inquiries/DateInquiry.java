package com.carddemo.inquiries;

import com.carddemo.mq.MessageProgram;
import com.carddemo.mq.MqMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** CODATE01. Replies with the system date and time on CARD.DEMO.REPLY.DATE. The request text is not read. */
@Component
class DateInquiry implements MessageProgram {

    static final String REPLY = "CARD.DEMO.REPLY.DATE";

    /** FORMATTIME MMDDYYYY DATESEP('-') and TIME with the default TIMESEP. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd-yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Clock clock;
    private final String requestQueue;

    DateInquiry(Clock clock, @Value("${carddemo.mq.date-request:CARD.DEMO.REQUEST.DATE}") String requestQueue) {
        this.clock = clock;
        this.requestQueue = requestQueue;
    }

    @Override
    public String program() {
        return "CODATE01";
    }

    @Override
    public String requestQueue() {
        return requestQueue;
    }

    @Override
    public List<MqMessage.Put> process(MqMessage request) {
        LocalDateTime now = LocalDateTime.now(clock);
        String text = "SYSTEM DATE : " + DATE.format(now) + "SYSTEM TIME : " + TIME.format(now);
        return List.of(MqMessage.put(REPLY, text, request.correlationId()));
    }
}
