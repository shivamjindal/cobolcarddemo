package com.carddemo.batch.programs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.KeyedDataset;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.Step;

/** CBACT03C: DISPLAYs every XREFFILE record in card-number order, between start and end banners. */
@Component
public final class Cbact03c implements BatchProgram {

    public static final String NAME = "CBACT03C";

    private static final String XREFFILE = "XREFFILE";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        step.display("START OF EXECUTION OF PROGRAM CBACT03C");
        KeyedDataset xref = step.keyed(XREFFILE);
        FileStatus opened = xref.open(OpenMode.INPUT);
        if (opened != FileStatus.OK) {
            throw IoFailure.abend(step, "ERROR OPENING XREFFILE", opened);
        }
        for (Read read = xref.readNext(); read.status() != FileStatus.END_OF_FILE; read = xref.readNext()) {
            if (!(read instanceof Read.Found found) || found.status() != FileStatus.OK) {
                throw IoFailure.abend(step, "ERROR READING XREFFILE", read.status());
            }
            String record = new String(found.record(), ISO_8859_1);
            displayAsRead(step, record);
            step.display(record);
        }
        FileStatus closed = xref.close();
        if (closed != FileStatus.OK) {
            throw IoFailure.abend(step, "ERROR CLOSING XREFFILE", closed);
        }
        step.display("END OF EXECUTION OF PROGRAM CBACT03C");
        return 0;
    }

    @LegacyDefect(id = "CBACT03C-displays-each-record-twice",
            goldens = {"xref-list-full-seed"},
            behavior = "1000-XREFFILE-GET-NEXT DISPLAYs each cross-reference record it reads and the main loop "
                    + "DISPLAYs it again, so SYSOUT lists every record twice",
            fix = "drop the DISPLAY in 1000-XREFFILE-GET-NEXT, as CBACT02C comments out its own")
    private static void displayAsRead(Step step, String record) {
        step.display(record);
    }
}
