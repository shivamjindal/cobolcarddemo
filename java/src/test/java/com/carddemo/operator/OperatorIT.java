package com.carddemo.operator;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.batch.chains.Chains;
import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.Dd;
import com.carddemo.batch.jcl.DdSource;
import com.carddemo.batch.jcl.Disposition;
import com.carddemo.batch.jcl.DsnRef;
import com.carddemo.batch.jcl.Gdg;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.jcl.StepDef;
import com.carddemo.batch.runtime.BatchRuntime;
import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.batch.runtime.Ran;
import com.carddemo.batch.runtime.RunIdentity;
import com.carddemo.jobs.JobQueue;
import com.carddemo.batch.utilities.Idcams;
import com.carddemo.cics.CicsFile;
import com.carddemo.cics.CicsRegion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class OperatorIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private Operator operator;

    @Autowired
    private BatchRuntime runtime;

    @Autowired
    private DatasetStore datasets;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JobQueue queue;

    @Autowired
    private CicsRegion region;

    @Autowired
    private Chains chains;

    @Test
    void outputShowsTheClaimTheStepsAndTheSpoolAndPrintShowsLatin1Records() {
        datasets.deleteCataloged("TEST.OPERATOR.IN");
        datasets.deleteCataloged("TEST.OPERATOR.OUT");
        byte[] record = "OPERATOR-LINE".getBytes(ISO_8859_1);
        datasets.stage("TEST.OPERATOR.IN", new Dcb(Dcb.Recfm.F, record.length), Optional.empty(), List.of(record));
        long submission = submit();
        Ran ran = runtime.run(JobDef.of("OPRINT", StepDef.program("STEP01", Idcams.NAME, Dd.sysout("SYSPRINT"),
                Dd.shr("IN", "TEST.OPERATOR.IN"),
                Dd.of("OUT", new DdSource.Dataset(new DsnRef.Named("TEST.OPERATOR.OUT"),
                        new Disposition(Disposition.Status.NEW, Disposition.End.CATLG, Disposition.End.DELETE),
                        Optional.of(new Dcb(Dcb.Recfm.F, record.length)))),
                Dd.of("SYSIN", new DdSource.Instream(List.of(" REPRO INFILE(IN) OUTFILE(OUT)"))))),
                new RunIdentity.Submitted(submission));
        queue.ended(submission, OptionalLong.of(ran.jobExecutionId()), ran.completion().label());

        String printed = capture(() -> assertEquals(0, operator.execute(List.of("print", "TEST.OPERATOR.OUT"))));
        assertEquals("OPERATOR-LINE\n", printed);
        String shown = capture(() -> assertEquals(0, operator.execute(List.of("output", Long.toString(submission)))));
        assertTrue(shown.contains("submission " + submission + " RC=0"), shown);
        assertTrue(shown.contains("STEP01 RC=0"), shown);
        assertTrue(shown.contains("NUMBER OF RECORDS PROCESSED WAS 1"), shown);

        datasets.stage("TEST.OPERATOR.IN", new Dcb(Dcb.Recfm.F, record.length), Optional.empty(), List.of(record));
        long generation = submit();
        runtime.run(JobDef.of("OGEN", StepDef.program("STEP01", Idcams.NAME, Dd.sysout("SYSPRINT"),
                Dd.shr("IN", "TEST.OPERATOR.IN"),
                Dd.of("OUT", new DdSource.Dataset(new DsnRef.Generation(Gdg.TRANREPT, 1),
                        new Disposition(Disposition.Status.NEW, Disposition.End.CATLG, Disposition.End.DELETE),
                        Optional.of(new Dcb(Dcb.Recfm.F, record.length)))),
                Dd.of("SYSIN", new DdSource.Instream(List.of(" REPRO INFILE(IN) OUTFILE(OUT)"))))),
                new RunIdentity.Submitted(generation));
        String newest = capture(() -> assertEquals(0,
                operator.execute(List.of("print", Gdg.TRANREPT.base() + "(0)"))));
        assertTrue(newest.contains("OPERATOR-LINE"), newest);
        assertEquals(4, operator.execute(List.of("print", "TEST.OPERATOR.MISSING")));
    }

    @Test
    void receiveCatalogsAFeedDropPaddedToTheRecordLength() throws Exception {
        Path feed = Files.createTempFile("dalytran", ".txt");
        Files.writeString(feed, "FIRST\nSECOND RECORD\n", ISO_8859_1);
        assertEquals(0, operator.execute(List.of("receive", "TEST.OPERATOR.FEED", "20", feed.toString())));
        assertEquals(List.of("FIRST               ", "SECOND RECORD       "), datasets.read("TEST.OPERATOR.FEED")
                .orElseThrow().stream().map(r -> new String(r, ISO_8859_1)).toList());
        assertEquals(0, operator.execute(List.of("receive", "TEST.OPERATOR.FEED", "20", feed.toString())));
        assertEquals(2, datasets.read("TEST.OPERATOR.FEED").orElseThrow().size());
        assertEquals(8, operator.execute(List.of("receive", "TEST.OPERATOR.FEED", "3", feed.toString())));
    }

    @Test
    void cemtClosesAndOpensAFileAndChainsListsWhatCanRun() {
        try {
            assertEquals(0, operator.execute(List.of("cemt", "close", "TRANSACT")));
            assertTrue(region.closed().contains(CicsFile.TRANSACT));
            assertEquals(0, operator.execute(List.of("cemt", "open", "TRANSACT")));
            assertFalse(region.closed().contains(CicsFile.TRANSACT));
        } finally {
            region.open(CicsFile.TRANSACT);
        }
        assertEquals(8, operator.execute(List.of("cemt", "close", "NOTAFILE")));

        String listed = capture(() -> assertEquals(0, operator.execute(List.of("chains"))));
        assertTrue(listed.contains("CA7_030_POSTING runnable"), listed);
        assertTrue(listed.contains("CONTROLM_MONTHLY_INTEREST runnable"), listed);
        assertTrue(listed.contains("CA7_031_CATBAL cannot run: PRTCATBL"), listed);
        assertEquals(chains.all().size(), listed.strip().lines().count());
        String absent = LocalDate.of(2099, 1, 1).plusDays(Math.floorMod(System.nanoTime(), 3000)).toString();
        String refused = captureErr(() -> assertEquals(4, operator.execute(List.of("abandon", "CA7_030_POSTING", absent))));
        assertTrue(refused.contains("has not started"), refused);
        assertEquals(8, operator.execute(List.of("restart", "CA7_030_POSTING")));
    }

    private long submit() {
        long id = jdbc.sql("insert into job_submissions (queue, job_name, jcl, submitted_by, submitted_at)"
                        + " values ('JOBS', 'OPRINT', ?, 'TEST', ?) returning id")
                .params(new String[] {"//OPRINT JOB"}, LocalDateTime.now()).query(Long.class).single();
        jdbc.sql("insert into job_submission_runs (submission_id, claimed_at) values (?, ?)")
                .params(id, LocalDateTime.now()).update();
        return id;
    }

    private static String capture(Runnable action) {
        return capture(action, false);
    }

    private static String captureErr(Runnable action) {
        return capture(action, true);
    }

    private static String capture(Runnable action, boolean error) {
        PrintStream previous = error ? System.err : System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream captured = new PrintStream(buffer, true, ISO_8859_1);
        if (error) {
            System.setErr(captured);
        } else {
            System.setOut(captured);
        }
        try {
            action.run();
            return buffer.toString(ISO_8859_1);
        } finally {
            if (error) {
                System.setErr(previous);
            } else {
                System.setOut(previous);
            }
        }
    }
}
