package com.carddemo.batch.step;

/** The outcome of a READ: the record and a successful status (00, 02), or a failing status. */
public sealed interface Read {

    FileStatus status();

    record Found(byte[] record, FileStatus status) implements Read {}

    record Failed(FileStatus status) implements Read {}
}
