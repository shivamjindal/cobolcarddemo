package com.carddemo.batch.runtime;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.Read;
import com.carddemo.batch.step.SequentialInput;

/** A DD read front to back, its concatenated data sets one after another, loaded at OPEN. */
final class RecordsInput implements SequentialInput {

    /** Fails OPEN with {@code status} instead of reading. */
    static RecordsInput failing(FileStatus status) {
        return new RecordsInput(Optional.of(status), List::of);
    }

    private final Optional<FileStatus> openFails;
    private final Supplier<List<byte[]>> load;
    private List<byte[]> records;
    private int next;
    private boolean ended;

    RecordsInput(Supplier<List<byte[]>> load) {
        this(Optional.empty(), load);
    }

    private RecordsInput(Optional<FileStatus> openFails, Supplier<List<byte[]>> load) {
        this.openFails = openFails;
        this.load = load;
    }

    @Override
    public FileStatus open() {
        if (records != null) {
            return FileStatus.ALREADY_OPEN;
        }
        if (openFails.isPresent()) {
            return openFails.get();
        }
        records = load.get();
        next = 0;
        ended = false;
        return FileStatus.OK;
    }

    @Override
    public Read read() {
        if (records == null) {
            return new Read.Failed(FileStatus.NOT_OPEN_FOR_READ);
        }
        if (next < records.size()) {
            return new Read.Found(records.get(next++), FileStatus.OK);
        }
        if (ended) {
            return new Read.Failed(FileStatus.NO_NEXT_RECORD);
        }
        ended = true;
        return new Read.Failed(FileStatus.END_OF_FILE);
    }

    @Override
    public FileStatus close() {
        if (records == null) {
            return FileStatus.NOT_OPEN;
        }
        records = null;
        return FileStatus.OK;
    }
}
