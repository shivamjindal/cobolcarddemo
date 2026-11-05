package com.carddemo.batch.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.KeyCondition;
import com.carddemo.batch.step.KeyedDataset;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.Read;
import com.carddemo.vsam.KeyRange;

/** The file status rules of a KSDS in dynamic access, over records kept in key order. */
abstract class KeyedFile implements KeyedDataset {

    /** Where READ NEXT continues: at or after {@code key}; an empty key is the start of the file. */
    private record Position(byte[] key, boolean inclusive) {}

    private static final Position START = new Position(new byte[0], true);

    /** Fails OPEN with {@code status} instead of reading. */
    static KeyedDataset failing(FileStatus status) {
        return new StoredKsds(new KeyRange(0, 1), 1, List.of()) {
            @Override
            public FileStatus open(OpenMode mode) {
                return status;
            }
        };
    }

    private final KeyRange key;
    private final int recordLength;
    private OpenMode mode;
    private Position position;
    private boolean byAlternate;

    KeyedFile(KeyRange key, int recordLength) {
        this.key = key;
        this.recordLength = recordLength;
    }

    abstract boolean writable();

    abstract Optional<byte[]> find(byte[] key);

    /** The first record whose key is above {@code from}, or equal to it when inclusive; {@code from} is empty or a full key. */
    abstract Optional<byte[]> next(byte[] from, boolean inclusive);

    abstract List<byte[]> findAlternate(byte[] key, int limit);

    abstract boolean insert(byte[] record);

    abstract boolean update(byte[] record);

    abstract boolean remove(byte[] key);

    /** OPEN OUTPUT replaces the file. */
    abstract void clear();

    @Override
    public FileStatus open(OpenMode mode) {
        if (this.mode != null) {
            return FileStatus.ALREADY_OPEN;
        }
        if (mode != OpenMode.INPUT && !writable()) {
            return FileStatus.MODE_NOT_SUPPORTED;
        }
        if (mode == OpenMode.OUTPUT) {
            clear();
        }
        this.mode = mode;
        position = START;
        byAlternate = false;
        return FileStatus.OK;
    }

    @Override
    public Read read(byte[] key) {
        if (!readable()) {
            return new Read.Failed(FileStatus.NOT_OPEN_FOR_READ);
        }
        byAlternate = false;
        Optional<byte[]> found = find(requireKey(key));
        position = found.map(record -> new Position(key.clone(), false)).orElse(null);
        return found.<Read>map(record -> new Read.Found(record, FileStatus.OK))
                .orElse(new Read.Failed(FileStatus.NOT_FOUND));
    }

    @Override
    public Read readAlternate(byte[] key) {
        if (!readable()) {
            return new Read.Failed(FileStatus.NOT_OPEN_FOR_READ);
        }
        List<byte[]> found = findAlternate(key, 2);
        position = null;
        if (found.isEmpty()) {
            byAlternate = false;
            return new Read.Failed(FileStatus.NOT_FOUND);
        }
        byAlternate = true;
        return new Read.Found(found.getFirst(), found.size() > 1 ? FileStatus.DUPLICATE_ALTERNATE_KEY : FileStatus.OK);
    }

    @Override
    public FileStatus start(byte[] key, KeyCondition condition) {
        if (!readable()) {
            return FileStatus.NOT_OPEN_FOR_READ;
        }
        if (key.length == 0 || key.length > this.key.length()) {
            throw new IllegalArgumentException("START key of " + key.length + " bytes, record key " + this.key.length());
        }
        byAlternate = false;
        Optional<byte[]> found = switch (condition) {
            case EQUAL -> next(pad(key, (byte) 0), true)
                    .filter(record -> Arrays.equals(record, this.key.offset(), this.key.offset() + key.length, key, 0,
                            key.length));
            case NOT_LESS -> next(pad(key, (byte) 0), true);
            case GREATER -> next(pad(key, (byte) 0xFF), false);
        };
        position = found.map(record -> new Position(keyOf(record), true)).orElse(null);
        return found.isPresent() ? FileStatus.OK : FileStatus.NOT_FOUND;
    }

    @Override
    public Read readNext() {
        if (!readable()) {
            return new Read.Failed(FileStatus.NOT_OPEN_FOR_READ);
        }
        if (byAlternate) {
            throw new UnsupportedOperationException("READ NEXT after a READ by the alternate key");
        }
        if (position == null) {
            return new Read.Failed(FileStatus.NO_NEXT_RECORD);
        }
        Optional<byte[]> found = next(position.key(), position.inclusive());
        position = found.map(record -> new Position(keyOf(record), false)).orElse(null);
        return found.<Read>map(record -> new Read.Found(record, FileStatus.OK))
                .orElse(new Read.Failed(FileStatus.END_OF_FILE));
    }

    @Override
    public FileStatus write(byte[] record) {
        if (mode == null || mode == OpenMode.INPUT) {
            return FileStatus.NOT_OPEN_FOR_WRITE;
        }
        if (record.length != recordLength) {
            return FileStatus.RECORD_LENGTH;
        }
        return insert(record) ? FileStatus.OK : FileStatus.DUPLICATE_KEY;
    }

    @Override
    public FileStatus rewrite(byte[] record) {
        if (mode != OpenMode.I_O) {
            return FileStatus.NOT_OPEN_FOR_REWRITE;
        }
        if (record.length != recordLength) {
            return FileStatus.RECORD_LENGTH;
        }
        return update(record) ? FileStatus.OK : FileStatus.NOT_FOUND;
    }

    @Override
    public FileStatus delete(byte[] key) {
        if (mode != OpenMode.I_O) {
            return FileStatus.NOT_OPEN_FOR_REWRITE;
        }
        return remove(requireKey(key)) ? FileStatus.OK : FileStatus.NOT_FOUND;
    }

    @Override
    public FileStatus close() {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        mode = null;
        return FileStatus.OK;
    }

    boolean isOpen() {
        return mode != null;
    }

    byte[] keyOf(byte[] record) {
        return Arrays.copyOfRange(record, key.offset(), key.offset() + key.length());
    }

    private boolean readable() {
        return mode == OpenMode.INPUT || mode == OpenMode.I_O;
    }

    private byte[] requireKey(byte[] key) {
        if (key.length != this.key.length()) {
            throw new IllegalArgumentException("key of " + key.length + " bytes, record key " + this.key.length());
        }
        return key;
    }

    private byte[] pad(byte[] key, byte fill) {
        byte[] full = new byte[this.key.length()];
        Arrays.fill(full, fill);
        System.arraycopy(key, 0, full, 0, key.length);
        return full;
    }
}
