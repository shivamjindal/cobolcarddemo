package com.carddemo.batch;

import com.carddemo.accounts.Account;
import com.carddemo.accounts.AccountRepository;
import com.carddemo.online.TestDatabase;
import com.carddemo.vsam.SeedFormat;
import com.carddemo.vsam.SeedLoader;
import com.carddemo.vsam.Table;
import com.carddemo.vsam.VsamDataset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestDatabase.class)
class DailyPostingTest {

    static final Path FIXTURES = Path.of(System.getProperty("carddemo.legacy-app"), "data", "ASCII");
    static final Path EDGES = Path.of(System.getProperty("carddemo.batch-characterization"), "fixtures", "posttran-edges");

    @Autowired
    BatchJobs jobs;

    @Autowired
    ProgramSteps steps;

    @Autowired
    SeedLoader loader;

    @Autowired
    AccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @TempDir
    Path dir;

    @BeforeEach
    void freshFixtures() {
        loader.load(FIXTURES, SeedFormat.ASCII);
    }

    @Test
    void rerunningAFeedRepeatsEveryEarlierOutcome() throws IOException {
        JobExecution first = post(FIXTURES.resolve("dailytran.txt"));
        int posted = count();
        List<String> balances = Table.of(VsamDataset.ACCOUNTS).unload(jdbc);

        JobExecution second = post(FIXTURES.resolve("dailytran.txt"));

        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(posted).isEqualTo(277);
        assertThat(count()).isEqualTo(posted);
        assertThat(Table.of(VsamDataset.ACCOUNTS).unload(jdbc)).isEqualTo(balances);
        assertThat(sysout(second)).filteredOn(l -> l.startsWith("ALREADY POSTED : ")).hasSize(posted);
        assertThat(sysout(second)).filteredOn(l -> l.startsWith("ALREADY REJECTED : ")).hasSize(300 - posted);
        assertThat(dataset(second, "DALYREJS")).isEqualTo(dataset(first, "DALYREJS"));
    }

    @Test
    void aFeedRecordClashingWithAPostedTransactionAbendsAfterKeepingWhatPosted() throws IOException {
        String record = Files.readAllLines(FIXTURES.resolve("dailytran.txt")).getFirst();
        String otherAmount = record.substring(0, 140) + (record.charAt(140) == '9' ? '8' : '9') + record.substring(141);
        Path feed = Files.write(dir.resolve("clash.txt"), List.of(record, otherAmount), StandardCharsets.ISO_8859_1);

        JobExecution execution = post(feed);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(sysout(execution)).contains("ABEND: TRAN-ID " + record.substring(0, 16) + " is already on file with other data",
                "[return code 16]");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void postingWaitsForAnOnlineUpdateHoldingTheAccountLock() throws Exception {
        String atLimit = Files.readAllLines(EDGES.resolve("dailytran.txt")).get(2);
        Path feed = Files.write(dir.resolve("one.txt"), List.of(atLimit), StandardCharsets.ISO_8859_1);
        BigDecimal before = accounts.find(1).orElseThrow().currentBalance();
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> online = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            Account a = accounts.lock(1).orElseThrow();
            locked.countDown();
            sleep(500);
            accounts.rewrite(a.withBalances(a.currentBalance().add(new BigDecimal("100.00")), a.currentCycleCredit(),
                    a.currentCycleDebit()));
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        JobExecution execution = post(feed);
        online.get(10, TimeUnit.SECONDS);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(accounts.find(1).orElseThrow().currentBalance())
                .isEqualByComparingTo(before.add(new BigDecimal("100.00")).add(new BigDecimal("2020.00")));
    }

    private JobExecution post(Path feed) {
        return jobs.run("POSTTRAN", new JobParametersBuilder()
                .addString(DailyPosting.FEED, feed.toString())
                .addString(DailyPosting.FORMAT, SeedFormat.ASCII.name())
                .addString("run", UUID.randomUUID().toString())
                .toJobParameters());
    }

    private List<String> sysout(JobExecution execution) throws IOException {
        return Files.readAllLines(steps.dir(execution).resolve("CBTRN02C.sysout"), StandardCharsets.ISO_8859_1);
    }

    private List<String> dataset(JobExecution execution, String dd) throws IOException {
        return Files.readAllLines(steps.dir(execution).resolve(dd), StandardCharsets.ISO_8859_1);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
