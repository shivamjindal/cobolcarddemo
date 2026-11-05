package com.carddemo.batch.library;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.Dd;
import com.carddemo.batch.jcl.DdSource;
import com.carddemo.batch.jcl.Disposition;
import com.carddemo.batch.jcl.DsnRef;
import com.carddemo.batch.jcl.Gdg;
import com.carddemo.batch.jcl.ProcDef;
import com.carddemo.batch.jcl.StepDef;
import com.carddemo.batch.programs.Cbtrn03c;
import com.carddemo.batch.utilities.Idcams;
import com.carddemo.batch.utilities.Sort;

/** The members of {@code app/proc} that run here, as typed JCL; {@code EXEC PROC=} resolves against them. */
public enum Proc {
    REPROC(new ProcDef("REPROC", List.of(StepDef.program("PRC001", Idcams.NAME, Dd.sysout("SYSPRINT"),
            Dd.shr("FILEIN", "NULLFILE"), Dd.shr("FILEOUT", "NULLFILE"),
            sysin("    REPRO INFILE(FILEIN) OUTFILE(FILEOUT)"))))),
    /**
     * The nested REPROC step is recorded as STEP01R. The qualified name STEP10.STEP01R.PRC001 does not fit the
     * spool step column. SORTOUT states LRECL 350 because DCB=(*.SORTIN) does not copy the catalog label, and
     * the backup generation written above is 350.
     */
    TRANREPT(new ProcDef("TRANREPT", List.of(
            StepDef.program("STEP01R", Idcams.NAME, Dd.sysout("SYSPRINT"),
                    Dd.shr("FILEIN", "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS"),
                    generation("FILEOUT", Gdg.TRANSACT_BKUP, newCatalog(), 350),
                    sysin("    REPRO INFILE(FILEIN) OUTFILE(FILEOUT)")),
            StepDef.program("STEP05R", Sort.NAME,
                    generation("SORTIN", Gdg.TRANSACT_BKUP, Disposition.SHR, 0),
                    Dd.of("SYMNAMES", instream(
                            "TRAN-CARD-NUM,263,16,ZD",
                            "TRAN-PROC-DT,305,10,CH",
                            "PARM-START-DATE,C'2022-01-01'",
                            "PARM-END-DATE,C'2022-07-06'")),
                    sysin(" SORT FIELDS=(TRAN-CARD-NUM,A)",
                            " INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,",
                            "         TRAN-PROC-DT,LE,PARM-END-DATE)"),
                    Dd.sysout("SYSOUT"),
                    generation("SORTOUT", Gdg.TRANSACT_DALY, newCatalog(), 350)),
            StepDef.program("STEP10R", Cbtrn03c.NAME, Dd.sysout("SYSOUT"), Dd.sysout("SYSPRINT"),
                    generation("TRANFILE", Gdg.TRANSACT_DALY, Disposition.SHR, 0),
                    Dd.shr("CARDXREF", "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS"),
                    Dd.shr("TRANTYPE", "AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS"),
                    Dd.shr("TRANCATG", "AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS"),
                    Dd.shr("DATEPARM", "AWS.M2.CARDDEMO.DATEPARM"),
                    generation("TRANREPT", Gdg.TRANREPT, newCatalog(), 133)))));

    private final ProcDef proc;

    Proc(ProcDef proc) {
        this.proc = proc;
    }

    public ProcDef proc() {
        return proc;
    }

    public static Optional<ProcDef> byMember(String member) {
        return Arrays.stream(values()).map(Proc::proc).filter(p -> p.member().equals(member)).findFirst();
    }

    private static Disposition newCatalog() {
        return new Disposition(Disposition.Status.NEW, Disposition.End.CATLG, Disposition.End.DELETE);
    }

    private static Dd generation(String name, Gdg gdg, Disposition disposition, int lrecl) {
        return Dd.of(name, new DdSource.Dataset(new DsnRef.Generation(gdg, 1), disposition,
                lrecl == 0 ? Optional.empty() : Optional.of(new Dcb(Dcb.Recfm.F, lrecl))));
    }

    private static Dd sysin(String... cards) {
        return Dd.of("SYSIN", instream(cards));
    }

    private static DdSource.Instream instream(String... cards) {
        return new DdSource.Instream(List.of(cards));
    }
}
