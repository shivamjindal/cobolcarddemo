package com.carddemo.batch.programs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.library.LibraryJob;
import com.carddemo.batch.runtime.BatchRuntime;
import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.batch.runtime.Ran;
import com.carddemo.batch.runtime.RunIdentity;
import com.carddemo.records.Fields;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class Mnttrdb2IT {

    private static final String TYPE = "98";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private BatchRuntime runtime;

    @Autowired
    private DatasetStore datasets;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from carddemo.transaction_type where tr_type = ?").param(TYPE).update();
        datasets.deleteCataloged("INPFILE");
    }

    @Test
    void runProgramUnderDsnRunsCobtupdtOnTheStepsInpfile() {
        stage("A" + TYPE + "Weekly refresh", "U" + TYPE + "Weekly disclosure refresh");

        Ran ran = run();

        assertEquals("RC=0", ran.completion().label());
        assertEquals(List.of("Weekly disclosure refresh"), jdbc.sql(
                "select tr_description from carddemo.transaction_type where tr_type = ?").param(TYPE)
                .query(String.class).list().stream().map(String::stripTrailing).toList());
    }

    @Test
    void cobtupdtsReturnCodeEndsTheStep() {
        stage("D" + TYPE);

        assertEquals("RC=4", run().completion().label());
    }

    private void stage(String... lines) {
        datasets.stage("INPFILE", new Dcb(Dcb.Recfm.F, 53), Optional.empty(),
                List.of(lines).stream().map(line -> {
                    byte[] record = new byte[53];
                    Fields.text(record, 0, 53, line);
                    return record;
                }).toList());
    }

    private Ran run() {
        long id = jdbc.sql("insert into job_submissions (queue, job_name, jcl, submitted_by, submitted_at)"
                        + " values ('JOBS', 'MNTTRDB2', ?, 'TEST', ?) returning id")
                .params(new String[] {"//MNTTRDB2 JOB"}, LocalDateTime.now()).query(Long.class).single();
        jdbc.sql("insert into job_submission_runs (submission_id, claimed_at) values (?, ?)")
                .params(id, LocalDateTime.now()).update();
        return runtime.run(LibraryJob.MNTTRDB2.job(), new RunIdentity.Submitted(id));
    }
}
