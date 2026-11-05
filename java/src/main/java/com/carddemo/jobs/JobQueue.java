package com.carddemo.jobs;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The internal reader's side of TD queue JOBS. A {@code job_submission_runs} row claims a submission, so the
 * queue stays append-only and only its requesters write it.
 */
@Component
public final class JobQueue {

    /** A submission the caller now owns, with its cards. */
    public record Claimed(long id, List<String> jcl) {}

    /** A claim; {@code endedAt} is empty while its job runs. */
    public record Run(long submissionId, OptionalLong jobExecutionId, Optional<String> completion,
            Optional<LocalDateTime> endedAt) {}

    private static final String QUEUE = "JOBS";

    private final JdbcClient jdbc;
    private final Clock clock;

    public JobQueue(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Claims the oldest unclaimed submission. Empty when there is none, or another reader took it first. */
    public Optional<Claimed> claimNext() {
        return jdbc.sql("""
                        with claimed as (
                            insert into job_submission_runs (submission_id, claimed_at)
                            select id, ? from job_submissions s
                            where queue = ? and not exists (select 1 from job_submission_runs r where r.submission_id = s.id)
                            order by id limit 1
                            on conflict do nothing
                            returning submission_id)
                        select s.id, s.jcl from job_submissions s join claimed c on c.submission_id = s.id""")
                .params(LocalDateTime.now(clock), QUEUE)
                .query((rs, row) -> new Claimed(rs.getLong(1), Arrays.asList((String[]) rs.getArray(2).getArray())))
                .optional();
    }

    /** Ends a claim with the job's completion label; a deck that never ran has no job execution. */
    public void ended(long submissionId, OptionalLong jobExecutionId, String completion) {
        jdbc.sql("update job_submission_runs set job_execution_id = ?, completion = ?, ended_at = ? where submission_id = ?")
                .params(jobExecutionId.isPresent() ? jobExecutionId.getAsLong() : null, completion,
                        LocalDateTime.now(clock), submissionId)
                .update();
    }

    public Optional<Run> run(long submissionId) {
        return jdbc.sql("select job_execution_id, completion, ended_at from job_submission_runs where submission_id = ?")
                .param(submissionId)
                .query((rs, row) -> {
                    long execution = rs.getLong(1);
                    OptionalLong jobExecutionId = rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(execution);
                    Timestamp endedAt = rs.getTimestamp(3);
                    return new Run(submissionId, jobExecutionId, Optional.ofNullable(rs.getString(2)),
                            Optional.ofNullable(endedAt).map(Timestamp::toLocalDateTime));
                })
                .optional();
    }
}
