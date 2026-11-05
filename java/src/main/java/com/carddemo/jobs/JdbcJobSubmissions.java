package com.carddemo.jobs;

import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** One {@code job_submissions} row per job. */
@Component
public final class JdbcJobSubmissions implements JobSubmissions {

    private static final Logger log = LoggerFactory.getLogger(JdbcJobSubmissions.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcJobSubmissions(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean submit(SubmittedJob job) {
        try {
            jdbc.sql("insert into job_submissions (queue, job_name, procedure, start_date, end_date, jcl, submitted_by,"
                            + " submitted_at) values (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(job.queue(), job.jobName(), job.procedure(), job.startDate(), job.endDate(),
                            job.jcl().toArray(String[]::new), job.submittedBy(), LocalDateTime.now(clock))
                    .update();
            return true;
        } catch (DataAccessException e) {
            log.warn("WRITEQ TD {} job {} failed", job.queue(), job.jobName(), e);
            return false;
        }
    }
}
