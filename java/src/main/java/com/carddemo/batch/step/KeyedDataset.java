package com.carddemo.batch.step;

/**
 * A KSDS opened through a DD, with the COBOL file statuses of dynamic access. Keys are the record's key bytes;
 * a key no record can hold reads as not found (23). READ NEXT follows the primary key, from the start of the
 * file, a START, or the last record read by primary key.
 */
public interface KeyedDataset {

    FileStatus open(OpenMode mode);

    Read read(byte[] key);

    /** READ by the alternate key: status 02 when another record has the same alternate key. */
    Read readAlternate(byte[] key);

    /** A key shorter than the record key compares as a leading portion of it. */
    FileStatus start(byte[] key, KeyCondition condition);

    Read readNext();

    FileStatus write(byte[] record);

    FileStatus rewrite(byte[] record);

    FileStatus delete(byte[] key);

    FileStatus close();
}
