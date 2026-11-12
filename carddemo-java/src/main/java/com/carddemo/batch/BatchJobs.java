package com.carddemo.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.job.DefaultJobParametersExtractor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Every ported JCL job as a Spring Batch job named after its member, plus one job per {@link Chain} whose steps
 * launch those jobs in order. Job parameters identify a run (the schedule's business date, a report's window),
 * so the job repository refuses to repeat a completed run and resumes a failed one, as CA-7 does.
 */
@Component
public class BatchJobs {

    /** Parameters a chain hands to each job it starts; a job ignores the ones it does not read. */
    private static final String[] CHAIN_PARAMETERS = {
            "date", DailyPosting.FEED, DailyPosting.FORMAT, InterestCalculation.PARM};

    private final JobLauncher launcher;
    private final Map<String, Job> jobs = new HashMap<>();

    public BatchJobs(List<JclJob> programs, ProgramSteps steps, JobRepository repository, JobLauncher launcher) {
        this.launcher = launcher;
        programs.forEach(p -> jobs.put(p.jobName(), steps.job(p.jobName(), p.programName(), p)));
        DefaultJobParametersExtractor extractor = new DefaultJobParametersExtractor();
        extractor.setKeys(CHAIN_PARAMETERS);
        for (Chain chain : Chain.values()) {
            SimpleJobBuilder builder = null;
            for (String name : chain.jobs) {
                var step = new StepBuilder(name, repository).job(get(name)).launcher(launcher)
                        .parametersExtractor(extractor).build();
                builder = builder == null ? new JobBuilder(chain.name(), repository).start(step) : builder.next(step);
            }
            jobs.put(chain.name(), builder.build());
        }
    }

    public Job get(String name) {
        Job job = jobs.get(name);
        if (job == null) {
            throw new NoSuchElementException("No batch job " + name);
        }
        return job;
    }

    /** Runs a job to its end on this thread. */
    public JobExecution run(String name, JobParameters parameters) {
        try {
            return launcher.run(get(name), parameters);
        } catch (Exception e) {
            throw new IllegalStateException("Could not launch " + name + " " + parameters, e);
        }
    }
}
