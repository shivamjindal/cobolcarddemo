package com.carddemo.batch.programs;

import static com.carddemo.batch.jcl.Dd.sysout;
import static com.carddemo.batch.jcl.StepDef.program;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.library.LibraryJob;
import com.carddemo.batch.runtime.BatchRuntime;
import com.carddemo.batch.runtime.Ran;
import com.carddemo.batch.runtime.RunIdentity;
import com.carddemo.batch.runtime.Spool;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExtractProgramsIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private BatchRuntime runtime;

    @Autowired
    private Spool spool;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void eachLibraryJobListsItsClusterBetweenTheBannersAndEndsRcZero() {
        Map<LibraryJob, String> tables = Map.of(LibraryJob.READCARD, "cards", LibraryJob.READXREF, "card_xref",
                LibraryJob.READCUST, "customers");
        Map<LibraryJob, Integer> printsPerRecord = Map.of(LibraryJob.READCARD, 1, LibraryJob.READXREF, 2,
                LibraryJob.READCUST, 2);
        for (LibraryJob job : LibraryJob.values()) {
            String program = job.job().steps().getFirst().program();
            int records = jdbc.sql("select count(*) from " + tables.get(job)).query(Integer.class).single();
            Ran ran = run(job.job());
            List<String> sysout = displayed(ran, "STEP05");

            assertEquals("RC=0", ran.completion().label(), job.name());
            assertEquals("START OF EXECUTION OF PROGRAM " + program, sysout.getFirst());
            assertEquals("END OF EXECUTION OF PROGRAM " + program, sysout.getLast());
            assertEquals(records * printsPerRecord.get(job), sysout.size() - 2, job.name());
        }
    }

    @Test
    void aFileThatDoesNotOpenShowsItsStatusAndAbendsU0999() {
        Ran ran = run(JobDef.of("NOCUST", program("STEP05", Cbcus01c.NAME, sysout("SYSOUT"))));

        assertEquals("U0999", ran.completion().label());
        assertEquals(List.of("START OF EXECUTION OF PROGRAM CBCUS01C", "ERROR OPENING CUSTFILE", "FILE STATUS IS: NNNN0035",
                "ABENDING PROGRAM"), displayed(ran, "STEP05"));
    }

    private Ran run(JobDef job) {
        long id = jdbc.sql("insert into job_submissions (queue, job_name, jcl, submitted_by, submitted_at)"
                        + " values ('JOBS', 'TEST', ?, 'TEST', ?) returning id")
                .params(new String[] {"//TEST JOB"}, LocalDateTime.now()).query(Long.class).single();
        jdbc.sql("insert into job_submission_runs (submission_id, claimed_at) values (?, ?)")
                .params(id, LocalDateTime.now()).update();
        return runtime.run(job, new RunIdentity.Submitted(id));
    }

    private List<String> displayed(Ran ran, String step) {
        return spool.read(ran.jobExecutionId(), step, Spool.DISPLAY).stream()
                .map(line -> new String(line, StandardCharsets.ISO_8859_1).stripTrailing()).toList();
    }
}
