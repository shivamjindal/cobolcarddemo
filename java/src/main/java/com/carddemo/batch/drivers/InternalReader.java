package com.carddemo.batch.drivers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.OptionalLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.carddemo.batch.jcl.JclDeck;
import com.carddemo.batch.library.Proc;
import com.carddemo.batch.runtime.BatchRuntime;
import com.carddemo.batch.runtime.Completion;
import com.carddemo.batch.runtime.Ran;
import com.carddemo.batch.runtime.RunIdentity;
import com.carddemo.jobs.JobQueue;

/**
 * TD queue JOBS to the batch runtime, as the JES internal reader. Every poll runs the unclaimed decks in
 * submission order, one at a time across every JVM on the database, as JES holds a second TRNRPT00 while one runs.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "carddemo.batch.drivers.enabled", havingValue = "true")
class InternalReader {

    private static final Logger log = LoggerFactory.getLogger(InternalReader.class);
    private static final String LOCK = "hashtext('carddemo internal reader'), 0";

    private final JobQueue queue;
    private final BatchRuntime runtime;
    private final DataSource dataSource;

    InternalReader(JobQueue queue, BatchRuntime runtime, DataSource dataSource) {
        this.queue = queue;
        this.runtime = runtime;
        this.dataSource = dataSource;
    }

    @Scheduled(fixedDelayString = "${carddemo.batch.reader.poll}")
    void poll() {
        try (Connection session = dataSource.getConnection()) {
            if (!lock(session, "pg_try_advisory_lock")) {
                return;
            }
            try {
                for (Optional<JobQueue.Claimed> claim = queue.claimNext(); claim.isPresent(); claim = queue.claimNext()) {
                    run(claim.get());
                }
            } finally {
                lock(session, "pg_advisory_unlock");
            }
        } catch (SQLException | RuntimeException e) {
            log.error("internal reader poll failed", e);
        }
    }

    private void run(JobQueue.Claimed claim) {
        switch (JclDeck.parse(claim.jcl(), Proc::byMember)) {
            case JclDeck.Parsed.Error error -> {
                log.warn("submission {}: JCL ERROR {}", claim.id(), error.reason());
                queue.ended(claim.id(), OptionalLong.empty(), new Completion.JclError(error.reason()).label());
            }
            case JclDeck.Parsed.Job job -> {
                Ran ran;
                try {
                    ran = runtime.run(job.job(), new RunIdentity.Submitted(claim.id()));
                } catch (RuntimeException e) {
                    log.error("submission {}: {} did not run", claim.id(), job.job().name(), e);
                    queue.ended(claim.id(), OptionalLong.empty(), "SYSTEM ERROR");
                    return;
                }
                log.info("submission {}: {} ended {}", claim.id(), job.job().name(), ran.completion().label());
                queue.ended(claim.id(), OptionalLong.of(ran.jobExecutionId()), ran.completion().label());
            }
        }
    }

    private static boolean lock(Connection session, String function) throws SQLException {
        try (PreparedStatement statement = session.prepareStatement("select " + function + "(" + LOCK + ")");
                ResultSet result = statement.executeQuery()) {
            return result.next() && result.getBoolean(1);
        }
    }
}
