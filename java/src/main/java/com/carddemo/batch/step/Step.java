package com.carddemo.batch.step;

import java.time.Clock;
import java.util.Optional;

import com.carddemo.vsam.KeyRange;

/**
 * What a running program sees of its job step: the EXEC PARM, the clock, SYSOUT for DISPLAY, and its DDs. A DD
 * the step does not have still opens, with status 35, as a missing DD does on z/OS.
 */
public interface Step {

    /** EXEC PARM, empty when there is none. */
    String parm();

    Clock clock();

    /** One DISPLAY line on SYSOUT, latin-1 characters. */
    void display(String line);

    SequentialInput input(String dd);

    SequentialOutput output(String dd);

    KeyedDataset keyed(String dd);

    /** LRECL of the DD, or 0 when the DD has none or is missing. */
    int lrecl(String dd);

    /** The record key when this DD is a KSDS. */
    Optional<KeyRange> key(String dd);

    /** The data set name this DD was allocated to, when it names one. */
    Optional<String> dsn(String dd);
}
