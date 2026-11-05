package com.carddemo.batch.programs;

import java.time.LocalDate;
import java.util.Arrays;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.batch.ims.DliProgram;
import com.carddemo.batch.ims.ImsRegion;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.SequentialInput;
import com.carddemo.batch.step.Step;
import com.carddemo.dli.DliStatus;
import com.carddemo.dli.Pcb;
import com.carddemo.dli.PendingAuthDetail;
import com.carddemo.dli.PendingAuthSummary;
import com.carddemo.dli.Ssa;

/**
 * PAUDBLOD: loads PAUDBUNL's unload into DBPAUTP0. It ISRTs every INFILE1 root, then, for each INFILE2 record,
 * GUs the root its key names and ISRTs the dependent under it. A key already there is reported and skipped.
 */
@Component
public final class Paudblod implements DliProgram {

    public static final String NAME = "PAUDBLOD";
    public static final String PSB = "PSBPAUTB";

    private static final String STARS = "***************************";

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
        Pcb pcb = region.database();
        step.display("STARTING PAUDBLOD");
        step.display("*-------------------------------------*");
        step.display("TODAYS DATE            :" + LocalDate.now(step.clock()).format(Paudbunl.YYMMDD));
        step.display(" ");
        SequentialInput roots = step.input("INFILE1");
        SequentialInput children = step.input("INFILE2");
        try {
            open(step, roots, "INFILE1");
            open(step, children, "INFILE2");
            for (Read read = roots.read(); read.status() != FileStatus.END_OF_FILE; read = roots.read()) {
                if (read instanceof Read.Found found && found.status() == FileStatus.OK) {
                    insertRoot(step, pcb, PendingAuthSummary.of(found.record()));
                } else {
                    step.display("ERROR READING ROOT SEG INFILE");
                }
            }
            for (Read read = children.read(); read.status() != FileStatus.END_OF_FILE; read = children.read()) {
                if (read instanceof Read.Found found && found.status() == FileStatus.OK) {
                    byte[] record = found.record();
                    if (packedIsNumeric(record, Paudbunl.ROOT_KEY_LENGTH)) {
                        insertChild(step, pcb, PendingAuthSummary.keyOf(record, 0), PendingAuthDetail.of(
                                Arrays.copyOfRange(record, Paudbunl.ROOT_KEY_LENGTH, Paudbunl.CHILD_LENGTH)));
                    }
                } else {
                    step.display("ERROR READING CHILD SEG INFILE");
                }
            }
        } catch (ImsGoback e) {
            return ImsGoback.RETURN_CODE;
        }
        step.display("CLOSING THE FILE");
        close(step, roots, "1ST");
        close(step, children, "2ND");
        return 0;
    }

    /** 2100-INSERT-ROOT-SEG. */
    private static void insertRoot(Step step, Pcb pcb, PendingAuthSummary root) {
        DliStatus status = pcb.isrt(root);
        step.display(" *******************************");
        step.display(" *******************************");
        switch (status) {
            case OK -> step.display("ROOT INSERT SUCCESS");
            case II -> step.display("ROOT SEGMENT ALREADY IN DB");
            default -> {
                step.display("ROOT INSERT FAILED  :" + status.code());
                throw abend(step);
            }
        }
    }

    @LegacyDefect(id = "PAUDBLOD-orphan-child-skipped",
            goldens = {"batch-auth-load-from-unload"},
            behavior = "3100-INSERT-CHILD-SEG has no END-IF after the GU success branch, so the test for a failed GU"
                    + " sits inside it: a dependent whose root is not in the database is skipped with no message"
                    + " and the load ends RC 0",
            fix = "end the success IF before the failure test, so a failed GU DISPLAYs ROOT GU CALL FAIL and ends"
                    + " RC 16")
    private static void insertChild(Step step, Pcb pcb, long acctId, PendingAuthDetail child) {
        boolean found = pcb.gu(Ssa.summary(acctId)).ok();
        step.display(STARS);
        step.display(STARS);
        if (found) {
            step.display("GU CALL TO ROOT SEG SUCCESS");
            insertUnderRoot(step, pcb, child);
        }
    }

    /**
     * 3200-INSERT-IMS-CALL: ISRT under the root the GU just reached. Only a new key or II can follow a successful
     * GU, so the failure branch, which DISPLAYs the PCB's key feedback area, cannot be reached.
     */
    private static void insertUnderRoot(Step step, Pcb pcb, PendingAuthDetail child) {
        DliStatus status = pcb.isrt(child);
        switch (status) {
            case OK -> step.display("CHILD SEGMENT INSERTED SUCCESS");
            case II -> step.display("CHILD SEGMENT ALREADY IN DB");
            default -> throw new IllegalStateException("ISRT after a successful GU ended " + status.code());
        }
    }

    /** ROOT-SEG-KEY IS NUMERIC: every digit nibble 0-9 and a sign nibble of C, D or F. */
    static boolean packedIsNumeric(byte[] record, int length) {
        for (int i = 0; i < length * 2 - 1; i++) {
            if ((record[i / 2] >> (i % 2 == 0 ? 4 : 0) & 0x0F) > 9) {
                return false;
            }
        }
        int sign = record[length - 1] & 0x0F;
        return sign == 0x0C || sign == 0x0D || sign == 0x0F;
    }

    private static void open(Step step, SequentialInput file, String name) {
        FileStatus status = file.open();
        if (status != FileStatus.OK) {
            step.display("ERROR IN OPENING " + name + ":" + status.code());
            throw abend(step);
        }
    }

    private static void close(Step step, SequentialInput file, String ordinal) {
        FileStatus status = file.close();
        if (status != FileStatus.OK) {
            step.display("ERROR IN CLOSING " + ordinal + " FILE:" + status.code());
        }
    }

    private static ImsGoback abend(Step step) {
        return ImsGoback.of(step, "IMS LOAD ABENDING ...");
    }
}
