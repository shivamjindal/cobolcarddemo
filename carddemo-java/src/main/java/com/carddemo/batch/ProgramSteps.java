package com.carddemo.batch;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Builds Spring Batch jobs out of {@link BatchProgram}s. Each program step writes its SYSOUT and datasets to
 * {@code <spool>/<job>/<job execution id>/}. Programs manage their own database transactions (one per record or
 * per account, like the VSAM updates they replace), so the step itself runs without one.
 */
@Component
public class ProgramSteps {

    public static final String RETURN_CODE = "returnCode";

    private final JobRepository repository;
    private final Path spool;

    public ProgramSteps(JobRepository repository, @Value("${carddemo.batch.spool-dir}") String spool) {
        this.repository = repository;
        this.spool = Path.of(spool);
    }

    /** A job of one program step, named after the JCL member, with the step named after the program. */
    public Job job(String name, String program, BatchProgram body) {
        return new JobBuilder(name, repository).start(step(program, body)).build();
    }

    public Step step(String program, BatchProgram body) {
        return new StepBuilder(program, repository).tasklet((contribution, chunk) -> {
            JobExecution job = chunk.getStepContext().getStepExecution().getJobExecution();
            StepOutput out = new StepOutput(dir(job));
            out.clear();
            try {
                body.run(job.getJobParameters(), out);
            } catch (Exception e) {
                out.display("ABEND: " + e.getMessage());
                out.returnCode(16);
                throw e;
            } finally {
                out.writeSysout(program);
                contribution.getStepExecution().getExecutionContext().putInt(RETURN_CODE, out.returnCode());
            }
            if (out.returnCode() != 0) {
                contribution.setExitStatus(ExitStatus.COMPLETED.addExitDescription("RC=" + out.returnCode()));
            }
            return RepeatStatus.FINISHED;
        }, new ResourcelessTransactionManager()).build();
    }

    public Path dir(JobExecution job) {
        return spool.resolve(job.getJobInstance().getJobName()).resolve(String.valueOf(job.getId()));
    }
}
