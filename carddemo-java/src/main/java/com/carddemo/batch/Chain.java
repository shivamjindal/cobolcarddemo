package com.carddemo.batch;

import java.util.List;

/**
 * The legacy schedules, as job chains: each job starts when the one before it completes, and a failure stops
 * the chain where a restart resumes it. CLOSEFIL, OPENFIL and WAITSTEP are gone (they only closed the CICS
 * files so batch could open the VSAM clusters); so are the reference-data reloads the schedules interleave
 * (TRANTYPE, TRANCATG, TCATBALF, DISCGRP), which the seed now owns, and TXT2PDF1 and PRTCATBL, which have no
 * program here.
 */
public enum Chain {

    /** CA-7 SCHID 030: CBPAUP0J, then POSTTRAN, then the master-file listings and the statements. */
    DAILY("CBPAUP0J", "POSTTRAN"),

    /** Control-M MONTHLY-InterestCalculation: INTCALC, then COMBTRAN, which INTCALC now does as it posts. */
    MONTHLY("INTCALC");

    final List<String> jobs;

    Chain(String... jobs) {
        this.jobs = List.of(jobs);
    }
}
