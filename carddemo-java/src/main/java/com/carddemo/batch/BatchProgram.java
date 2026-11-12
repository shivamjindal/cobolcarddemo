package com.carddemo.batch;

import org.springframework.batch.core.JobParameters;

/** A legacy batch program (one JCL EXEC PGM= step) ported to Java. Throwing ends the step like an abend. */
@FunctionalInterface
public interface BatchProgram {

    void run(JobParameters parameters, StepOutput out) throws Exception;
}
