package com.carddemo.jobs;

/** {@code WRITEQ TD} of a whole job. Records the request in the task's transaction; the batch slice runs it. */
public interface JobSubmissions {

    /** False is any RESP but NORMAL, which CORPT00C reports as "Unable to Write TDQ (JOBS)...". */
    boolean submit(SubmittedJob job);
}
