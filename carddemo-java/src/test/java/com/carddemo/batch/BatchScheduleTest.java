package com.carddemo.batch;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchScheduleTest {

    private final BatchJobs jobs = mock(BatchJobs.class);
    private final BatchSchedule schedule = new BatchSchedule(jobs,
            Clock.fixed(Instant.parse("2022-07-18T01:00:00Z"), ZoneOffset.UTC), "/feeds/dalytran", "EBCDIC");

    @Test
    void theDailyChainIsIdentifiedByTheBusinessDateAndGetsTheFeed() {
        when(jobs.run(eq("DAILY"), any())).thenReturn(new JobExecution(1L));

        schedule.daily();

        ArgumentCaptor<JobParameters> parameters = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobs).run(eq("DAILY"), parameters.capture());
        assertThat(parameters.getValue().getString("date")).isEqualTo("2022-07-18");
        assertThat(parameters.getValue().getString(DailyPosting.FEED)).isEqualTo("/feeds/dalytran");
        assertThat(parameters.getValue().getString(DailyPosting.FORMAT)).isEqualTo("EBCDIC");
    }

    @Test
    void theMonthlyChainGetsTheIntcalcParmOfItsDate() {
        when(jobs.run(eq("MONTHLY"), any())).thenReturn(new JobExecution(1L));

        schedule.monthly();

        ArgumentCaptor<JobParameters> parameters = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobs).run(eq("MONTHLY"), parameters.capture());
        assertThat(parameters.getValue().getString(InterestCalculation.PARM)).isEqualTo("2022071800");
    }

    @Test
    void aDayThatAlreadyRanIsLoggedNotThrown() {
        when(jobs.run(eq("DAILY"), any())).thenThrow(new IllegalStateException("Could not launch DAILY"));

        schedule.daily();
    }
}
