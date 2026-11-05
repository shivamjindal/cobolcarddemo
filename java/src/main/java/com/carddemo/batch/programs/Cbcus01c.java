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

/** CBCUS01C: DISPLAYs every CUSTFILE record in customer-id order, between start and end banners. */
@Component
public final class Cbcus01c implements BatchProgram {

    public static final String NAME = "CBCUS01C";

    private static final String CUSTFILE = "CUSTFILE";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        step.display("START OF EXECUTION OF PROGRAM CBCUS01C");
        KeyedDataset customers = step.keyed(CUSTFILE);
        FileStatus opened = customers.open(OpenMode.INPUT);
        if (opened != FileStatus.OK) {
            throw IoFailure.abend(step, "ERROR OPENING CUSTFILE", opened);
        }
        for (Read read = customers.readNext(); read.status() != FileStatus.END_OF_FILE; read = customers.readNext()) {
            if (!(read instanceof Read.Found found) || found.status() != FileStatus.OK) {
                throw IoFailure.abend(step, "ERROR READING CUSTOMER FILE", read.status());
            }
            String record = new String(found.record(), ISO_8859_1);
            displayAsRead(step, record);
            step.display(record);
        }
        FileStatus closed = customers.close();
        if (closed != FileStatus.OK) {
            throw IoFailure.abend(step, "ERROR CLOSING CUSTOMER FILE", closed);
        }
        step.display("END OF EXECUTION OF PROGRAM CBCUS01C");
        return 0;
    }

    @LegacyDefect(id = "CBCUS01C-displays-each-record-twice",
            goldens = {"customer-list-full-seed"},
            behavior = "1000-CUSTFILE-GET-NEXT DISPLAYs each customer record it reads and the main loop "
                    + "DISPLAYs it again, so SYSOUT lists every record twice",
            fix = "drop the DISPLAY in 1000-CUSTFILE-GET-NEXT, as CBACT02C comments out its own")
    private static void displayAsRead(Step step, String record) {
        step.display(record);
    }
}
