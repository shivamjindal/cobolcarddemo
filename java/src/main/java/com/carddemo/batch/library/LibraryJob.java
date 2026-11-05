package com.carddemo.batch.library;

import static com.carddemo.batch.jcl.Dd.shr;
import static com.carddemo.batch.jcl.Dd.sysout;
import static com.carddemo.batch.jcl.StepDef.program;

import java.util.Arrays;
import java.util.Optional;

import com.carddemo.batch.jcl.JobDef;

/** The members of {@code app/jcl} that run here, as typed JCL. STEPLIB is left out: programs come from the program library. */
public enum LibraryJob {
    READCARD(JobDef.of("READCARD", program("STEP05", "CBACT02C",
            shr("CARDFILE", "AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS"), sysout("SYSOUT"), sysout("SYSPRINT")))),
    READXREF(JobDef.of("READXREF", program("STEP05", "CBACT03C",
            shr("XREFFILE", "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS"), sysout("SYSOUT"), sysout("SYSPRINT")))),
    READCUST(JobDef.of("READCUST", program("STEP05", "CBCUS01C",
            shr("CUSTFILE", "AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS"), sysout("SYSOUT"), sysout("SYSPRINT"))));

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
}
