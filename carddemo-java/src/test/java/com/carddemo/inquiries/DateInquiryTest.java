package com.carddemo.inquiries;

import com.carddemo.mq.MqMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DateInquiryTest {

    @Test
    void replyIsTheFormattedClockAndIgnoresTheRequestText() {
        Clock clock = Clock.fixed(LocalDateTime.of(2021, 3, 21, 15, 4, 5).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        DateInquiry inquiry = new DateInquiry(clock, "CARD.DEMO.REQUEST.DATE");

        List<String> texts = List.of("DATE00000001", "NOT-A-DATE-REQUEST").stream()
                .map(body -> inquiry.process(new MqMessage(body, "DATEREQ1", "IGNORED")).getFirst())
                .map(put -> {
                    assertThat(put.queue()).isEqualTo(DateInquiry.REPLY);
                    assertThat(put.message().correlationId()).isEqualTo("DATEREQ1");
                    return put.message().text();
                })
                .toList();

        assertThat(texts).containsOnly("SYSTEM DATE : 03-21-2021SYSTEM TIME : 15:04:05");
    }
}
