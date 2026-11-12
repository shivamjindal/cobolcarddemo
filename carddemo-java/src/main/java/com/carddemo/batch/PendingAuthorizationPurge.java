package com.carddemo.batch;

import org.springframework.batch.core.JobParameters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * CBPAUP0J, the IMS pending-authorization purge (CBPAUP0C) that the daily chain runs before POSTTRAN. It
 * belongs to the optional authorization module; until that module provides a {@link Purge} bean, the step
 * runs and does nothing, so the chain keeps its shape.
 */
@Component
public class PendingAuthorizationPurge implements JclJob {

    /** What the authorization module plugs in. */
    public interface Purge extends BatchProgram {
    }

    private final ObjectProvider<Purge> purge;

    public PendingAuthorizationPurge(ObjectProvider<Purge> purge) {
        this.purge = purge;
    }

    @Override
    public String jobName() {
        return "CBPAUP0J";
    }

    @Override
    public String programName() {
        return "CBPAUP0C";
    }

    @Override
    public void run(JobParameters parameters, StepOutput out) throws Exception {
        Purge installed = purge.getIfAvailable();
        if (installed == null) {
            out.display("CBPAUP0C: AUTHORIZATION MODULE NOT INSTALLED, NOTHING TO PURGE");
            return;
        }
        installed.run(parameters, out);
    }
}
