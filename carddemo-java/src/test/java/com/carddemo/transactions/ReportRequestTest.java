package com.carddemo.transactions;

import com.carddemo.batch.ReportSubmission;
import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.SignedOnUser;
import com.carddemo.online.UserType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReportRequestTest {

    private final ReportSubmission submission = mock(ReportSubmission.class);

    @Test
    void monthlySubmitsTheCurrentMonthThroughItsLastDay() {
        assertThat(confirm(on(2028, 2, 14), Map.of("MONTHLY", "X"))).isEqualTo("Monthly report submitted for printing ...");
        verify(submission).submit(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29));
    }

    @Test
    void monthlyInDecemberEndsOnTheThirtyFirst() {
        confirm(on(2026, 12, 31), Map.of("MONTHLY", "X"));
        verify(submission).submit(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
    }

    @Test
    void yearlySubmitsTheWholeCurrentYear() {
        assertThat(confirm(on(2026, 11, 12), Map.of("YEARLY", "X"))).isEqualTo("Yearly report submitted for printing ...");
        verify(submission).submit(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    }

    @Test
    void customSubmitsTheTypedDatesEvenWhenTheyAreOutOfOrder() {
        assertThat(confirm(on(2026, 11, 12), Map.of("CUSTOM", "X", "SDTMM", "1", "SDTDD", "5", "SDTYYYY", "2026",
                "EDTMM", "03", "EDTDD", "31", "EDTYYYY", "2026"))).isEqualTo("Custom report submitted for printing ...");
        verify(submission).submit(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 3, 31));

        confirm(on(2026, 11, 12), Map.of("CUSTOM", "X", "SDTMM", "12", "SDTDD", "31", "SDTYYYY", "2026",
                "EDTMM", "01", "EDTDD", "01", "EDTYYYY", "2026"));
        verify(submission).submit(LocalDate.of(2026, 12, 31), LocalDate.of(2026, 1, 1));
    }

    @Test
    void nothingIsSubmittedUntilConfirmedWithY() {
        ReportRequest screen = new ReportRequest(submission, on(2026, 11, 12));
        for (String typed : new String[]{"", "N", "Q"}) {
            screen.receive(conversation(), Aid.ENTER, Map.of("MONTHLY", "X", "CONFIRM", typed));
        }
        screen.receive(conversation(), Aid.ENTER, Map.of("CUSTOM", "X", "SDTMM", "02", "SDTDD", "30", "SDTYYYY", "2026",
                "EDTMM", "03", "EDTDD", "31", "EDTYYYY", "2026", "CONFIRM", "Y"));
        verify(submission, never()).submit(any(), any());
    }

    private String confirm(Clock clock, Map<String, String> fields) {
        Map<String, String> confirmed = new HashMap<>(fields);
        confirmed.put("CONFIRM", "Y");
        Outcome outcome = new ReportRequest(submission, clock).receive(conversation(), Aid.ENTER, confirmed);
        return ((Outcome.Show) outcome).message().text();
    }

    private static Conversation conversation() {
        return new Conversation(new SignedOnUser("USER0001", UserType.USER), Program.COMEN01C);
    }

    private static Clock on(int year, int month, int day) {
        return Clock.fixed(LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }
}
