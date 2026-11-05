package com.carddemo.batch.jcl;

import java.util.List;

/** A cataloged procedure: its member name and its steps, with paths of the procedure step names alone. */
public record ProcDef(String member, List<StepDef> steps) {

    public ProcDef {
        steps = List.copyOf(steps);
    }
}
