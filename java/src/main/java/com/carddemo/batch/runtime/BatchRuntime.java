package com.carddemo.batch.runtime;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.jcl.StepDef;
import com.carddemo.batch.step.BatchProgram;

/**
 * Runs JCL jobs. Spring Batch keeps the run ledger and refuses a second run of an identity; each JCL step is one
 * tasklet step outside any transaction, so what a step wrote before it abended stays written, as with VSAM.
 */
@Component
public final class BatchRuntime {

    private static final DefaultTransactionAttribute NO_TRANSACTION =
            new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

    private final JobRepository repository;
    private final JobLauncher launcher;
    private final PlatformTransactionManager transactions;
    private final JdbcClient jdbc;
    private final DatasetStore store;
    private final Spool spool;
    private final Clock clock;
    private final Map<String, BatchProgram> programs;

    BatchRuntime(JobRepository repository, JobLauncher launcher, PlatformTransactionManager transactions,
            JdbcClient jdbc, DatasetStore store, Spool spool, Clock clock, List<BatchProgram> programs) {
        this.repository = repository;
        this.launcher = launcher;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.store = store;
        this.spool = spool;
        this.clock = clock;
        this.programs = programs.stream().collect(Collectors.toUnmodifiableMap(BatchProgram::name, Function.identity()));
    }

    /** Runs the job to its end on this thread. A step not reached because an earlier one failed is FLUSH. */
    public Ran run(JobDef job, RunIdentity identity) {
        JobRun run = new JobRun(store);
        JobExecution execution;
        try {
            execution = launcher.run(job(job, run), parameters(identity));
        } catch (JobExecutionException e) {
            throw new IllegalStateException(job.name() + " " + identity + " cannot run: " + e.getMessage(), e);
        }
        store.deletePending(execution.getId());
        SequencedMap<String, Completion> steps = new LinkedHashMap<>();
        for (StepDef step : job.steps()) {
            steps.put(step.path(), run.completions().getOrDefault(step.path(), new Completion.Flushed()));
        }
        return new Ran(execution.getId(), steps);
    }

    private static JobParameters parameters(RunIdentity identity) {
        return switch (identity) {
            case RunIdentity.Submitted submitted -> new JobParametersBuilder()
                    .addLong("submission", submitted.submissionId(), true).toJobParameters();
            case RunIdentity.Chained chained -> new JobParametersBuilder()
                    .addLong("chainExecution", chained.chainExecutionId(), true)
                    .addLong("member", (long) chained.member(), true).toJobParameters();
        };
    }

    private Job job(JobDef job, JobRun run) {
        SimpleJobBuilder builder = null;
        for (StepDef def : job.steps()) {
            Step step = new StepBuilder(def.path(), repository)
                    .tasklet(new StepTasklet(def, run, programs, jdbc, store, spool, clock), transactions)
                    .transactionAttribute(NO_TRANSACTION)
                    .build();
            builder = builder == null ? new JobBuilder(job.name(), repository).start(step) : builder.next(step);
        }
        if (builder == null) {
            throw new IllegalArgumentException(job.name() + " has no steps");
        }
        return builder.build();
    }
}
