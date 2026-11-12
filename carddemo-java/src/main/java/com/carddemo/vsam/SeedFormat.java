package com.carddemo.vsam;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** How the sample files in app/data store records. Both decode to text with the record's byte offsets. */
public enum SeedFormat {
    /** Fixed-length records with no delimiters, as downloaded from the mainframe. */
    EBCDIC("AWS.M2.CARDDEMO.DALYTRAN.PS") {
        @Override
        Optional<Path> file(Path dir, VsamDataset dataset) {
            return Optional.of(dir.resolve(dataset.ebcdicFile));
        }

        @Override
        List<String> split(byte[] bytes, VsamDataset dataset) {
            if (bytes.length % dataset.recordLength != 0) {
                throw new IllegalArgumentException(dataset.ebcdicFile + " is not a multiple of LRECL " + dataset.recordLength);
            }
            String text = new String(bytes, CP037);
            List<String> records = new ArrayList<>();
            for (int i = 0; i < text.length(); i += dataset.recordLength) {
                records.add(text.substring(i, i + dataset.recordLength));
            }
            return records;
        }
    },
    /** One record per line; trailing spaces (and sometimes FILLER) are trimmed, so lines are padded back. */
    ASCII("dailytran.txt") {
        @Override
        Optional<Path> file(Path dir, VsamDataset dataset) {
            return Optional.ofNullable(dataset.asciiFile).map(dir::resolve);
        }

        @Override
        List<String> split(byte[] bytes, VsamDataset dataset) {
            List<String> records = new ArrayList<>();
            for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\\R")) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.length() > dataset.recordLength) {
                    throw new IllegalArgumentException(dataset.asciiFile + " line longer than LRECL: " + line);
                }
                records.add(line + " ".repeat(dataset.recordLength - line.length()));
            }
            return records;
        }
    };

    private static final Charset CP037 = Charset.forName("IBM037");

    private final String dailyTransactions;

    SeedFormat(String dailyTransactions) {
        this.dailyTransactions = dailyTransactions;
    }

    abstract Optional<Path> file(Path dir, VsamDataset dataset);

    abstract List<String> split(byte[] bytes, VsamDataset dataset);

    /** Empty when this format has no sample file for the dataset (users and transactions are EBCDIC only). */
    public Optional<List<String>> records(Path dir, VsamDataset dataset) {
        return file(dir, dataset).map(path -> read(path, dataset));
    }

    /** DALYTRAN.PS, the daily feed POSTTRAN posts into TRANSACT; its layout (CVTRA06Y) is TRAN-RECORD's. */
    public List<String> dailyTransactions(Path dir) {
        return read(dir.resolve(dailyTransactions), VsamDataset.TRANSACTIONS);
    }

    private List<String> read(Path path, VsamDataset dataset) {
        try {
            return split(Files.readAllBytes(path), dataset);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
