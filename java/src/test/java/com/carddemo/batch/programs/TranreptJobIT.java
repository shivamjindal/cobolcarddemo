package com.carddemo.batch.programs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.batch.jcl.Gdg;
import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.jobs.JobQueue;
import com.carddemo.jobs.JobSubmissions;
import com.carddemo.jobs.SubmittedJob;
import com.carddemo.report.ReportJcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CORPT00C's deck through the internal reader: REPRO, the screen-date SORT, and CBTRN03C. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"carddemo.batch.drivers.enabled=true", "carddemo.batch.reader.poll=PT0.05S"})
@DirtiesContext
class TranreptJobIT {

    private static final String START = "2099-01-01";
    private static final String END = "2099-01-31";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private JobSubmissions submissions;

    @Autowired
    private JobQueue queue;

    @Autowired
    private DatasetStore datasets;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void aSubmittedReportRunsAndKeepsTheInWindowLines() throws Exception {
        seed();
        try {
            assertTrue(submissions.submit(new SubmittedJob("JOBS", "TRNRPT00", "TRANREPT", START, END,
                    ReportJcl.cards(START, END), "TESTER")));
            long id = jdbc.sql("select max(id) from job_submissions").query(Long.class).single();
            JobQueue.Run ended = ended(id);

            assertEquals("RC=0", ended.completion().orElseThrow());
            List<String> report = datasets.generation(Gdg.TRANREPT.base(), 0).orElseThrow().stream()
                    .map(record -> new String(record, ISO_8859_1).stripTrailing()).toList();
            assertTrue(report.getFirst().contains("Date Range: " + START + " to " + END), report.toString());
            assertTrue(report.stream().anyMatch(line -> line.contains("0000000000000R01")));
            assertTrue(report.stream().anyMatch(line -> line.contains("0000000000000R02")));
            assertTrue(report.stream().noneMatch(line -> line.contains("0000000000000R03")));
            assertEquals(1, report.stream().filter(line -> line.startsWith("Account Total")).count());
            assertTrue(report.getLast().startsWith("Grand Total"));
            assertTrue(report.getLast().endsWith("+         12.00"), report.getLast());
        } finally {
            jdbc.sql("delete from transactions where tran_id in ('0000000000000R01', '0000000000000R02',"
                    + " '0000000000000R03')").update();
            jdbc.sql("delete from card_xref where xref_card_num in ('1111111111111111', '2222222222222222')").update();
            jdbc.sql("delete from transaction_categories where tran_type_cd = 'Z9'").update();
            jdbc.sql("delete from transaction_types where tran_type = 'Z9'").update();
        }
    }

    private void seed() throws Exception {
        jdbc.sql("delete from transactions where tran_id in ('0000000000000R01', '0000000000000R02',"
                + " '0000000000000R03')").update();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("set local session_replication_role = replica");
                sql.execute("insert into transaction_types (tran_type, tran_type_desc) values ('Z9', 'Widget')"
                        + " on conflict do nothing");
                sql.execute("insert into transaction_categories (tran_type_cd, tran_cat_cd, tran_cat_type_desc)"
                        + " values ('Z9', 1, 'Parts') on conflict do nothing");
                sql.execute("insert into card_xref (xref_card_num, xref_cust_id, xref_acct_id) values"
                        + " ('1111111111111111', 1, 42), ('2222222222222222', 1, 42) on conflict do nothing");
                sql.execute("""
                        insert into transactions (tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt,
                            tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip, tran_card_num,
                            tran_orig_ts, tran_proc_ts) values
                        ('0000000000000R01', 'Z9', 1, 'POS TERM', 'Purchase', 10.00, 1, 'Store', 'City', '12345',
                            '1111111111111111', '2099-01-01 10:00:00.000000', '2099-01-01 11:00:00.000000'),
                        ('0000000000000R02', 'Z9', 1, 'POS TERM', 'Purchase', 1.00, 1, 'Store', 'City', '12345',
                            '2222222222222222', '2099-01-31 10:00:00.000000', '2099-01-31 11:00:00.000000'),
                        ('0000000000000R03', 'Z9', 1, 'POS TERM', 'Purchase', 5.00, 1, 'Store', 'City', '12345',
                            '1111111111111111', '2098-12-31 10:00:00.000000', '2098-12-31 11:00:00.000000')
                        """);
            }
            connection.commit();
        }
    }

    private JobQueue.Run ended(long submission) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            JobQueue.Run run = queue.run(submission).orElse(null);
            if (run != null && run.endedAt().isPresent()) {
                return run;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("submission " + submission + " did not end");
    }
}
