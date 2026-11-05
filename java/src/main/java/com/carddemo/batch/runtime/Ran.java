package com.carddemo.batch.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

/** A finished job: its execution, which keys its spool, and every step's completion in order. */
public record Ran(long jobExecutionId, SequencedMap<String, Completion> steps) {

    public Ran {
        steps = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(steps));
    }

    public Completion completion() {
        return Completion.worst(steps.values());
    }
}
