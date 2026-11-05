package com.carddemo.batch.programs;

import com.carddemo.LegacyDefect;
import com.carddemo.batch.step.Step;
import com.carddemo.dli.DliStatus;
import com.carddemo.dli.Get;
import com.carddemo.dli.Pcb;
import com.carddemo.dli.PendingAuthDetail;
import com.carddemo.dli.PendingAuthSummary;
import com.carddemo.dli.Ssa;

/**
 * The walk PAUDBUNL and DBUNLDGS share, by CBLTDLI: every root by an unqualified GN, then its dependents by GNP
 * until GE, which DISPLAYs {@code CHILD SEG FLAG GE : Y}. The two differ only in where a segment goes.
 */
final class UnloadWalk {

    interface Sink {
        void root(PendingAuthSummary root);

        void child(PendingAuthSummary root, PendingAuthDetail child);
    }

    /**
     * The ACCNTID x'404040404040' reads as. The sample unload has one root of EBCDIC spaces; its sign nibble is not
     * C, D or F, so PA-ACCT-ID IS NUMERIC fails, but the segment store reads it as this positive number.
     */
    static final long BLANK_KEY = 40404040404L;

    private final Step step;
    private final Pcb pcb;
    private long summariesRead;

    UnloadWalk(Step step, Pcb pcb) {
        this.step = step;
        this.pcb = pcb;
    }

    /** 2000-FIND-NEXT-AUTH-SUMMARY until GB. */
    void walk(Sink sink) {
        for (Get<PendingAuthSummary> got = pcb.gn(Ssa.summary()); got.ok(); got = pcb.gn(Ssa.summary())) {
            PendingAuthSummary root = got.segment().orElseThrow();
            summariesRead++;
            if (isNumeric(root)) {
                sink.root(root);
                children(root, sink);
            }
        }
        expect(pcb.status(), DliStatus.GB, "GN");
    }

    /** 3000-FIND-NEXT-AUTH-DTL until GE. */
    private void children(PendingAuthSummary root, Sink sink) {
        for (Get<PendingAuthDetail> got = pcb.gnp(Ssa.detail()); got.ok(); got = pcb.gnp(Ssa.detail())) {
            countChildAsSummary();
            sink.child(root, got.segment().orElseThrow());
        }
        expect(pcb.status(), DliStatus.GE, "GNP");
        step.display("CHILD SEG FLAG GE : Y");
    }

    @LegacyDefect(id = "PAUDBUNL-DBUNLDGS-blank-key-root-dropped",
            goldens = {"batch-auth-unload-flat", "batch-auth-unload-gsam"},
            behavior = "a root whose PA-ACCT-ID is not numeric, such as the sample's root of spaces, is not unloaded"
                    + " and neither are its dependents, without a message, so a reload loses them",
            fix = "unload every root, or DISPLAY and count the roots skipped")
    private static boolean isNumeric(PendingAuthSummary root) {
        return root.acctId() != BLANK_KEY;
    }

    @LegacyDefect(id = "PAUDBUNL-DBUNLDGS-child-counted-as-summary",
            behavior = "each dependent read adds 1 to WS-NO-SUMRY-READ and WS-AUTH-SMRY-PROC-CNT, the summary"
                    + " counters; neither is displayed, so nothing shows it",
            fix = "count dependents in WS-NO-DTL-READ")
    private void countChildAsSummary() {
        summariesRead++;
    }

    /**
     * An unqualified GN ends only at GB and a GNP after a successful GN only at GE, so the programs' other-status
     * branches, which DISPLAY the PCB's key feedback area, cannot be reached; this PCB keeps no key feedback area.
     */
    private static void expect(DliStatus status, DliStatus end, String call) {
        if (status != end) {
            throw new IllegalStateException(call + " ended " + status.code() + ", not " + end.code());
        }
    }
}
