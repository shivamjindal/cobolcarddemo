package com.carddemo.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Starts the {@link Chain}s on the legacy calendar. The business date (CA-7's ODATE) identifies each run: a
 * second trigger for a completed day does nothing, and a trigger for a failed day resumes it at the failed job.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty("carddemo.batch.schedule.enabled")
public class BatchSchedule {

    private static final Logger log = LoggerFactory.getLogger(BatchSchedule.class);

    private final BatchJobs jobs;
    private final Clock clock;
    private final String feed;
    private final String format;

    public BatchSchedule(BatchJobs jobs, Clock clock, @Value("${carddemo.batch.daily-feed}") String feed,
                         @Value("${carddemo.batch.daily-feed-format}") String format) {
        this.jobs = jobs;
        this.clock = clock;
        this.feed = feed;
        this.format = format;
    }

    @Scheduled(cron = "${carddemo.batch.schedule.daily}")
    void daily() {
        run(Chain.DAILY, dated().addString(DailyPosting.FEED, feed).addString(DailyPosting.FORMAT, format)
                .toJobParameters());
    }

    @Scheduled(cron = "${carddemo.batch.schedule.monthly}")
    void monthly() {
        String parm = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE) + "00";
        run(Chain.MONTHLY, dated().addString(InterestCalculation.PARM, parm).toJobParameters());
    }

    private JobParametersBuilder dated() {
        return new JobParametersBuilder().addString("date", LocalDate.now(clock).toString());
    }

    private void run(Chain chain, JobParameters parameters) {
        try {
            log.info("{} chain ended {}", chain, jobs.run(chain.name(), parameters).getStatus());
        } catch (IllegalStateException e) {
            log.warn("{} chain not started: {}", chain, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        }
    }
}
