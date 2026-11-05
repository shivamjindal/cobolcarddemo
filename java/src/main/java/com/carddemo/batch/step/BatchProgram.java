package com.carddemo.batch.step;

/**
 * A batch load module, run by {@code EXEC PGM=}{@link #name()}. Utilities (SORT, IDCAMS, IEBGENER) are batch
 * programs too, reading their control statements from a DD.
 */
public interface BatchProgram {

    String name();

    /** Runs to GOBACK or STOP RUN and returns RETURN-CODE. An abend throws {@link Abend}. */
    int run(Step step);
}
