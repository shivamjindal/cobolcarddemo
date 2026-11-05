package com.carddemo.batch.library;

import static com.carddemo.batch.jcl.Dd.shr;
import static com.carddemo.batch.jcl.Dd.sysout;
import static com.carddemo.batch.jcl.StepDef.program;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.carddemo.batch.jcl.Dd;
import com.carddemo.batch.jcl.DdSource;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.programs.Cbact02c;
import com.carddemo.batch.programs.Cbact03c;
import com.carddemo.batch.programs.Cbcus01c;
import com.carddemo.batch.programs.Cobswait;
import com.carddemo.batch.programs.Sdsf;

/** The members of {@code app/jcl} that run here, as typed JCL. STEPLIB is left out: programs come from the program library. */
public enum LibraryJob {
    READCARD(JobDef.of("READCARD", program("STEP05", Cbact02c.NAME,
            shr("CARDFILE", "AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS"), sysout("SYSOUT"), sysout("SYSPRINT")))),
    READXREF(JobDef.of("READXREF", program("STEP05", Cbact03c.NAME,
            shr("XREFFILE", "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS"), sysout("SYSOUT"), sysout("SYSPRINT")))),
    READCUST(JobDef.of("READCUST", program("STEP05", Cbcus01c.NAME,
            shr("CUSTFILE", "AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS"), sysout("SYSOUT"), sysout("SYSPRINT")))),
    CLOSEFIL(JobDef.of("CLOSEFIL", program("CLCIFIL", Sdsf.NAME, sysout("ISFOUT"), sysout("CMDOUT"), instream("ISFIN",
            " /F CICSAWSA,'CEMT SET FIL(TRANSACT ) CLO'",
            " /F CICSAWSA,'CEMT SET FIL(CCXREF ) CLO'",
            " /F CICSAWSA,'CEMT SET FIL(ACCTDAT ) CLO'",
            " /F CICSAWSA,'CEMT SET FIL(CXACAIX ) CLO'",
            " /F CICSAWSA,'CEMT SET FIL(USRSEC ) CLO'")))),
    OPENFIL(JobDef.of("OPENFIL", program("OPCIFIL", Sdsf.NAME, sysout("ISFOUT"), sysout("CMDOUT"), instream("ISFIN",
            " /F CICSAWSA,'CEMT SET FIL(TRANSACT ) OPE'",
            " /F CICSAWSA,'CEMT SET FIL(CCXREF ) OPE'",
            " /F CICSAWSA,'CEMT SET FIL(ACCTDAT ) OPE'",
            " /F CICSAWSA,'CEMT SET FIL(CXACAIX ) OPE'",
            " /F CICSAWSA,'CEMT SET FIL(USRSEC ) OPE'")))),
    WAITSTEP(JobDef.of("WAITSTEP", program("WAIT", Cobswait.NAME, sysout("SYSOUT"),
            instream("SYSIN", "00003600      VALUE IN CENTISECONDS"))));

    private final JobDef job;

    LibraryJob(JobDef job) {
        this.job = job;
    }

    public JobDef job() {
        return job;
    }

    public static Optional<LibraryJob> byName(String name) {
        return Arrays.stream(values()).filter(j -> j.name().equals(name)).findFirst();
    }

    private static Dd instream(String dd, String... cards) {
        return Dd.of(dd, new DdSource.Instream(List.of(cards)));
    }
}
