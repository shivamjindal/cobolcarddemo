package com.carddemo.batch;

import com.carddemo.online.TestDatabase;
import com.carddemo.vsam.SeedFormat;
import com.carddemo.vsam.SeedLoader;
import com.carddemo.vsam.Table;
import com.carddemo.vsam.VsamDataset;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the ported batch cycle on the same fixtures, in the same order and at the same clock as
 * characterization/batch/harness.py, and compares what each job leaves behind with the legacy golden masters.
 * build.gradle.kts echoes the "[batch-golden]" lines to the console.
 */
@SpringBootTest(properties = "carddemo.batch.spool-dir=build/batch-spool")
@Import({TestDatabase.class, BatchGoldenTest.FixedClock.class})
class BatchGoldenTest {

    /** Golden jobs whose legacy program is deliberately not ported, with the reason. */
    static final Map<String, String> NOT_PORTED = Map.of(
            "VALIDTRAN", "CBTRN01C is in no JCL member or schedule and is not ported");

    static final Path FIXTURES = Path.of(System.getProperty("carddemo.legacy-app"), "data", "ASCII");
    static final Path EDGES = Golden.ROOT.resolve("fixtures/posttran-edges");
    static final Path PURGE = Golden.ROOT.resolve("fixtures/cbpaup0j/pautdb.txt");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId zone = ZoneId.systemDefault();
            return Clock.fixed(LocalDateTime.of(2022, 7, 6, 10, 0).atZone(zone).toInstant(), zone);
        }
    }

    @Autowired
    BatchJobs jobs;

    @Autowired
    ProgramSteps steps;

    @Autowired
    SeedLoader loader;

    @Autowired
    JdbcTemplate jdbc;

    private final Map<String, String> artifacts = new LinkedHashMap<>();

    @Test
    void theBatchCycleMatchesTheLegacyGoldenMasters() throws IOException {
        cycle();
        edges();
        purge();

        Golden golden = new Golden();
        Map<String, Golden.Master> masters = Golden.load();
        List<Golden.Result> results = new ArrayList<>();
        masters.forEach((name, master) -> {
            String job = name.substring(0, name.indexOf('/'));
            if (NOT_PORTED.containsKey(job)) {
                results.add(new Golden.Result(name, Golden.Outcome.NOT_PORTED, NOT_PORTED.get(job)));
            } else if (!artifacts.containsKey(name)) {
                results.add(new Golden.Result(name, Golden.Outcome.MISSING, "no Java job wrote it"));
            } else {
                results.add(golden.compare(name, master, artifacts.get(name)));
            }
        });
        Set<String> extra = new TreeSet<>(artifacts.keySet());
        extra.removeAll(masters.keySet());

        results.forEach(r -> System.out.printf("[batch-golden] %-32s %s%s%n", r.artifact(), r.outcome(),
                r.detail().isEmpty() ? "" : ": " + r.detail()));
        Map<Golden.Outcome, Long> tally = new LinkedHashMap<>();
        results.forEach(r -> tally.merge(r.outcome(), 1L, Long::sum));
        System.out.printf("[batch-golden] %d artifacts: %s%n", results.size(), tally);
        golden.unused().forEach(u -> System.out.println("[batch-golden] UNUSED " + u));

        assertThat(results).filteredOn(r -> r.outcome() == Golden.Outcome.DIFFERS || r.outcome() == Golden.Outcome.MISSING)
                .isEmpty();
        assertThat(extra).as("artifacts with no golden master").isEmpty();
        assertThat(golden.unused()).as("overrides that no longer apply").isEmpty();
    }

    /** harness.py's main cycle: load, then READACCT, READCARD, READXREF, READCUST, POSTTRAN, INTCALC, TRANREPT, CREASTMT. */
    private void cycle() throws IOException {
        loader.load(FIXTURES, SeedFormat.ASCII);

        JobExecution accounts = run("READACCT", params());
        sysout("READACCT", accounts, "CBACT01C");
        dataset("READACCT", accounts, "OUTFILE");
        dataset("READACCT", accounts, "ARRYFILE");
        dataset("READACCT", accounts, "VBRCFILE");
        sysout("READCARD", run("READCARD", params()), "CBACT02C");
        sysout("READXREF", run("READXREF", params()), "CBACT03C");
        sysout("READCUST", run("READCUST", params()), "CBCUS01C");

        JobExecution post = run("POSTTRAN", params()
                .addString(DailyPosting.FEED, FIXTURES.resolve("dailytran.txt").toString())
                .addString(DailyPosting.FORMAT, SeedFormat.ASCII.name()));
        sysout("POSTTRAN", post, "CBTRN02C");
        dataset("POSTTRAN", post, "DALYREJS");
        unload("POSTTRAN", VsamDataset.TRANSACTIONS, "TRANSACT");
        unload("POSTTRAN", VsamDataset.ACCOUNTS, "ACCTDATA");
        unload("POSTTRAN", VsamDataset.TRAN_CAT_BALANCES, "TCATBALF");

        JobExecution interest = run("INTCALC", params().addString(InterestCalculation.PARM, "2022071800"));
        sysout("INTCALC", interest, "CBACT04C");
        dataset("INTCALC", interest, "SYSTRAN");
        unload("INTCALC", VsamDataset.ACCOUNTS, "ACCTDATA");
        unload("COMBTRAN", VsamDataset.TRANSACTIONS, "TRANSACT");

        JobExecution report = run("TRANREPT", params()
                .addString(TransactionReport.START, "2022-01-01")
                .addString(TransactionReport.END, "2022-07-06"));
        sysout("TRANREPT", report, "CBTRN03C");
        dataset("TRANREPT", report, "TRANREPT");

        JobExecution statements = run("CREASTMT", params());
        sysout("CREASTMT", statements, "CBSTM03A");
        dataset("CREASTMT", statements, "STMTFILE");
        dataset("CREASTMT", statements, "HTMLFILE");
    }

    /** harness.py's POSTTRAN-EDGES scenario: fresh fixtures plus crafted records at each validation boundary. */
    private void edges() throws IOException {
        loader.load(FIXTURES, SeedFormat.ASCII);
        Table xref = Table.of(VsamDataset.CARD_XREF);
        for (String line : Files.readAllLines(EDGES.resolve("cardxref.txt"))) {
            jdbc.update(xref.insertSql(), xref.decode("%-50s".formatted(line)));
        }

        JobExecution post = run("POSTTRAN", params()
                .addString(DailyPosting.FEED, EDGES.resolve("dailytran.txt").toString())
                .addString(DailyPosting.FORMAT, SeedFormat.ASCII.name()));
        sysout("POSTTRAN-EDGES", post, "CBTRN02C");
        dataset("POSTTRAN-EDGES", post, "DALYREJS");
        unload("POSTTRAN-EDGES", VsamDataset.TRANSACTIONS, "TRANSACT");
        artifacts.put("POSTTRAN-EDGES/ACCTDATA.ksds",
                Golden.render(Table.of(VsamDataset.ACCOUNTS).unload(jdbc).subList(0, 4)));
    }

    /** harness.py's CBPAUP0J scenario: CBPAUP0C with the member's SYSIN over the crafted DBPAUTP0. */
    private void purge() throws IOException {
        jdbc.execute("TRUNCATE pending_auth_summaries CASCADE");
        long account = 0;
        for (String line : Files.readAllLines(PURGE)) {
            String[] f = line.split(" ");
            if (f[0].equals("S")) {
                account = Long.parseLong(f[1]);
                jdbc.update("INSERT INTO pending_auth_summaries (pa_acct_id, pa_cust_id, pa_credit_limit, pa_cash_limit,"
                                + " pa_credit_balance, pa_cash_balance, pa_approved_auth_cnt, pa_declined_auth_cnt,"
                                + " pa_approved_auth_amt, pa_declined_auth_amt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        account, Integer.parseInt(f[2]), new BigDecimal(f[3]), new BigDecimal(f[4]), new BigDecimal(f[5]),
                        new BigDecimal(f[6]), Integer.parseInt(f[7]), Integer.parseInt(f[8]), new BigDecimal(f[9]),
                        new BigDecimal(f[10]));
            } else if (f[0].equals("D")) {
                jdbc.update("INSERT INTO pending_auth_details (pa_acct_id, pa_auth_date_9c, pa_auth_time_9c,"
                                + " pa_auth_resp_code, pa_transaction_amt, pa_approved_amt, pa_transaction_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                        account, 99999 - Integer.parseInt(f[1]), 999999999 - Integer.parseInt(f[2]), f[3],
                        new BigDecimal(f[4]), new BigDecimal(f[5]), f[6]);
            }
        }

        sysout("CBPAUP0J", run("CBPAUP0J", params()), "CBPAUP0C");
        List<String> segments = new ArrayList<>();
        jdbc.queryForList("SELECT * FROM pending_auth_summaries ORDER BY pa_acct_id").forEach(summary -> {
            segments.add("S %011d %09d %s %s %s %s %d %d %s %s".formatted(summary.get("pa_acct_id"),
                    summary.get("pa_cust_id"), summary.get("pa_credit_limit"), summary.get("pa_cash_limit"),
                    summary.get("pa_credit_balance"), summary.get("pa_cash_balance"),
                    summary.get("pa_approved_auth_cnt"), summary.get("pa_declined_auth_cnt"),
                    summary.get("pa_approved_auth_amt"), summary.get("pa_declined_auth_amt")));
            jdbc.queryForList("SELECT * FROM pending_auth_details WHERE pa_acct_id = ? ORDER BY pa_auth_date_9c,"
                    + " pa_auth_time_9c", summary.get("pa_acct_id")).forEach(detail -> segments.add(
                    "D %05d %09d %s %s %s %s".formatted(99999 - (int) detail.get("pa_auth_date_9c"),
                            999999999 - (int) detail.get("pa_auth_time_9c"), detail.get("pa_auth_resp_code"),
                            detail.get("pa_transaction_amt"), detail.get("pa_approved_amt"),
                            detail.get("pa_transaction_id"))));
        });
        artifacts.put("CBPAUP0J/PAUTDB", Golden.render(segments));
    }

    /** Every launch is a new job instance, so the cycle can rerun in a context another test already used. */
    private static JobParametersBuilder params() {
        return new JobParametersBuilder().addString("run", UUID.randomUUID().toString());
    }

    private JobExecution run(String job, JobParametersBuilder parameters) {
        JobParameters built = parameters.toJobParameters();
        JobExecution execution = jobs.run(job, built);
        assertThat(execution.getStatus()).as(job + " " + execution.getExitStatus()).isEqualTo(BatchStatus.COMPLETED);
        return execution;
    }

    private void sysout(String job, JobExecution execution, String program) throws IOException {
        artifacts.put(job + "/" + program + ".sysout",
                Files.readString(steps.dir(execution).resolve(program + ".sysout"), StandardCharsets.ISO_8859_1));
    }

    private void dataset(String job, JobExecution execution, String dd) throws IOException {
        String text = Files.readString(steps.dir(execution).resolve(dd), StandardCharsets.ISO_8859_1);
        List<String> records = text.isEmpty() ? List.of() : Arrays.asList(text.substring(0, text.length() - 1).split("\n", -1));
        artifacts.put(job + "/" + dd, Golden.render(records));
    }

    private void unload(String job, VsamDataset dataset, String ddName) {
        artifacts.put(job + "/" + ddName + ".ksds", Golden.render(Table.of(dataset).unload(jdbc)));
    }
}
