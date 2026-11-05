package com.carddemo.batch.library;

import java.util.Arrays;
import java.util.Optional;

import com.carddemo.batch.jcl.ProcDef;

/** The members of {@code app/proc} that run here, as typed JCL; {@code EXEC PROC=} resolves against them. */
public enum Proc {
    ;

    private final ProcDef proc;

    Proc(ProcDef proc) {
        this.proc = proc;
    }

    public ProcDef proc() {
        return proc;
    }

    public static Optional<ProcDef> byMember(String member) {
        return Arrays.stream(values()).map(Proc::proc).filter(p -> p.member().equals(member)).findFirst();
    }
}
