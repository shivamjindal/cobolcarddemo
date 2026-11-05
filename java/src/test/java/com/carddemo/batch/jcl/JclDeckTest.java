package com.carddemo.batch.jcl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.carddemo.report.ReportJcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JclDeckTest {

    private static final DdSource.Dataset BACKUP =
            new DdSource.Dataset(new DsnRef.Generation(Gdg.TRANSACT_BKUP, 1), Disposition.SHR, Optional.empty());

    private static final ProcDef TRANREPT = new ProcDef("TRANREPT", List.of(
            StepDef.program("STEP05R", "SORT", Dd.of("SORTIN", BACKUP),
                    Dd.of("SYMNAMES", new DdSource.Instream(List.of("PARM-START-DATE,C'2022-01-01'"))),
                    Dd.sysout("SYSOUT")),
            StepDef.program("STEP10R", "CBTRN03C", Dd.sysout("SYSOUT"),
                    Dd.shr("DATEPARM", "AWS.M2.CARDDEMO.DATEPARM"))));

    @Test
    void theReportSubmitRunsTheProcedureWithItsInstreamOverrides() {
        JobDef job = parse(ReportJcl.cards("2026-11-01", "2026-11-30"));

        assertEquals("TRNRPT00", job.name());
        assertEquals(List.of("STEP10.STEP05R", "STEP10.STEP10R"), job.steps().stream().map(StepDef::path).toList());
        StepDef sort = job.steps().get(0);
        assertEquals(List.of("SORTIN", "SYMNAMES", "SYSOUT"), sort.dds().stream().map(Dd::name).toList());
        assertEquals(BACKUP, sort.dd("SORTIN").orElseThrow().source());
        assertEquals(new DdSource.Instream(List.of("TRAN-CARD-NUM,263,16,ZD", "TRAN-PROC-DT,305,10,CH",
                        "PARM-START-DATE,C'2026-11-01'", "PARM-END-DATE,C'2026-11-30'")),
                sort.dd("SYMNAMES").orElseThrow().source());
        assertEquals(new DdSource.Instream(List.of("2026-11-01 2026-11-30")),
                job.steps().get(1).dd("DATEPARM").orElseThrow().source());
    }

    @Test
    void programStepsCarryParmCondDcbAndConcatenations() {
        JobDef job = parse(List.of(
                "//HARNESS  JOB 'BATCH',CLASS=A,",
                "//         MSGCLASS=X",
                "//STEP01   EXEC PGM=CBACT04C,PARM='2022071800'",
                "//STEPLIB  DD DISP=SHR,DSN=AWS.M2.CARDDEMO.LOADLIB",
                "//         DD DISP=SHR,DSN=AWS.M2.CARDDEMO.LOADLIB2",
                "//ACCTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS",
                "//TRANSACT DD SYSOUT=*,DCB=(RECFM=FB,LRECL=350)",
                "//TRANFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TRANSACT.FLAT",
                "//         DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)",
                "//DATEPARM DD *",
                "2022-01-01 2022-07-06",
                "/*",
                "//STEP02   EXEC PGM=IEFBR14,COND=((0,NE),(4,LT,STEP01))",
                "//IN       DD SYSOUT=*,DCB=(RECFM=VB,LRECL=200)",
                "//OUT      DD DSN=AWS.M2.CARDDEMO.TRANREPT(+1),DISP=(NEW,CATLG,DELETE),",
                "//            DCB=(*.IN)",
                "//GONE     DD DUMMY",
                "/*EOF"));

        StepDef interest = job.steps().get(0);
        assertEquals("CBACT04C", interest.program());
        assertEquals("2022071800", interest.parm());
        assertEquals(Cond.NONE, interest.cond());
        assertEquals(List.of("ACCTFILE", "TRANSACT", "TRANFILE", "DATEPARM"),
                interest.dds().stream().map(Dd::name).toList());
        assertEquals(new DdSource.Sysout(Optional.of(new Dcb(Dcb.Recfm.F, 350))),
                interest.dd("TRANSACT").orElseThrow().source());
        assertEquals(List.of(
                        new DdSource.Dataset(new DsnRef.Named("AWS.M2.CARDDEMO.TRANSACT.FLAT"), Disposition.SHR,
                                Optional.empty()),
                        new DdSource.Dataset(new DsnRef.Generation(Gdg.TRANSACT_BKUP, 0), Disposition.SHR,
                                Optional.empty())),
                interest.dd("TRANFILE").orElseThrow().concatenation());

        StepDef second = job.steps().get(1);
        assertEquals(new Cond(List.of(new Cond.Test(0, Cond.Operator.NE, Optional.empty()),
                new Cond.Test(4, Cond.Operator.LT, Optional.of("STEP01")))), second.cond());
        assertEquals(new DdSource.Dataset(new DsnRef.Generation(Gdg.TRANREPT, 1),
                        new Disposition(Disposition.Status.NEW, Disposition.End.CATLG, Disposition.End.DELETE),
                        Optional.of(new Dcb(Dcb.Recfm.V, 200))),
                second.dd("OUT").orElseThrow().source());
        assertEquals(new DdSource.Dummy(), second.dd("GONE").orElseThrow().source());
    }

    @Test
    void dispositionDefaultsFollowJcl() {
        assertEquals(new Disposition(Disposition.Status.NEW, Disposition.End.DELETE, Disposition.End.DELETE),
                Disposition.of(null, null, null));
        assertEquals(new Disposition(Disposition.Status.MOD, Disposition.End.KEEP, Disposition.End.KEEP),
                Disposition.of(Disposition.Status.MOD, null, null));
        assertEquals(new Disposition(Disposition.Status.NEW, Disposition.End.CATLG, Disposition.End.CATLG),
                Disposition.of(Disposition.Status.NEW, Disposition.End.CATLG, null));
    }

    @Test
    void condTestsOnlyStepsThatEndedWithAReturnCode() {
        Cond cond = new Cond(List.of(new Cond.Test(4, Cond.Operator.LT, Optional.empty())));
        assertFalse(cond.bypass(Map.of("STEP01", OptionalInt.of(4))));
        assertTrue(cond.bypass(Map.of("STEP01", OptionalInt.of(8))));
        assertFalse(cond.bypass(Map.of("STEP01", OptionalInt.empty())));
        Cond named = new Cond(List.of(new Cond.Test(0, Cond.Operator.NE, Optional.of("STEP05R"))));
        assertTrue(named.bypass(Map.of("STEP10.STEP05R", OptionalInt.of(4))));
        assertFalse(named.bypass(Map.of("STEP10.STEP10R", OptionalInt.of(4))));
    }

    @Test
    void decksOutsideTheSubsetAreJclErrors() {
        for (List<String> deck : List.of(
                List.of("//STEP01 EXEC PGM=CBACT02C"),
                List.of("//J JOB"),
                List.of("//J JOB", "//S EXEC PGM=X,COND=EVEN"),
                List.of("//J JOB", "//S EXEC PROC=NOSUCH"),
                List.of("//J JOB", "//S EXEC PGM=X", "// IF (RC = 0) THEN"),
                List.of("//J JOB", "//S EXEC PGM=X", "//IN DD DISP=SHR,DSN=AWS.M2.CARDDEMO.NOTAGDG(+1)"),
                List.of("//J JOB", "//S EXEC PGM=X", "//   DD DUMMY"),
                List.of("//J JOB", "//S EXEC PGM=X", "//IN DD DISP=(OLD,KEPT),DSN=A.B"),
                List.of("//J JOB", "//S EXEC PROC=TRANREPT", "//NOSUCH.IN DD DUMMY"))) {
            assertInstanceOf(JclDeck.Parsed.Error.class, JclDeck.parse(deck, procs()), String.join(" / ", deck));
        }
    }

    private static JobDef parse(List<String> cards) {
        return switch (JclDeck.parse(cards, procs())) {
            case JclDeck.Parsed.Job job -> job.job();
            case JclDeck.Parsed.Error error -> throw new AssertionError(error.reason());
        };
    }

    private static Function<String, Optional<ProcDef>> procs() {
        return member -> member.equals(TRANREPT.member()) ? Optional.of(TRANREPT) : Optional.empty();
    }
}
