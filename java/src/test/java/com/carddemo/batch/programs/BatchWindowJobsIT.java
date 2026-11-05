package com.carddemo.batch.programs;

import static com.carddemo.batch.jcl.Dd.sysout;
import static com.carddemo.batch.jcl.StepDef.program;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
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
import com.carddemo.batch.jcl.Dd;
import com.carddemo.batch.jcl.DdSource;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.library.LibraryJob;
import com.carddemo.batch.runtime.BatchRuntime;
import com.carddemo.batch.runtime.Ran;
import com.carddemo.batch.runtime.RunIdentity;
import com.carddemo.batch.runtime.Spool;
import com.carddemo.cics.CicsFile;
import com.carddemo.cics.CicsRegion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLOSEFIL, OPENFIL and WAITSTEP as library jobs through the batch runtime, against the CICS region. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class BatchWindowJobsIT {

    private static final Set<CicsFile> CLOSEFIL_FILES =
            EnumSet.of(CicsFile.TRANSACT, CicsFile.CCXREF, CicsFile.ACCTDAT, CicsFile.CXACAIX, CicsFile.USRSEC);

    private static final List<Duration> slept = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class RecordedWaits {
        @Bean
        Cobswait.Sleeper recordingSleeper() {
            return slept::add;
        }
    }

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

    @Autowired
    private CicsRegion region;

    @BeforeEach
    @AfterEach
    void openAll() {
        jdbc.sql("update cics_region set closed = '{}'").update();
        slept.clear();
    }

    @Test
    void closefilClosesItsFiveFilesBatchStillReadsThemAndOpenfilReopensThem() {
        Ran closed = run(LibraryJob.CLOSEFIL.job());
        assertEquals("RC=0", closed.steps().get("CLCIFIL").label());
        assertEquals(CLOSEFIL_FILES, region.closed());

        int xrefs = jdbc.sql("select count(*) from card_xref").query(Integer.class).single();
        Ran listed = run(LibraryJob.READXREF.job());
        assertEquals("RC=0", listed.completion().label(), "batch does not go through CICS");
        assertEquals(2 + 2 * xrefs, displayed(listed, "STEP05").size());

        Ran opened = run(LibraryJob.OPENFIL.job());
        assertEquals("RC=0", opened.steps().get("OPCIFIL").label());
        assertEquals(Set.of(), region.closed());
    }

    @Test
    void waitstepWaitsTheThirtySixSecondsOnItsSysinCard() {
        Ran ran = run(LibraryJob.WAITSTEP.job());

        assertEquals("RC=0", ran.steps().get("WAIT").label());
        assertEquals(List.of(Duration.ofSeconds(36)), slept);
    }

    @Test
    void aCardTheRegionCannotApplyEndsRcEightAndTheOtherCardsStillApply() {
        Ran ran = run(JobDef.of("BADCEMT", program("CLCIFIL", Sdsf.NAME, sysout("ISFOUT"), instream("ISFIN",
                " /F CICSAWSA,'CEMT SET FIL(ACCTDAT ) CLO'",
                " /F CICSAWSA,'CEMT SET FIL(TRANSACX ) CLO'",
                " /F CICSAWSB,'CEMT SET FIL(USRSEC ) CLO'",
                " /F CICSAWSA,'CEMT SET FIL(CCXREF ) CLO'"))));

        assertEquals("RC=8", ran.completion().label());
        assertEquals(EnumSet.of(CicsFile.ACCTDAT, CicsFile.CCXREF), region.closed());
    }

    @Test
    void aWaitCardThatIsNotEightDigitsIsADataException() {
        Ran ran = run(JobDef.of("BADWAIT", program("WAIT", Cobswait.NAME, sysout("SYSOUT"),
                instream("SYSIN", "36 SECONDS"))));

        assertEquals("S0C7", ran.completion().label());
        assertTrue(slept.isEmpty());
    }

    private Ran run(JobDef job) {
        long id = jdbc.sql("insert into job_submissions (queue, job_name, jcl, submitted_by, submitted_at)"
                        + " values ('JOBS', 'TEST', ?, 'TEST', ?) returning id")
                .params(new String[] {"//TEST JOB"}, LocalDateTime.now()).query(Long.class).single();
        jdbc.sql("insert into job_submission_runs (submission_id, claimed_at) values (?, ?)")
                .params(id, LocalDateTime.now()).update();
        return runtime.run(job, new RunIdentity.Submitted(id));
    }

    private static Dd instream(String dd, String... cards) {
        return Dd.of(dd, new DdSource.Instream(List.of(cards)));
    }

    private List<String> displayed(Ran ran, String step) {
        return spool.read(ran.jobExecutionId(), step, Spool.DISPLAY).stream()
                .map(line -> new String(line, StandardCharsets.ISO_8859_1).stripTrailing()).toList();
    }
}
