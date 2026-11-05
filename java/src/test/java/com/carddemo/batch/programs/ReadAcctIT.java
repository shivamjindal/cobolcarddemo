package com.carddemo.batch.programs;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.library.LibraryJob;
import com.carddemo.batch.runtime.BatchRuntime;
import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.batch.runtime.Ran;
import com.carddemo.batch.runtime.RunIdentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReadAcctIT {

    private static final long ACCOUNT = 99999999999L;
    private static final List<String> OUTPUTS = List.of("AWS.M2.CARDDEMO.ACCTDATA.PSCOMP",
            "AWS.M2.CARDDEMO.ACCTDATA.ARRYPS", "AWS.M2.CARDDEMO.ACCTDATA.VBPS");
    private static final String ACCOUNT_ROW = "insert into accounts (acct_id, acct_active_status, acct_curr_bal,"
            + " acct_credit_limit, acct_cash_credit_limit, acct_open_date, acct_expiraion_date, acct_reissue_date,"
            + " acct_curr_cyc_credit, acct_curr_cyc_debit, acct_addr_zip, acct_group_id) values (?, 'Y', ?, 100, 50,"
            + " '2014-11-20', '2025-05-20', '2025-05-20', 0, 0, '12546', 'A')";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private BatchRuntime runtime;

    @Autowired
    private DatasetStore datasets;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aSecondRunReplacesTheThreeOutputs() {
        jdbc.sql("delete from accounts where acct_id = ?").param(ACCOUNT).update();
        jdbc.sql(ACCOUNT_ROW).params(ACCOUNT, new BigDecimal("10.00")).update();
        try {
            JobDef job = LibraryJob.READACCT.job();
            Ran first = run(job);
            Map<String, List<byte[]>> written = outputs();

            int accounts = jdbc.sql("select count(*) from accounts").query(Integer.class).single();
            jdbc.sql("update accounts set acct_curr_bal = ? where acct_id = ?").params(new BigDecimal("20.00"), ACCOUNT)
                    .update();
            Ran second = run(job);
            Map<String, List<byte[]>> replaced = outputs();

            assertEquals("RC=0", first.completion().label());
            assertEquals("RC=0", second.completion().label());
            assertEquals(accounts, written.get(OUTPUTS.get(0)).size());
            assertEquals(accounts, written.get(OUTPUTS.get(1)).size());
            assertEquals(accounts * 2, written.get(OUTPUTS.get(2)).size());
            for (String dsn : OUTPUTS) {
                assertEquals(written.get(dsn).size(), replaced.get(dsn).size(), dsn);
                assertNotEquals(written.get(dsn), replaced.get(dsn), dsn);
            }
        } finally {
            jdbc.sql("delete from accounts where acct_id = ?").param(ACCOUNT).update();
            jdbc.sql("delete from datasets where dsn in (?, ?, ?)").params(OUTPUTS.get(0), OUTPUTS.get(1), OUTPUTS.get(2))
                    .update();
        }
    }

    private Map<String, List<byte[]>> outputs() {
        Map<String, List<byte[]>> records = new LinkedHashMap<>();
        for (String dsn : OUTPUTS) {
            records.put(dsn, datasets.read(dsn).orElseThrow());
        }
        return records;
    }

    private Ran run(JobDef job) {
        long id = jdbc.sql("insert into job_submissions (queue, job_name, jcl, submitted_by, submitted_at)"
                        + " values ('JOBS', 'READACCT', ?, 'TEST', ?) returning id")
                .params(new String[] {"//READACCT JOB"}, LocalDateTime.now()).query(Long.class).single();
        jdbc.sql("insert into job_submission_runs (submission_id, claimed_at) values (?, ?)")
                .params(id, LocalDateTime.now()).update();
        return runtime.run(job, new RunIdentity.Submitted(id));
    }
}
