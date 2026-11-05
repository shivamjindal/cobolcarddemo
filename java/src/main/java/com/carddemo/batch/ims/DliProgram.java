package com.carddemo.batch.ims;

import com.carddemo.batch.step.Step;

/**
 * An IMS batch program, which {@link Dfsrrc00} runs under its PSB, as {@code EXEC PGM=DFSRRC00,PARM='BMP,name,psb'}
 * does. It is not a {@link com.carddemo.batch.step.BatchProgram}: {@code EXEC PGM=name} does not find it.
 */
public interface DliProgram {

    String name();

    /** The PSB the program's PCB masks are written for. */
    String psb();

    /** Runs to GOBACK and returns RETURN-CODE. An abend throws {@link com.carddemo.batch.step.Abend}. */
    int run(Step step, ImsRegion region);
}
