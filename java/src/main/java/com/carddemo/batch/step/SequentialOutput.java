package com.carddemo.batch.step;

/**
 * A QSAM output file, opened OUTPUT or EXTEND. A fixed-format DD with an LRECL takes records of exactly that
 * length (status 44 otherwise); a variable-format DD takes any length up to its LRECL less the 4-byte record
 * descriptor word. Records reach the data set when the file is closed or the step ends.
 */
public interface SequentialOutput {

    FileStatus open(OpenMode mode);

    FileStatus write(byte[] record);

    FileStatus close();
}
