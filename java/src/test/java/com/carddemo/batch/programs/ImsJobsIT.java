package com.carddemo.batch.programs;

import static com.carddemo.batch.jcl.Dd.sysout;
import static com.carddemo.batch.jcl.StepDef.program;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

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
import com.carddemo.batch.ims.Dfsrrc00;
import com.carddemo.batch.ims.DliProgram;
import com.carddemo.batch.ims.ImsRegion;
import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.library.LibraryJob;
import com.carddemo.batch.runtime.BatchRuntime;
import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.batch.runtime.Ran;
import com.carddemo.batch.runtime.RunIdentity;
import com.carddemo.batch.step.Step;
import com.carddemo.characterization.SeededTable;
import com.carddemo.dli.Dbpautp0;
import com.carddemo.dli.DliStatus;
import com.carddemo.dli.Get;
import com.carddemo.dli.Pcb;
import com.carddemo.dli.PendingAuthDetail;
import com.carddemo.dli.PendingAuthSummary;
import com.carddemo.dli.Ssa;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CBPAUP0J, UNLDPADB, LOADPADB and UNLDGSAM as library jobs through DFSRRC00, on the seeded DBPAUTP0. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class ImsJobsIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REGISTRY = Path.of("..", "characterization", "fixtures", "datasets.json");
    private static final String ROOT_FILE = "AWS.M2.CARDDEMO.PAUTDB.ROOT.FILEO";
    private static final String CHILD_FILE = "AWS.M2.CARDDEMO.PAUTDB.CHILD.FILEO";
    private static final String ROOT_GSAM = "AWS.M2.CARDDEMO.PAUTDB.ROOT.GSAM";
    private static final String CHILD_GSAM = "AWS.M2.CARDDEMO.PAUTDB.CHILD.GSAM";
    private static final long BLANK_KEY = 40404040404L;

    /** Deletes account 1, checkpoints, deletes account 5, then fails. */
    @TestConfiguration
    static class CheckpointThenFail {
        @Bean
        DliProgram checkpointThenFail() {
            return new DliProgram() {
                @Override
                public String name() {
                    return "IMSTEST";
                }

                @Override
                public String psb() {
                    return "PSBPAUTB";
                }

                @Override
                public int run(Step step, ImsRegion region) {
                    delete(region.database(), 1);
                    region.checkpoint();
                    delete(region.database(), 5);
                    throw new IllegalStateException("after the checkpoint");
                }

                private void delete(Pcb pcb, long acctId) {
                    Get<PendingAuthSummary> root = pcb.gu(Ssa.summary(acctId));
                    assertEquals(DliStatus.OK, pcb.dlet(root.segment().orElseThrow()));
                }
            };
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private BatchRuntime runtime;

    @Autowired
    private DatasetStore datasets;

    @Autowired
    private Dbpautp0 dbpautp0;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    @AfterEach
    void freshSeed() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            SeededTable.seed(connection, SeededTable.all(JSON.readTree(REGISTRY.toFile()),
                    REGISTRY.toAbsolutePath().normalize().getParent().getParent().getParent()), JSON.createObjectNode());
        }
        for (String dsn : List.of(ROOT_FILE, CHILD_FILE, ROOT_GSAM, CHILD_GSAM)) {
            datasets.deleteCataloged(dsn);
        }
    }

    @Test
    void unloadThenLoadRebuildsTheDatabaseWithoutTheBlankKeyRoot() {
        List<Object> seeded = walk().stream().filter(segment -> !blankRoot(segment)).toList();
        stageEmptyUnload();

        Ran unloaded = run(LibraryJob.UNLDPADB.job());

        assertEquals("RC=0", unloaded.completion().label());
        List<byte[]> roots = datasets.read(ROOT_FILE).orElseThrow();
        List<byte[]> children = datasets.read(CHILD_FILE).orElseThrow();
        assertEquals(seeded.stream().filter(PendingAuthSummary.class::isInstance).count(), roots.size());
        assertEquals(seeded.stream().filter(PendingAuthDetail.class::isInstance).count(), children.size());

        jdbc.sql("delete from pending_auth_summaries").update();
        Ran loaded = run(LibraryJob.LOADPADB.job());

        assertEquals("RC=0", loaded.completion().label());
        assertEquals(seeded, walk());
    }

    @Test
    void eachChildRecordCarriesItsRootKey() {
        stageEmptyUnload();

        run(LibraryJob.UNLDPADB.job());

        List<byte[]> children = datasets.read(CHILD_FILE).orElseThrow();
        assertFalse(children.isEmpty());
        for (byte[] child : children) {
            long acctId = PendingAuthSummary.keyOf(child, 0);
            PendingAuthDetail detail = PendingAuthDetail.of(Arrays.copyOfRange(child, 6, 206));
            assertEquals(DliStatus.OK, dbpautp0.schedule().gu(Ssa.summary(acctId)).status());
            assertTrue(walkUnder(acctId).contains(detail), acctId + " " + detail.key());
        }
    }

    @Test
    void unloadWithNoEarlierUnloadIsAJclError() {
        Ran ran = run(LibraryJob.UNLDPADB.job());

        assertEquals("JCL ERROR", ran.completion().label());
        assertTrue(datasets.read(ROOT_FILE).isEmpty());
    }

    @Test
    void loadingRootsAlreadyThereSaysSoAndEndsRcZero() {
        stageEmptyUnload();
        run(LibraryJob.UNLDPADB.job());
        List<Object> before = walk();

        Ran ran = run(LibraryJob.LOADPADB.job());

        assertEquals("RC=0", ran.completion().label());
        assertEquals(before, walk());
    }

    @Test
    void gsamUnloadWritesRootsAndDependentsWithoutTheirRootKey() {
        List<Object> seeded = walk().stream().filter(segment -> !blankRoot(segment)).toList();
        datasets.stage(ROOT_GSAM, new Dcb(Dcb.Recfm.F, PendingAuthSummary.LENGTH), Optional.empty(), List.of());
        datasets.stage(CHILD_GSAM, new Dcb(Dcb.Recfm.F, PendingAuthDetail.LENGTH), Optional.empty(), List.of());

        Ran ran = run(LibraryJob.UNLDGSAM.job());

        assertEquals("RC=0", ran.completion().label());
        assertEquals(seeded.stream().filter(PendingAuthSummary.class::isInstance)
                .map(segment -> hex(((PendingAuthSummary) segment).image())).toList(),
                datasets.read(ROOT_GSAM).orElseThrow().stream().map(ImsJobsIT::hex).toList());
        assertEquals(seeded.stream().filter(PendingAuthDetail.class::isInstance)
                .map(segment -> hex(((PendingAuthDetail) segment).image())).toList(),
                datasets.read(CHILD_GSAM).orElseThrow().stream().map(ImsJobsIT::hex).toList());
    }

    @Test
    void gsamUnloadNeedsItsDataSetsCataloged() {
        assertEquals("JCL ERROR", run(LibraryJob.UNLDGSAM.job()).completion().label());
    }

    @Test
    void purgeOnTheSeedDeletesAccountOnesDetailsAndEndsRcSixteenOnTheSummaryDlet() {
        int details = count("pending_auth_details");

        Ran ran = run(LibraryJob.CBPAUP0J.job());

        assertEquals("RC=16", ran.completion().label());
        assertEquals(0, jdbc.sql("select count(*) from pending_auth_details where acct_id = 1").query(Integer.class).single());
        assertEquals(details - 6, count("pending_auth_details"));
        assertEquals(DliStatus.OK, dbpautp0.schedule().gu(Ssa.summary(1)).status());
    }

    @Test
    void anAbendBacksOutToTheLastCheckpoint() {
        Ran ran = run(dfsrrc00("DLI,IMSTEST,PSBPAUTB"));

        assertEquals("U4038", ran.completion().label());
        assertEquals(DliStatus.GE, dbpautp0.schedule().gu(Ssa.summary(1)).status());
        assertEquals(DliStatus.OK, dbpautp0.schedule().gu(Ssa.summary(5)).status());
    }

    @Test
    void aProgramDfsrrc00DoesNotHaveIsS806() {
        assertEquals("S806", run(dfsrrc00("DLI,NOSUCH,PSBPAUTB")).completion().label());
    }

    @Test
    void anImsProgramIsNotFoundByExecPgm() {
        Ran ran = run(JobDef.of("EXECPGM", program("STEP01", Cbpaup0c.NAME, sysout("SYSOUT"))));

        assertEquals("S806", ran.completion().label());
    }

    private void stageEmptyUnload() {
        datasets.stage(ROOT_FILE, new Dcb(Dcb.Recfm.F, 100), Optional.empty(), List.of());
        datasets.stage(CHILD_FILE, new Dcb(Dcb.Recfm.F, 206), Optional.empty(), List.of());
    }

    private static JobDef dfsrrc00(String parm) {
        return JobDef.of("IMSTEST", program("STEP01", Dfsrrc00.NAME, sysout("SYSOUT")).parm(parm));
    }

    /** The database in hierarchical sequence. */
    private List<Object> walk() {
        Pcb pcb = dbpautp0.schedule();
        List<Object> segments = new ArrayList<>();
        for (Get<PendingAuthSummary> root = pcb.gn(Ssa.summary()); root.ok(); root = pcb.gn(Ssa.summary())) {
            segments.add(root.segment().orElseThrow());
            for (Get<PendingAuthDetail> child = pcb.gnp(Ssa.detail()); child.ok(); child = pcb.gnp(Ssa.detail())) {
                segments.add(child.segment().orElseThrow());
            }
        }
        return segments;
    }

    private List<PendingAuthDetail> walkUnder(long acctId) {
        Pcb pcb = dbpautp0.schedule();
        pcb.gu(Ssa.summary(acctId));
        List<PendingAuthDetail> children = new ArrayList<>();
        for (Get<PendingAuthDetail> child = pcb.gnp(Ssa.detail()); child.ok(); child = pcb.gnp(Ssa.detail())) {
            children.add(child.segment().orElseThrow());
        }
        return children;
    }

    private static boolean blankRoot(Object segment) {
        return segment instanceof PendingAuthSummary root && root.acctId() == BLANK_KEY;
    }

    private int count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
    }

    private static String hex(byte[] record) {
        return HexFormat.of().formatHex(record);
    }

    private Ran run(JobDef job) {
        long id = jdbc.sql("insert into job_submissions (queue, job_name, jcl, submitted_by, submitted_at)"
                        + " values ('JOBS', 'TEST', ?, 'TEST', ?) returning id")
                .params(new String[] {"//TEST JOB"}, LocalDateTime.now()).query(Long.class).single();
        jdbc.sql("insert into job_submission_runs (submission_id, claimed_at) values (?, ?)")
                .params(id, LocalDateTime.now()).update();
        return runtime.run(job, new RunIdentity.Submitted(id));
    }
}
