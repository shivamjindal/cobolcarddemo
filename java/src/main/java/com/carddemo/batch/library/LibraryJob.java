package com.carddemo.batch.library;

import static com.carddemo.batch.jcl.Dd.shr;
import static com.carddemo.batch.jcl.Dd.sysout;
import static com.carddemo.batch.jcl.StepDef.program;

import java.util.Arrays;
import java.util.Optional;

import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.Dd;
import com.carddemo.batch.jcl.DdSource;
import com.carddemo.batch.jcl.Disposition;
import com.carddemo.batch.jcl.DsnRef;
import com.carddemo.batch.jcl.Gdg;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.programs.Cbact02c;
import com.carddemo.batch.programs.Cbact03c;
import com.carddemo.batch.programs.Cbact04c;
import com.carddemo.batch.programs.Cbcus01c;
import com.carddemo.batch.programs.Cbtrn02c;

/** The members of {@code app/jcl} that run here, as typed JCL. STEPLIB is left out: programs come from the program library. */
public enum LibraryJob {
    READCARD(JobDef.of("READCARD", program("STEP05", Cbact02c.NAME,
            shr("CARDFILE", "AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS"), sysout("SYSOUT"), sysout("SYSPRINT")))),
    READXREF(JobDef.of("READXREF", program("STEP05", Cbact03c.NAME,
            shr("XREFFILE", "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS"), sysout("SYSOUT"), sysout("SYSPRINT")))),
    READCUST(JobDef.of("READCUST", program("STEP05", Cbcus01c.NAME,
            shr("CUSTFILE", "AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS"), sysout("SYSOUT"), sysout("SYSPRINT")))),
    POSTTRAN(JobDef.of("POSTTRAN", program("STEP15", Cbtrn02c.NAME,
            shr("TRANFILE", "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS"),
            shr("DALYTRAN", "AWS.M2.CARDDEMO.DALYTRAN.PS"),
            shr("XREFFILE", "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS"),
            generation("DALYREJS", Gdg.DALYREJS, 430),
            shr("ACCTFILE", "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS"),
            shr("TCATBALF", "AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS"),
            sysout("SYSOUT"), sysout("SYSPRINT")))),
    INTCALC(JobDef.of("INTCALC", program("STEP15", Cbact04c.NAME,
            shr("TCATBALF", "AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS"),
            shr("XREFFILE", "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS"),
            shr("XREFFIL1", "AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH"),
            shr("ACCTFILE", "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS"),
            shr("DISCGRP", "AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS"),
            generation("TRANSACT", Gdg.SYSTRAN, 350),
            sysout("SYSOUT"), sysout("SYSPRINT")).parm("2022071800")));

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

    private static Dd generation(String name, Gdg gdg, int lrecl) {
        return Dd.of(name, new DdSource.Dataset(new DsnRef.Generation(gdg, 1),
                Disposition.of(Disposition.Status.NEW, Disposition.End.CATLG, Disposition.End.DELETE),
                Optional.of(new Dcb(Dcb.Recfm.F, lrecl))));
    }
}
