package com.carddemo.batch.chains;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import com.carddemo.batch.runtime.BatchRuntime;
import com.carddemo.batch.runtime.Completion;
import com.carddemo.batch.runtime.Ran;
import com.carddemo.batch.runtime.RunIdentity;

/**
 * Runs chains. A chain run is one Spring Batch job named after the chain and identified by its order date, with a
 * step per member; each step runs its member as its own JCL job, so COND and relative generations stay scoped to
 * that job. A member that does not end OK fails its step and no later member runs, so a chain stopped between
 * CLOSEFIL and OPENFIL leaves the CICS files closed until it is restarted or an operator opens them.
 */
@Component
public final class ChainRunner {

    /** Why a chain did not run. */
    public static final class Refused extends RuntimeException {
        Refused(String reason) {
            super(reason);
        }
    }

    private static final class MemberFailed extends RuntimeException {
        MemberFailed(String step, Completion completion) {
            super(step + " " + completion.label(), null, false, false);
        }
    }

    /** A nested launch refuses to start inside a transaction. */
    private static final DefaultTransactionAttribute NO_TRANSACTION =
            new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

    private static final String JOB_EXECUTION = "jobExecutionId";

    private final JobRepository repository;
    private final JobLauncher launcher;
    private final PlatformTransactionManager transactions;
    private final BatchRuntime runtime;

    ChainRunner(JobRepository repository, JobLauncher launcher, PlatformTransactionManager transactions,
            BatchRuntime runtime) {
        this.repository = repository;
        this.launcher = launcher;
        this.transactions = transactions;
        this.runtime = runtime;
    }

    /** Runs the chain for {@code orderDate} to its end on this thread, once per order date. */
    public ChainRun start(Chain chain, LocalDate orderDate) {
        refuseUnrunnable(chain);
        if (repository.getJobInstance(chain.name(), parameters(orderDate)) != null) {
            throw new Refused(chain.name() + " has already started for " + orderDate);
        }
        return launch(chain, orderDate);
    }

    /** Runs a failed chain again from the member that did not end OK. Members that ended OK do not run again. */
    public ChainRun restart(Chain chain, LocalDate orderDate) {
        refuseUnrunnable(chain);
        JobExecution last = repository.getLastJobExecution(chain.name(), parameters(orderDate));
        if (last == null) {
            throw new Refused(chain.name() + " has not started for " + orderDate);
        }
        if (last.getStatus() != BatchStatus.FAILED) {
            throw new Refused(chain.name() + " for " + orderDate + " is " + last.getStatus());
        }
        return launch(chain, orderDate);
    }

    /** The chain's run for {@code orderDate} as it stands, empty when it has not started. */
    public Optional<ChainRun> run(Chain chain, LocalDate orderDate) {
        JobInstance instance = repository.getJobInstance(chain.name(), parameters(orderDate));
        if (instance == null) {
            return Optional.empty();
        }
        List<ChainRun.MemberRun> members = new ArrayList<>();
        for (int i = 0; i < chain.members().size(); i++) {
            Member member = chain.members().get(i);
            StepExecution last = repository.getLastStepExecution(instance, stepName(i, member));
            members.add(new ChainRun.MemberRun(member.name(), completion(last), jobExecution(last)));
        }
        return Optional.of(new ChainRun(chain.name(), orderDate, members));
    }

    private ChainRun launch(Chain chain, LocalDate orderDate) {
        try {
            launcher.run(job(chain), parameters(orderDate));
        } catch (JobExecutionException e) {
            throw new Refused(chain.name() + " for " + orderDate + ": " + e.getMessage());
        }
        return run(chain, orderDate).orElseThrow();
    }

    private static void refuseUnrunnable(Chain chain) {
        if (!chain.runnable()) {
            throw new Refused(chain.name() + " cannot run: " + chain.unported().stream()
                    .map(u -> u.name() + " (" + u.reason() + ")").toList());
        }
    }

    private static JobParameters parameters(LocalDate orderDate) {
        return new JobParametersBuilder().addLocalDate("orderDate", orderDate, true).toJobParameters();
    }

    /** Positional, because a walk of the CA-7 export can trigger the same job more than once. */
    private static String stepName(int position, Member member) {
        return (position + 1) + "." + member.name();
    }

    private Job job(Chain chain) {
        SimpleJobBuilder builder = null;
        for (int i = 0; i < chain.members().size(); i++) {
            Member member = chain.members().get(i);
            Step step = new StepBuilder(stepName(i, member), repository)
                    .tasklet(tasklet(i, member), transactions)
                    .transactionAttribute(NO_TRANSACTION)
                    .build();
            builder = builder == null ? new JobBuilder(chain.name(), repository).start(step) : builder.next(step);
        }
        return builder.build();
    }

    private Tasklet tasklet(int position, Member member) {
        return switch (member) {
            case Member.Job job -> (contribution, chunk) -> {
                StepExecution step = contribution.getStepExecution();
                Ran ran = runtime.run(job.job().job(), new RunIdentity.Chained(step.getJobExecutionId(), position));
                step.getExecutionContext().putLong(JOB_EXECUTION, ran.jobExecutionId());
                contribution.setExitStatus(new ExitStatus(ran.completion().label()));
                if (!ChainRun.endedOk(ran.completion())) {
                    throw new MemberFailed(step.getStepName(), ran.completion());
                }
                return RepeatStatus.FINISHED;
            };
            case Member.Optional optional -> (contribution, chunk) -> {
                contribution.setExitStatus(new ExitStatus(new Completion.Bypassed().label()));
                return RepeatStatus.FINISHED;
            };
            case Member.Unported unported -> throw new IllegalArgumentException(unported.name() + " is not ported");
        };
    }

    private static Optional<Completion> completion(StepExecution step) {
        if (step == null || step.getStatus().isRunning()) {
            return Optional.empty();
        }
        return Optional.of(Completion.parse(step.getExitStatus().getExitCode()));
    }

    private static OptionalLong jobExecution(StepExecution step) {
        return step != null && step.getExecutionContext().containsKey(JOB_EXECUTION)
                ? OptionalLong.of(step.getExecutionContext().getLong(JOB_EXECUTION))
                : OptionalLong.empty();
    }
}
