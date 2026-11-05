package com.carddemo.batch.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.SequentialOutput;

/** A DD written front to back. Records reach the data set or the spool at CLOSE, or at step end if never closed. */
final class BufferedOutput implements SequentialOutput {

    /** Where a closed output's records go; {@code extend} when they follow what is already there. */
    interface Target {
        void write(List<byte[]> records, boolean extend);
    }

    /** Fails OPEN with {@code status} instead of writing. */
    static BufferedOutput failing(FileStatus status) {
        return new BufferedOutput(Optional.of(status), Optional.empty(), (records, extend) -> {});
    }

    private static final int RDW = 4;

    private final Optional<FileStatus> openFails;
    private final Optional<Dcb> dcb;
    private final Target target;
    private final List<byte[]> buffered = new ArrayList<>();
    private OpenMode mode;

    BufferedOutput(Optional<Dcb> dcb, Target target) {
        this(Optional.empty(), dcb, target);
    }

    private BufferedOutput(Optional<FileStatus> openFails, Optional<Dcb> dcb, Target target) {
        this.openFails = openFails;
        this.dcb = dcb;
        this.target = target;
    }

    @Override
    public FileStatus open(OpenMode mode) {
        if (this.mode != null) {
            return FileStatus.ALREADY_OPEN;
        }
        if (openFails.isPresent()) {
            return openFails.get();
        }
        if (mode != OpenMode.OUTPUT && mode != OpenMode.EXTEND) {
            return FileStatus.MODE_NOT_SUPPORTED;
        }
        this.mode = mode;
        return FileStatus.OK;
    }

    @Override
    public FileStatus write(byte[] record) {
        if (mode == null) {
            return FileStatus.NOT_OPEN_FOR_WRITE;
        }
        if (dcb.isPresent() && dcb.get().lrecl() > 0 && !fits(dcb.get(), record.length)) {
            return FileStatus.RECORD_LENGTH;
        }
        buffered.add(record.clone());
        return FileStatus.OK;
    }

    @Override
    public FileStatus close() {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        target.write(List.copyOf(buffered), mode == OpenMode.EXTEND);
        buffered.clear();
        mode = null;
        return FileStatus.OK;
    }

    void closeIfOpen() {
        if (mode != null) {
            close();
        }
    }

    private static boolean fits(Dcb dcb, int length) {
        return switch (dcb.recfm()) {
            case F -> length == dcb.lrecl();
            case V -> length <= dcb.lrecl() - RDW;
        };
    }
}
