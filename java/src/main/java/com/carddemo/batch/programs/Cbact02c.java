package com.carddemo.batch.programs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import org.springframework.stereotype.Component;

import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.KeyedDataset;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.Step;

/** CBACT02C: DISPLAYs every CARDFILE record in card-number order, between start and end banners. */
@Component
public final class Cbact02c implements BatchProgram {

    public static final String NAME = "CBACT02C";

    private static final String CARDFILE = "CARDFILE";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        step.display("START OF EXECUTION OF PROGRAM CBACT02C");
        KeyedDataset cards = step.keyed(CARDFILE);
        FileStatus opened = cards.open(OpenMode.INPUT);
        if (opened != FileStatus.OK) {
            throw IoFailure.abend(step, "ERROR OPENING CARDFILE", opened);
        }
        for (Read read = cards.readNext(); read.status() != FileStatus.END_OF_FILE; read = cards.readNext()) {
            if (!(read instanceof Read.Found found) || found.status() != FileStatus.OK) {
                throw IoFailure.abend(step, "ERROR READING CARDFILE", read.status());
            }
            step.display(new String(found.record(), ISO_8859_1));
        }
        FileStatus closed = cards.close();
        if (closed != FileStatus.OK) {
            throw IoFailure.abend(step, "ERROR CLOSING CARDFILE", closed);
        }
        step.display("END OF EXECUTION OF PROGRAM CBACT02C");
        return 0;
    }
}
