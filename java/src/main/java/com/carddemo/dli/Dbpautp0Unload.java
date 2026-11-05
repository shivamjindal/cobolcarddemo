package com.carddemo.dli;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.carddemo.dli.SegmentTables.Child;

/**
 * A DFSURGU0 unload of DBPAUTP0, as {@code data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat} holds it: variable
 * records with an RDW, then a prefix whose byte 4 is the segment level (0 for the header and statistics
 * records), bytes 8-9 the segment length and bytes 10-17 the segment name; the segment starts at byte 39.
 * Segments come in hierarchical sequence, so a PAUTDTL1 belongs to the PAUTSUM0 before it.
 */
public final class Dbpautp0Unload {

    public static final String DATA_FILE = "app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat";

    private static final Charset EBCDIC = Charset.forName("Cp037");
    private static final int SEGMENT_OFFSET = 39;

    public record Contents(List<PendingAuthSummary> roots, List<Child> children) {}

    private Dbpautp0Unload() {}

    public static Contents read(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        List<PendingAuthSummary> roots = new ArrayList<>();
        List<Child> children = new ArrayList<>();
        int at = 0;
        while (at < data.length) {
            int length = (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
            if (length < 4 || at + length > data.length) {
                throw new IllegalArgumentException(file + ": bad RDW at offset " + at);
            }
            byte[] record = Arrays.copyOfRange(data, at, at + length);
            at += length;
            if (record[4] == 0) {
                continue;
            }
            int segmentLength = (record[8] & 0xFF) << 8 | record[9] & 0xFF;
            String name = new String(record, 10, 8, EBCDIC);
            byte[] segment = Arrays.copyOfRange(record, SEGMENT_OFFSET, SEGMENT_OFFSET + segmentLength);
            switch (name) {
                case "PAUTSUM0" -> roots.add(PendingAuthSummary.of(Image.fromEbcdic(segment, PendingAuthSummary.LAYOUT)));
                case "PAUTDTL1" -> {
                    if (roots.isEmpty()) {
                        throw new IllegalArgumentException(file + ": PAUTDTL1 before any PAUTSUM0");
                    }
                    children.add(new Child(roots.getLast().acctId(),
                            PendingAuthDetail.of(Image.fromEbcdic(segment, PendingAuthDetail.LAYOUT))));
                }
                default -> throw new IllegalArgumentException(file + ": unknown segment " + name);
            }
        }
        return new Contents(roots, children);
    }
}
