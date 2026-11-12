package com.carddemo.batch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * What one legacy program step leaves in the spool: its DISPLAY lines (SYSOUT), the sequential datasets it
 * writes (one record per line), and its return code. Lives in {@code <spool>/<job>/<job execution id>/}.
 */
public final class StepOutput {

    private final Path dir;
    private final List<String> sysout = new ArrayList<>();
    private int returnCode;

    StepOutput(Path dir) {
        this.dir = dir;
    }

    /** Execution ids restart at 1 on a fresh database, so a spool directory can hold an earlier run's files. */
    void clear() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void display(String line) {
        sysout.add(line);
    }

    public void returnCode(int code) {
        returnCode = code;
    }

    int returnCode() {
        return returnCode;
    }

    public void dataset(String dd, List<String> records) {
        write(dir.resolve(dd), records.stream().map(r -> r + "\n").toList());
    }

    /** {@code <program>.sysout}, closed with the step's condition code like a JES job log. */
    void writeSysout(String program) {
        List<String> lines = new ArrayList<>(sysout.stream().map(l -> l + "\n").toList());
        lines.add("\n[return code " + returnCode + "]\n");
        write(dir.resolve(program + ".sysout"), lines);
    }

    private static void write(Path file, List<String> chunks) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("", chunks), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
