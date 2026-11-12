package com.carddemo.batch;

/** A JCL job whose work is one legacy program, ported to Java. {@link BatchJobs} turns each into a Spring Batch job. */
public interface JclJob extends BatchProgram {

    /** The JCL member, which names the job in the schedule: POSTTRAN. */
    String jobName();

    /** The EXEC PGM= program, which names the step and its SYSOUT: CBTRN02C. */
    String programName();
}
