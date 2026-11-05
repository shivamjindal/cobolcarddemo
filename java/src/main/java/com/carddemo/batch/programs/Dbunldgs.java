package com.carddemo.batch.programs;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.batch.ims.DliProgram;
import com.carddemo.batch.ims.ImsRegion;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.SequentialOutput;
import com.carddemo.batch.step.Step;
import com.carddemo.dli.PendingAuthDetail;
import com.carddemo.dli.PendingAuthSummary;

/**
 * DBUNLDGS: unloads DBPAUTP0 to two GSAM databases, the roots to PASFLDBD and the dependents to PADFLDBD. A
 * GSAM PCB writes the data set its DBD names: DD PASFILOP (100 bytes) and DD PADFILOP (200 bytes).
 */
@Component
public final class Dbunldgs implements DliProgram {

    public static final String NAME = "DBUNLDGS";
    public static final String PSB = "DLIGSAMP";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String psb() {
        return PSB;
    }

    @Override
    public int run(Step step, ImsRegion region) {
        step.display("STARTING PROGRAM DBUNLDGS::");
        step.display("*-------------------------------------*");
        step.display("TODAYS DATE            :" + LocalDate.now(step.clock()).format(Paudbunl.YYMMDD));
        step.display(" ");
        Gsam roots = new Gsam(step.output("PASFILOP"));
        Gsam children = new Gsam(step.output("PADFILOP"));
        try {
            new UnloadWalk(step, region.database()).walk(new UnloadWalk.Sink() {
                @Override
                public void root(PendingAuthSummary root) {
                    insert(step, roots, root.image());
                }

                @Override
                public void child(PendingAuthSummary root, PendingAuthDetail child) {
                    insert(step, children, childRecord(root, child));
                }
            });
            step.display("CLOSING THE FILE");
            return 0;
        } catch (ImsGoback e) {
            return ImsGoback.RETURN_CODE;
        } finally {
            roots.close();
            children.close();
        }
    }

    @LegacyDefect(id = "DBUNLDGS-child-loses-parent-key",
            goldens = {"batch-auth-unload-gsam"},
            behavior = "the dependent GSAM record is the bare 200-byte segment: DBUNLDGS builds the root key before it"
                    + " in OPFIL2-REC but inserts PENDING-AUTH-DETAILS, so the unload cannot tell whose dependent it is",
            fix = "insert OPFIL2-REC, the root's packed ACCNTID followed by the segment, into a 206-byte PADFLDBD")
    private static byte[] childRecord(PendingAuthSummary root, PendingAuthDetail child) {
        return child.image();
    }

    /** 3100 and 3200 share the failure branch, so a dependent's failure also says GSAM PARENT FAIL. */
    private static void insert(Step step, Gsam gsam, byte[] record) {
        String status = gsam.isrt(record);
        if (!status.isBlank()) {
            step.display("GSAM PARENT FAIL :" + status);
            step.display("KFB AREA IN GSAM:");
            throw ImsGoback.of(step, "DBUNLDGS ABENDING ...");
        }
    }

    /** A GSAM PCB for output. The first ISRT opens its data set: AI when that fails, AO when a write fails. */
    private static final class Gsam {

        private final SequentialOutput file;
        private boolean open;

        Gsam(SequentialOutput file) {
            this.file = file;
        }

        String isrt(byte[] record) {
            if (!open) {
                if (file.open(OpenMode.OUTPUT) != FileStatus.OK) {
                    return "AI";
                }
                open = true;
            }
            return file.write(record) == FileStatus.OK ? "  " : "AO";
        }

        void close() {
            if (open) {
                file.close();
            }
        }
    }
}
