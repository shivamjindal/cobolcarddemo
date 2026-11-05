package com.carddemo.batch.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.carddemo.vsam.KeyRange;

/** A keyed work file from the data set store (the CREASTMT TRXFL), read-only: programs only browse it. */
class StoredKsds extends KeyedFile {

    private final TreeMap<byte[], byte[]> records = new TreeMap<>(Arrays::compareUnsigned);

    StoredKsds(KeyRange key, int recordLength, List<byte[]> records) {
        super(key, recordLength);
        for (byte[] record : records) {
            this.records.put(keyOf(record), record);
        }
    }

    @Override
    boolean writable() {
        return false;
    }

    @Override
    Optional<byte[]> find(byte[] key) {
        return Optional.ofNullable(records.get(key));
    }

    @Override
    Optional<byte[]> next(byte[] from, boolean inclusive) {
        Map.Entry<byte[], byte[]> entry = inclusive ? records.ceilingEntry(from) : records.higherEntry(from);
        return Optional.ofNullable(entry).map(Map.Entry::getValue);
    }

    @Override
    List<byte[]> findAlternate(byte[] key, int limit) {
        throw new IllegalStateException("a keyed work file has no alternate key");
    }

    @Override
    boolean insert(byte[] record) {
        throw new IllegalStateException("read-only");
    }

    @Override
    boolean update(byte[] record) {
        throw new IllegalStateException("read-only");
    }

    @Override
    void clear() {
        records.clear();
    }

    @Override
    boolean remove(byte[] key) {
        throw new IllegalStateException("read-only");
    }
}
