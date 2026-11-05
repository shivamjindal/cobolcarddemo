package com.carddemo.jobs;

import java.util.List;

/** A job written to a TD queue for the internal reader: its card images rstripped, through {@code /*EOF}. */
public record SubmittedJob(String queue, String jobName, String procedure, String startDate, String endDate,
        List<String> jcl, String submittedBy) {}
