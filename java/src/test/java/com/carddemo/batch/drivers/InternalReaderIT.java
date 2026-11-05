package com.carddemo.batch.drivers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.batch.runtime.Spool;
import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.Step;
import com.carddemo.jobs.JobQueue;
import com.carddemo.jobs.JobSubmissions;
import com.carddemo.jobs.SubmittedJob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"carddemo.batch.drivers.enabled=true", "carddemo.batch.reader.poll=PT0.05S"})
@DirtiesContext
class InternalReaderIT {

    @TestConfiguration
    static class Programs {

        /** DISPLAYs its PARM and ends RC=4. */
        @Bean
        BatchProgram echo() {
            return new BatchProgram() {
                @Override
                public String name() {
                    return "ECHO";
                }

                @Override
                public int run(Step step) {
                    step.display(step.parm());
                    return 4;
                }
            };
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private JobSubmissions submissions;

    @Autowired
    private JobQueue queue;

    @Autowired
    private Spool spool;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void empty() {
        jdbc.sql("delete from job_submissions").update();
    }

    @Test
    void runsEachDeckOnceInSubmissionOrderAndEndsADeckThatDoesNotParseAsAJclError() throws Exception {
        submit("//ECHO JOB", "//STEP01 EXEC PGM=ECHO,PARM='FIRST'");
        submit("//STEP01 EXEC PGM=ECHO,PARM='NO JOB CARD'");
        submit("//ECHO JOB", "//STEP01 EXEC PGM=ECHO,PARM='THIRD'");
        List<JobQueue.Run> runs = jdbc.sql("select id from job_submissions order by id").query(Long.class).list()
                .stream().map(this::ended).toList();

        assertEquals(List.of("RC=4", "JCL ERROR", "RC=4"), runs.stream().map(r -> r.completion().orElseThrow()).toList());
        assertTrue(runs.get(1).jobExecutionId().isEmpty());
        long first = runs.get(0).jobExecutionId().getAsLong();
        long third = runs.get(2).jobExecutionId().getAsLong();
        assertTrue(first < third, first + " ran after " + third);
        assertEquals(List.of("THIRD"), spool.read(third, "STEP01", Spool.DISPLAY).stream()
                .map(line -> new String(line, StandardCharsets.ISO_8859_1)).toList());
        assertEquals(3, jdbc.sql("select count(*) from job_submission_runs").query(Integer.class).single());
    }

    private void submit(String... cards) {
        assertTrue(submissions.submit(new SubmittedJob("JOBS", "ECHO", null, null, null, List.of(cards), "TESTER")));
    }

    private JobQueue.Run ended(long submission) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            JobQueue.Run run = queue.run(submission).orElse(null);
            if (run != null && run.endedAt().isPresent()) {
                return run;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("submission " + submission + " did not end");
    }
}
