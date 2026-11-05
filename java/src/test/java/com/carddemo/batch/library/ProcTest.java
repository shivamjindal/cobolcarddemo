package com.carddemo.batch.library;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.carddemo.batch.jcl.DdSource;
import com.carddemo.batch.jcl.DsnRef;
import com.carddemo.batch.jcl.Gdg;
import com.carddemo.batch.jcl.JclDeck;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.jcl.StepDef;
import com.carddemo.batch.programs.Cbtrn03c;
import com.carddemo.batch.utilities.Idcams;
import com.carddemo.batch.utilities.Sort;
import com.carddemo.report.ReportJcl;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcTest {

    @Test
    void theReportDeckRunsReproSortAndTheReportWithTheScreenDates() {
        JobDef job = job(ReportJcl.cards("2099-01-01", "2099-01-31"));

        assertEquals(List.of("STEP10.STEP01R", "STEP10.STEP05R", "STEP10.STEP10R"),
                job.steps().stream().map(StepDef::path).toList());
        StepDef repro = job.steps().get(0);
        StepDef sort = job.steps().get(1);
        StepDef report = job.steps().get(2);
        assertEquals(Idcams.NAME, repro.program());
        assertEquals(Sort.NAME, sort.program());
        assertEquals(Cbtrn03c.NAME, report.program());
        assertEquals(new DsnRef.Generation(Gdg.TRANSACT_BKUP, 1), ((DdSource.Dataset) repro.dd("FILEOUT").orElseThrow()
                .source()).dsn());
        assertEquals(new DsnRef.Generation(Gdg.TRANSACT_DALY, 1), ((DdSource.Dataset) sort.dd("SORTOUT").orElseThrow()
                .source()).dsn());
        assertEquals(new DsnRef.Generation(Gdg.TRANREPT, 1), ((DdSource.Dataset) report.dd("TRANREPT").orElseThrow()
                .source()).dsn());
        assertEquals(List.of("TRAN-CARD-NUM,263,16,ZD", "TRAN-PROC-DT,305,10,CH", "PARM-START-DATE,C'2099-01-01'",
                "PARM-END-DATE,C'2099-01-31'"), ((DdSource.Instream) sort.dd("SYMNAMES").orElseThrow().source()).cards());
        assertEquals(List.of("2099-01-01 2099-01-31"),
                ((DdSource.Instream) report.dd("DATEPARM").orElseThrow().source()).cards());
    }

    private static JobDef job(List<String> cards) {
        return switch (JclDeck.parse(cards, Proc::byMember)) {
            case JclDeck.Parsed.Job parsed -> parsed.job();
            case JclDeck.Parsed.Error error -> throw new AssertionError(error.reason());
        };
    }
}
