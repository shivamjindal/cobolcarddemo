package com.carddemo.batch.programs;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.KeyedDataset;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.Step;

/**
 * CBSTM03B: open, read and close of the four indexed inputs CBSTM03A calls. An operation the paragraph does
 * not handle leaves the file status it already held.
 */
final class Cbstm03b {

    static final String TRNXFILE = "TRNXFILE";
    static final String XREFFILE = "XREFFILE";
    static final String CUSTFILE = "CUSTFILE";
    static final String ACCTFILE = "ACCTFILE";

    private static final Set<String> FILES = Set.of(TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE);

    record Reply(String status, byte[] record) {

        boolean is(String code) {
            return code.equals(status);
        }

        boolean okOrWarning() {
            return is("00") || is("04");
        }
    }

    private static final class Held {
        final KeyedDataset dataset;
        String status = "  ";

        Held(KeyedDataset dataset) {
            this.dataset = dataset;
        }
    }

    private final Map<String, Held> files = new HashMap<>();

    /** {@code op} is O, C, R or K. {@code key} is the random-read key, and only K uses it. */
    Reply call(Step step, String dd, char op, byte[] key) {
        if (!FILES.contains(dd)) {
            return new Reply("00", null);
        }
        Held file = files.computeIfAbsent(dd, name -> new Held(step.keyed(name)));
        return switch (op) {
            case 'O' -> status(file, file.dataset.open(OpenMode.INPUT));
            case 'C' -> status(file, file.dataset.close());
            case 'R' -> read(file, file.dataset.readNext());
            case 'K' -> read(file, file.dataset.read(key));
            default -> new Reply(file.status, null);
        };
    }

    private static Reply status(Held file, FileStatus status) {
        file.status = status.code();
        return new Reply(file.status, null);
    }

    private static Reply read(Held file, Read read) {
        file.status = read.status().code();
        return new Reply(file.status, read instanceof Read.Found found ? found.record() : null);
    }
}
