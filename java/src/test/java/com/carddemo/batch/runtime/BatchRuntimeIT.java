package com.carddemo.batch.runtime;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.batch.jcl.Cond;
import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.Dd;
import com.carddemo.batch.jcl.DdSource;
import com.carddemo.batch.jcl.Disposition;
import com.carddemo.batch.jcl.DsnRef;
import com.carddemo.batch.jcl.Gdg;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.jcl.StepDef;
import com.carddemo.batch.step.Abend;
import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.KeyedDataset;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.SequentialInput;
import com.carddemo.batch.step.SequentialOutput;
import com.carddemo.batch.programs.Iefbr14;
import com.carddemo.batch.step.Step;
import com.carddemo.records.KeyedTable;
import com.carddemo.records.RecordImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BatchRuntimeIT {

    private static final Disposition NEW_CATLG =
            new Disposition(Disposition.Status.NEW, Disposition.End.CATLG, Disposition.End.DELETE);
    private static final Dcb CARD = new Dcb(Dcb.Recfm.F, 80);

    record TestProgram(String name, ToIntFunction<Step> body) implements BatchProgram {
        @Override
        public int run(Step step) {
            return body.applyAsInt(step);
        }
    }

    @TestConfiguration
    static class Programs {

        /** Copies IN to OUT and returns its PARM as the return code. */
        @Bean
        BatchProgram copy() {
            return new TestProgram("COPY", step -> {
                SequentialInput in = step.input("IN");
                SequentialOutput out = step.output("OUT");
                in.open();
                out.open(OpenMode.OUTPUT);
                int copied = 0;
                for (Read read = in.read(); read instanceof Read.Found found; read = in.read()) {
                    out.write(found.record());
                    copied++;
                }
                step.display("COPIED " + copied);
                return step.parm().isEmpty() ? 0 : Integer.parseInt(step.parm());
            });
        }

        /** Writes a category and a record to OUT, then abends U0999 without closing either. */
        @Bean
        BatchProgram fail() {
            return new TestProgram("FAIL", step -> {
                KeyedDataset categories = step.keyed("TRANCATG");
                categories.open(OpenMode.I_O);
                categories.write(RecordImage.of(KeyedTable.TRANCATG, new Object[] {"Z7", 1, "kept"}));
                SequentialOutput out = step.output("OUT");
                out.open(OpenMode.OUTPUT);
                out.write(card("LOST"));
                step.display("ABENDING");
                throw Abend.user(999);
            });
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
    private Spool spool;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from datasets").update();
        jdbc.sql("delete from transaction_categories where tran_type_cd = 'Z7'").update();
    }

    @Test
    void condBypassesAStepWhenATestHoldsForAnEarlierReturnCode() {
        Ran ran = run(JobDef.of("CONDJOB",
                copy("STEP01", Dd.of("IN", new DdSource.Dummy())).parm("4"),
                copy("STEP02", Dd.of("IN", new DdSource.Dummy())).cond(cond(4, Cond.Operator.LT)),
                copy("STEP03", Dd.of("IN", new DdSource.Dummy())).cond(cond(0, Cond.Operator.NE)),
                copy("STEP04", Dd.of("IN", new DdSource.Dummy())).cond(cond(8, Cond.Operator.LT))));

        assertEquals(Map.of("STEP01", "RC=4", "STEP02", "RC=0", "STEP03", "BYPASSED", "STEP04", "RC=0"), labels(ran));
        assertEquals("RC=4", ran.completion().label());
        assertEquals(List.of("COPIED 0"), lines(spool.read(ran.jobExecutionId(), "STEP04", Spool.DISPLAY)));
    }

    @Test
    void anAbendKeepsWhatWasWrittenDeletesItsNewDataSetAndFlushesLaterSteps() {
        Ran ran = run(JobDef.of("ABENDJOB",
                copy("STEP01", instream("A", "B"), newDataset("OUT", "TEST.BATCH.KEPT")),
                StepDef.program("STEP02", "FAIL", Dd.shr("TRANCATG", KeyedTable.TRANCATG.dsn()),
                        newDataset("OUT", "TEST.BATCH.LOST")),
                copy("STEP03", instream("C"))));

        assertEquals(Map.of("STEP01", "RC=0", "STEP02", "U0999", "STEP03", "FLUSH"), labels(ran));
        assertEquals("U0999", ran.completion().label());
        assertEquals(List.of("A", "B"), lines(datasets.read("TEST.BATCH.KEPT").orElseThrow()));
        assertEquals(Optional.empty(), datasets.read("TEST.BATCH.LOST"));
        assertEquals(List.of("ABENDING"), lines(spool.read(ran.jobExecutionId(), "STEP02", Spool.DISPLAY)));
        assertEquals(1, jdbc.sql("select count(*) from transaction_categories where tran_type_cd = 'Z7'")
                .query(Integer.class).single());
        assertEquals(1, jdbc.sql("select count(*) from datasets").query(Integer.class).single());
    }

    @Test
    void aMissingInputIsAJclErrorAndWhatTheStepAllocatedIsGone() {
        Ran ran = run(JobDef.of("JCLERR",
                copy("STEP01", newDataset("OUT", "TEST.BATCH.NEVER"), Dd.shr("IN", "TEST.BATCH.MISSING")),
                copy("STEP02", instream("C"))));

        assertEquals(Map.of("STEP01", "JCL ERROR", "STEP02", "FLUSH"), labels(ran));
        assertEquals("JCL ERROR", jdbc.sql("select exit_code from batch_step_execution where job_execution_id = ?"
                        + " and step_name = 'STEP01'").param(ran.jobExecutionId()).query(String.class).single());
        assertEquals(0, jdbc.sql("select count(*) from datasets").query(Integer.class).single());
    }

    @Test
    void modDeleteDeleteRemovesAMissingDataSetAndACatalogedOneSoTheNextStepCanCreateIt() {
        Disposition modDelete = Disposition.of(Disposition.Status.MOD, Disposition.End.DELETE, Disposition.End.DELETE);
        String dsn = "TEST.BATCH.MODDEL";
        Dd predel = Dd.of("DD01", new DdSource.Dataset(new DsnRef.Named(dsn), modDelete, Optional.empty()));

        Ran first = run(JobDef.of("MODDEL", StepDef.program("PREDEL", Iefbr14.NAME, predel),
                copy("STEP05", instream("ONE"), newDataset("OUT", dsn))));
        assertEquals("RC=0", first.completion().label());
        assertEquals(List.of("ONE"), lines(datasets.read(dsn).orElseThrow()));

        Ran second = run(JobDef.of("MODDEL2", StepDef.program("PREDEL", Iefbr14.NAME, predel),
                copy("STEP05", instream("TWO"), newDataset("OUT", dsn))));
        assertEquals("RC=0", second.completion().label());
        assertEquals(List.of("TWO"), lines(datasets.read(dsn).orElseThrow()));
    }

    @Test
    void aProgramNotInTheLibraryAbendsS806() {
        Ran ran = run(JobDef.of("NOPGM", StepDef.program("STEP01", "NOSUCH", newDataset("OUT", "TEST.BATCH.NOPGM"))));

        assertEquals(Map.of("STEP01", "S806"), labels(ran));
        assertEquals(Optional.empty(), datasets.read("TEST.BATCH.NOPGM"));
    }

    @Test
    void anIdentityRunsOnce() {
        long submission = submission();
        JobDef job = JobDef.of("ONCE", copy("STEP01", instream("A")));
        runtime.run(job, new RunIdentity.Submitted(submission));

        assertThrows(IllegalStateException.class, () -> runtime.run(job, new RunIdentity.Submitted(submission)));
    }

    @Test
    void aNewGenerationIsOneDataSetForTheJobAndZeroIsFixedAtJobStart() {
        run(JobDef.of("GEN1", copy("STEP01", instream("G1"), generation("OUT", Gdg.TRANREPT, 1, NEW_CATLG))));

        Ran ran = run(JobDef.of("GEN2",
                copy("STEP01", instream("G2"), generation("OUT", Gdg.TRANREPT, 1, NEW_CATLG)),
                copy("STEP02", generation("IN", Gdg.TRANREPT, 1, Disposition.SHR), Dd.sysout("OUT")),
                copy("STEP03", generation("IN", Gdg.TRANREPT, 0, Disposition.SHR), Dd.sysout("OUT"))));

        assertEquals(List.of("G2"), lines(spool.read(ran.jobExecutionId(), "STEP02", "OUT")));
        assertEquals(List.of("G1"), lines(spool.read(ran.jobExecutionId(), "STEP03", "OUT")));
        assertEquals(List.of("1 CATALOGED", "2 CATALOGED"), generations(Gdg.TRANREPT));
    }

    @Test
    void generationsPastTheLimitAreScratchedOrUncataloged() {
        for (int i = 1; i <= 6; i++) {
            run(JobDef.of("ROLL", copy("STEP01", instream("G" + i), generation("OUT", Gdg.TRANREPT, 1, NEW_CATLG)),
                    copy("STEP02", instream("G" + i), generation("OUT", Gdg.TRANSACT_DALY, 1, NEW_CATLG))));
        }

        assertEquals(List.of("2 CATALOGED", "3 CATALOGED", "4 CATALOGED", "5 CATALOGED", "6 CATALOGED"),
                generations(Gdg.TRANREPT));
        assertEquals(List.of("1 UNCATALOGED", "2 CATALOGED", "3 CATALOGED", "4 CATALOGED", "5 CATALOGED",
                "6 CATALOGED"), generations(Gdg.TRANSACT_DALY));
        Ran ran = run(JobDef.of("OLDEST",
                copy("STEP01", generation("IN", Gdg.TRANSACT_DALY, -4, Disposition.SHR), Dd.sysout("OUT"))));
        assertEquals(List.of("G2"), lines(spool.read(ran.jobExecutionId(), "STEP01", "OUT")));
    }

    private Ran run(JobDef job) {
        return runtime.run(job, new RunIdentity.Submitted(submission()));
    }

    /** A queue row with its claim taken, as the internal reader leaves it before running the deck. */
    private long submission() {
        long id = jdbc.sql("insert into job_submissions (queue, job_name, jcl, submitted_by, submitted_at)"
                        + " values ('JOBS', 'TEST', ?, 'TEST', ?) returning id")
                .params(new String[] {"//TEST JOB"}, LocalDateTime.now()).query(Long.class).single();
        jdbc.sql("insert into job_submission_runs (submission_id, claimed_at) values (?, ?)")
                .params(id, LocalDateTime.now()).update();
        return id;
    }

    private static StepDef copy(String path, Dd... dds) {
        List<Dd> all = new ArrayList<>(List.of(dds));
        if (all.stream().noneMatch(dd -> dd.name().equals("OUT"))) {
            all.add(Dd.of("OUT", new DdSource.Dummy()));
        }
        return new StepDef(path, "COPY", "", Cond.NONE, all);
    }

    private static Cond cond(int code, Cond.Operator operator) {
        return new Cond(List.of(new Cond.Test(code, operator, Optional.empty())));
    }

    private static Dd instream(String... cards) {
        return Dd.of("IN", new DdSource.Instream(List.of(cards)));
    }

    private static Dd newDataset(String name, String dsn) {
        return Dd.of(name, new DdSource.Dataset(new DsnRef.Named(dsn), NEW_CATLG, Optional.of(CARD)));
    }

    private static Dd generation(String name, Gdg gdg, int relative, Disposition disposition) {
        return Dd.of(name, new DdSource.Dataset(new DsnRef.Generation(gdg, relative), disposition, Optional.of(CARD)));
    }

    private List<String> generations(Gdg gdg) {
        return jdbc.sql("select generation || ' ' || state from datasets where dsn = ? order by generation")
                .param(gdg.base()).query(String.class).list();
    }

    private static Map<String, String> labels(Ran ran) {
        Map<String, String> labels = new LinkedHashMap<>();
        ran.steps().forEach((step, completion) -> labels.put(step, completion.label()));
        return labels;
    }

    private static byte[] card(String text) {
        byte[] card = new byte[80];
        Arrays.fill(card, (byte) ' ');
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, card, 0, bytes.length);
        return card;
    }

    private static List<String> lines(List<byte[]> records) {
        return records.stream().map(r -> new String(r, StandardCharsets.ISO_8859_1).stripTrailing()).toList();
    }
}
