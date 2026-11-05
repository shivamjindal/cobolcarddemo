package com.carddemo.batch.programs;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.carddemo.batch.ims.DliProgram;
import com.carddemo.batch.ims.ImsRegion;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.SequentialOutput;
import com.carddemo.batch.step.Step;
import com.carddemo.dli.PendingAuthDetail;
import com.carddemo.dli.PendingAuthSummary;

/**
 * PAUDBUNL: unloads DBPAUTP0 to two flat files, OUTFIL1 with the 100-byte roots and OUTFIL2 with each dependent
 * after its root's packed ACCNTID (206 bytes), in hierarchical sequence. PAUDBLOD loads them back.
 */
@Component
public final class Paudbunl implements DliProgram {

    public static final String NAME = "PAUDBUNL";
    public static final String PSB = "PAUTBUNL";

    static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    /** OPFIL2-REC and PAUDBLOD's INFIL2-REC: ROOT-SEG-KEY, the root's packed ACCNTID, then the dependent. */
    static final int ROOT_KEY_LENGTH = 6;
    static final int CHILD_LENGTH = ROOT_KEY_LENGTH + PendingAuthDetail.LENGTH;

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
        step.display("STARTING PROGRAM PAUDBUNL::");
        step.display("*-------------------------------------*");
        step.display("TODAYS DATE            :" + LocalDate.now(step.clock()).format(YYMMDD));
        step.display(" ");
        SequentialOutput roots = step.output("OUTFIL1");
        SequentialOutput children = step.output("OUTFIL2");
        try {
            open(step, roots, "OPFILE1");
            open(step, children, "OPFILE2");
            new UnloadWalk(step, region.database()).walk(new UnloadWalk.Sink() {
                @Override
                public void root(PendingAuthSummary root) {
                    roots.write(root.image());
                }

                @Override
                public void child(PendingAuthSummary root, PendingAuthDetail child) {
                    children.write(ByteBuffer.allocate(CHILD_LENGTH)
                            .put(PendingAuthSummary.keyImage(root.acctId())).put(child.image()).array());
                }
            });
        } catch (ImsGoback e) {
            return ImsGoback.RETURN_CODE;
        }
        step.display("CLOSING THE FILE");
        close(step, roots, "1ST");
        close(step, children, "2ND");
        return 0;
    }

    private static void open(Step step, SequentialOutput file, String name) {
        FileStatus status = file.open(OpenMode.OUTPUT);
        if (status != FileStatus.OK) {
            step.display("ERROR IN OPENING " + name + ":" + status.code());
            throw ImsGoback.of(step, "IMSUNLOD ABENDING ...");
        }
    }

    private static void close(Step step, SequentialOutput file, String ordinal) {
        FileStatus status = file.close();
        if (status != FileStatus.OK) {
            step.display("ERROR IN CLOSING " + ordinal + " FILE:" + status.code());
        }
    }
}
