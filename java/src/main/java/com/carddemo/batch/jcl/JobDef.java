package com.carddemo.batch.jcl;

import java.util.List;

/** A JCL job: its name and its steps in order, procedures already expanded. */
public record JobDef(String name, List<StepDef> steps) {

    public JobDef {
        steps = List.copyOf(steps);
    }

    public static JobDef of(String name, StepDef... steps) {
        return new JobDef(name, List.of(steps));
    }
}
