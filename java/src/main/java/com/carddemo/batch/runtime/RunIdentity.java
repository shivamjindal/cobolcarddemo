package com.carddemo.batch.runtime;

/**
 * Why a job runs, kept as its identifying job parameters: the same identity runs once, and a second launch
 * while it runs is refused.
 */
public sealed interface RunIdentity {

    /** A deck read from {@code job_submissions} row {@code submissionId}. */
    record Submitted(long submissionId) implements RunIdentity {}
}
