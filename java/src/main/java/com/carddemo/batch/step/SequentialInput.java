package com.carddemo.batch.step;

/** A QSAM input file: records in order, then status 10. */
public interface SequentialInput {

    FileStatus open();

    Read read();

    FileStatus close();
}
