package com.carddemo.batch;

import com.carddemo.online.TestDatabase;
import com.carddemo.vsam.SeedFormat;
import com.carddemo.vsam.SeedLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestDatabase.class)
class BatchChainTest {

    static final Path FIXTURES = Path.of(System.getProperty("carddemo.legacy-app"), "data", "ASCII");

    @Autowired
    BatchJobs jobs;

    @Autowired
    SeedLoader loader;

    @TempDir
    Path dir;

    @Test
    void theDailyChainRunsItsJobsInScheduleOrderOncePerBusinessDate() {
        loader.load(FIXTURES, SeedFormat.ASCII);
        JobParameters day = daily(FIXTURES.resolve("dailytran.txt"));

        JobExecution execution = jobs.run(Chain.DAILY.name(), day);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions().stream().map(StepExecution::getStepName)).containsExactlyElementsOf(Chain.DAILY.jobs);
        assertThatThrownBy(() -> jobs.run(Chain.DAILY.name(), day))
                .hasCauseInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void aFailedChainRestartsAtTheJobThatFailed() throws IOException {
        loader.load(FIXTURES, SeedFormat.ASCII);
        Path feed = dir.resolve("dailytran.txt");
        JobParameters day = daily(feed);

        JobExecution failed = jobs.run(Chain.DAILY.name(), day);
        Files.copy(FIXTURES.resolve("dailytran.txt"), feed);
        JobExecution restarted = jobs.run(Chain.DAILY.name(), day);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getStepExecutions().stream().map(StepExecution::getStepName)).containsExactly("CBPAUP0J", "POSTTRAN");
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getStepExecutions().stream().map(StepExecution::getStepName))
                .containsExactlyElementsOf(Chain.DAILY.jobs.subList(1, Chain.DAILY.jobs.size()));
    }

    @Test
    void withoutTheAuthorizationModuleThePurgeStepDoesNothing() throws Exception {
        var provider = new StaticListableBeanFactory().getBeanProvider(PendingAuthorizationPurge.Purge.class);
        StepOutput out = new StepOutput(dir);

        new PendingAuthorizationPurge(provider).run(new JobParameters(), out);
        out.writeSysout("CBPAUP0C");

        assertThat(Files.readAllLines(dir.resolve("CBPAUP0C.sysout"), StandardCharsets.ISO_8859_1))
                .containsExactly("CBPAUP0C: AUTHORIZATION MODULE NOT INSTALLED, NOTHING TO PURGE", "", "[return code 0]");
    }

    @Test
    void anInstalledPurgeRunsInThePurgeStep() throws Exception {
        PendingAuthorizationPurge.Purge purge = (parameters, out) -> out.display("PURGED");
        var provider = new StaticListableBeanFactory(Map.of("purge", purge)).getBeanProvider(PendingAuthorizationPurge.Purge.class);
        StepOutput out = new StepOutput(dir);

        new PendingAuthorizationPurge(provider).run(new JobParameters(), out);
        out.writeSysout("CBPAUP0C");

        assertThat(Files.readAllLines(dir.resolve("CBPAUP0C.sysout"))).containsExactly("PURGED", "", "[return code 0]");
    }

    private static JobParameters daily(Path feed) {
        return new JobParametersBuilder()
                .addString("date", UUID.randomUUID().toString())
                .addString(DailyPosting.FEED, feed.toString())
                .addString(DailyPosting.FORMAT, SeedFormat.ASCII.name())
                .toJobParameters();
    }
}
