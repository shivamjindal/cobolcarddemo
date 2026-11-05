package com.carddemo.batch.runtime;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.carddemo.batch.jcl.StepDef;
import com.carddemo.batch.step.Abend;
import com.carddemo.batch.step.BatchProgram;

/**
 * One JCL step: COND against the job's earlier steps, allocation, the program, DISP. A step that abends or
 * cannot be allocated fails, so Spring Batch runs no later step of the job.
 */
final class StepTasklet implements Tasklet {

    static final class StepFailed extends RuntimeException {
        StepFailed(String step, Completion completion) {
            super(step + " " + completion.label(), null, false, false);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(StepTasklet.class);

    /** What Language Environment abends with when a condition is not handled. */
    private static final String UNHANDLED_CONDITION = "U4038";

    private final StepDef def;
    private final JobRun job;
    private final Map<String, BatchProgram> programs;
    private final JdbcClient jdbc;
    private final DatasetStore store;
    private final Spool spool;
    private final Clock clock;

    StepTasklet(StepDef def, JobRun job, Map<String, BatchProgram> programs, JdbcClient jdbc, DatasetStore store,
            Spool spool, Clock clock) {
        this.def = def;
        this.job = job;
        this.programs = programs;
        this.jdbc = jdbc;
        this.store = store;
        this.spool = spool;
        this.clock = clock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunk) {
        Completion completion;
        try {
            completion = run(contribution.getStepExecution().getJobExecutionId());
        } catch (RuntimeException e) {
            log.error("{} failed outside {}", def.path(), def.program(), e);
            completion = new Completion.Abended(UNHANDLED_CONDITION);
        }
        job.record(def.path(), completion);
        contribution.setExitStatus(new ExitStatus(completion.label()));
        if (completion instanceof Completion.Abended || completion instanceof Completion.JclError) {
            throw new StepFailed(def.path(), completion);
        }
        return RepeatStatus.FINISHED;
    }

    private Completion run(long jobExecutionId) {
        if (def.cond().bypass(job.returnCodes())) {
            return new Completion.Bypassed();
        }
        Map<String, List<Allocation>> dds;
        try {
            dds = job.allocate(def, jobExecutionId);
        } catch (JobRun.Unallocatable e) {
            log.warn("{} JCL ERROR: {}", def.path(), e.getMessage());
            return new Completion.JclError(e.getMessage());
        }
        StepRun step = new StepRun(def, jobExecutionId, dds, jdbc, store, spool, clock);
        Completion completion;
        try {
            BatchProgram program = programs.get(def.program());
            if (program == null) {
                throw Abend.system("S806");
            }
            completion = new Completion.Rc(program.run(step));
        } catch (Abend abend) {
            completion = new Completion.Abended(abend.code());
        } catch (RuntimeException e) {
            log.error("{} {} failed", def.path(), def.program(), e);
            completion = new Completion.Abended(UNHANDLED_CONDITION);
        }
        step.end();
        job.dispose(dds.values(), completion instanceof Completion.Abended);
        return completion;
    }
}
