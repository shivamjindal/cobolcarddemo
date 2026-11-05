package com.carddemo.vsam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Records {

    private Records() {}

    static Path dataFile(Path appRoot, Dataset dataset) {
        return appRoot.resolve("data").resolve("EBCDIC").resolve(dataset.dataFile());
    }

    static List<byte[]> read(Path file, int length) throws IOException {
        byte[] all = Files.readAllBytes(file);
        if (length <= 0 || all.length % length != 0) {
            throw new IllegalArgumentException(file.getFileName() + " length " + all.length
                    + " is not a multiple of " + length);
        }
        List<byte[]> records = new ArrayList<>();
        for (int offset = 0; offset < all.length; offset += length) {
            records.add(Arrays.copyOfRange(all, offset, offset + length));
        }
        return records;
    }
}
