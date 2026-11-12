package com.carddemo.batch;

import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What CORPT00C's EXEC CICS WRITEQ TD QUEUE('JOBS') of TRANREPT JCL became: starts the TRANREPT job for a date
 * window and returns at once. Every submission is its own run, even for a window reported before.
 */
@Component
public class ReportSubmission {

    private final BatchJobs jobs;
    private final Clock clock;
    private final TaskExecutor executor = new SimpleAsyncTaskExecutor("tranrept-");

    public ReportSubmission(BatchJobs jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    public void submit(LocalDate start, LocalDate end) {
        var parameters = new JobParametersBuilder()
                .addString(TransactionReport.START, start.toString())
                .addString(TransactionReport.END, end.toString())
                .addString("submission", UUID.randomUUID().toString())
                .addLocalDateTime("submitted", LocalDateTime.now(clock), false)
                .toJobParameters();
        executor.execute(() -> jobs.run("TRANREPT", parameters));
    }
}
