package com.carddemo.batch.programs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.Dd;
import com.carddemo.batch.jcl.DdSource;
import com.carddemo.batch.jcl.Disposition;
import com.carddemo.batch.jcl.DsnRef;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.jcl.StepDef;
import com.carddemo.batch.runtime.BatchRuntime;
import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.batch.runtime.Ran;
import com.carddemo.batch.runtime.RunIdentity;
import com.carddemo.batch.runtime.Spool;
import com.carddemo.records.KeyedTable;
import com.carddemo.vsam.KeyRange;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StatementsIT {

    private static final String TRXFL = "AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private BatchRuntime runtime;

    @Autowired
    private DatasetStore datasets;

    @Autowired
    private Spool spool;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void anEmptyTransactionFileAbendsOnTheFirstRead() {
        datasets.stage(TRXFL, new Dcb(Dcb.Recfm.F, 350), Optional.of(new KeyRange(0, 32)), List.of());
        Ran ran = run(JobDef.of("CREASTMT", StepDef.program("STEP040", Cbstm03a.NAME,
                keyed("TRNXFILE", TRXFL),
                Dd.shr("XREFFILE", KeyedTable.CARDXREF.dsn()),
                Dd.shr("CUSTFILE", KeyedTable.CUSTDATA.dsn()),
                Dd.shr("ACCTFILE", KeyedTable.ACCTDATA.dsn()),
                sysout("STMTFILE", new Dcb(Dcb.Recfm.F, 80)),
                sysout("HTMLFILE", new Dcb(Dcb.Recfm.F, 100)))));

        assertEquals("ABEND-NO-CODE", ran.completion().label());
        assertEquals(List.of("ERROR READING TRNXFILE", "RETURN CODE: 10", "ABENDING PROGRAM"),
                spool.read(ran.jobExecutionId(), "STEP040", Spool.DISPLAY).stream()
                        .map(line -> new String(line, ISO_8859_1).stripTrailing()).toList());
    }

    private static Dd keyed(String name, String dsn) {
        return Dd.of(name, new DdSource.Dataset(new DsnRef.Named(dsn), Disposition.SHR, Optional.empty()));
    }

    private static Dd sysout(String name, Dcb dcb) {
        return Dd.of(name, new DdSource.Sysout(Optional.of(dcb)));
    }

    private Ran run(JobDef job) {
        long id = jdbc.sql("insert into job_submissions (queue, job_name, jcl, submitted_by, submitted_at)"
                        + " values ('JOBS', 'CREASTMT', ?, 'TEST', ?) returning id")
                .params(new String[] {"//CREASTMT JOB"}, LocalDateTime.now()).query(Long.class).single();
        jdbc.sql("insert into job_submission_runs (submission_id, claimed_at) values (?, ?)")
                .params(id, LocalDateTime.now()).update();
        return runtime.run(job, new RunIdentity.Submitted(id));
    }
}
