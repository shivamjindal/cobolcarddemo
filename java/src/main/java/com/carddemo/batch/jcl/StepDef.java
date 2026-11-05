package com.carddemo.batch.jcl;

import java.util.List;
import java.util.Optional;

/**
 * One job step: {@code EXEC PGM=program} with its PARM, COND and DDs. The path is the step name, or
 * {@code step.procstep} for a step of an expanded procedure, as JCL refers back to it.
 */
public record StepDef(String path, String program, String parm, Cond cond, List<Dd> dds) {

    public StepDef {
        dds = List.copyOf(dds);
    }

    public static StepDef program(String path, String program, Dd... dds) {
        return new StepDef(path, program, "", Cond.NONE, List.of(dds));
    }

    public StepDef parm(String parm) {
        return new StepDef(path, program, parm, cond, dds);
    }

    public StepDef cond(Cond cond) {
        return new StepDef(path, program, parm, cond, dds);
    }

    public Optional<Dd> dd(String name) {
        return dds.stream().filter(dd -> dd.name().equals(name)).findFirst();
    }
}
