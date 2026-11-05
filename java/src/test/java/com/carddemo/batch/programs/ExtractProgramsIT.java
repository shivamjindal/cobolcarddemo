package com.carddemo.batch.programs;

import static com.carddemo.batch.jcl.Dd.sysout;
import static com.carddemo.batch.jcl.StepDef.program;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.batch.jcl.Dcb;
import com.carddemo.batch.jcl.JobDef;
import com.carddemo.batch.library.LibraryJob;
import com.carddemo.batch.runtime.BatchRuntime;
import com.carddemo.batch.runtime.DatasetStore;
import com.carddemo.batch.runtime.Ran;
import com.carddemo.batch.runtime.RunIdentity;
import com.carddemo.batch.runtime.Spool;
import com.carddemo.records.Fields;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExtractProgramsIT {

    private static final long ACCOUNT = 88000000001L;
    private static final String CARD = "8800000000000001";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private BatchRuntime runtime;

    @Autowired
    private Spool spool;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DatasetStore datasets;

    @Test
    void eachExtractListsItsClusterBetweenTheBannersAndEndsRcZero() {
        Map<LibraryJob, String> tables = Map.of(LibraryJob.READCARD, "cards", LibraryJob.READXREF, "card_xref",
                LibraryJob.READCUST, "customers");
        Map<LibraryJob, Integer> printsPerRecord = Map.of(LibraryJob.READCARD, 1, LibraryJob.READXREF, 2,
                LibraryJob.READCUST, 2);
        for (LibraryJob job : tables.keySet()) {
            String program = job.job().steps().getFirst().program();
            int records = jdbc.sql("select count(*) from " + tables.get(job)).query(Integer.class).single();
            Ran ran = run(job.job());
            List<String> sysout = displayed(ran, "STEP05");

            assertEquals("RC=0", ran.completion().label(), job.name());
            assertEquals("START OF EXECUTION OF PROGRAM " + program, sysout.getFirst());
            assertEquals("END OF EXECUTION OF PROGRAM " + program, sysout.getLast());
            assertEquals(records * printsPerRecord.get(job), sysout.size() - 2, job.name());
        }
    }

    @Test
    void aFileThatDoesNotOpenShowsItsStatusAndAbendsU0999() {
        Ran ran = run(JobDef.of("NOCUST", program("STEP05", Cbcus01c.NAME, sysout("SYSOUT"))));

        assertEquals("U0999", ran.completion().label());
        assertEquals(List.of("START OF EXECUTION OF PROGRAM CBCUS01C", "ERROR OPENING CUSTFILE", "FILE STATUS IS: NNNN0035",
                "ABENDING PROGRAM"), displayed(ran, "STEP05"));
    }

    @Test
    void posttranKeepsTheRejectGenerationWhenTheStepEndsRc4() {
        byte[] daily = rejectedDaily();
        datasets.stage("AWS.M2.CARDDEMO.DALYTRAN.PS", new Dcb(Dcb.Recfm.F, daily.length), Optional.empty(), List.of(daily));

        Ran ran = run(LibraryJob.POSTTRAN.job());

        assertEquals("RC=4", ran.completion().label());
        Cataloged rejects = cataloged(ran, "AWS.M2.CARDDEMO.DALYREJS");
        assertEquals("F", rejects.recfm());
        assertEquals(430, rejects.lrecl());
        assertTrue(rejects.generation() > 0);
        byte[] written = record(rejects.id());
        assertEquals(430, written.length);
        assertEquals(new String(daily, ISO_8859_1), new String(written, 0, daily.length, ISO_8859_1));
        assertTrue(new String(written, daily.length, 80, ISO_8859_1).startsWith("0100INVALID CARD NUMBER FOUND"));
    }

    @Test
    void intcalcCatalogsTheInterestGeneration() {
        seedOneCategory();

        Ran ran = run(LibraryJob.INTCALC.job());

        assertEquals("RC=0", ran.completion().label());
        Cataloged interest = cataloged(ran, "AWS.M2.CARDDEMO.SYSTRAN");
        assertEquals("F", interest.recfm());
        assertEquals(350, interest.lrecl());
        assertTrue(interest.generation() > 0);
        byte[] written = record(interest.id());
        assertEquals(350, written.length);
        assertEquals("2022071800000001", new String(written, 0, 16, ISO_8859_1));
    }

    private Ran run(JobDef job) {
        long id = jdbc.sql("insert into job_submissions (queue, job_name, jcl, submitted_by, submitted_at)"
                        + " values ('JOBS', 'TEST', ?, 'TEST', ?) returning id")
                .params(new String[] {"//TEST JOB"}, LocalDateTime.now()).query(Long.class).single();
        jdbc.sql("insert into job_submission_runs (submission_id, claimed_at) values (?, ?)")
                .params(id, LocalDateTime.now()).update();
        return runtime.run(job, new RunIdentity.Submitted(id));
    }

    private List<String> displayed(Ran ran, String step) {
        return spool.read(ran.jobExecutionId(), step, Spool.DISPLAY).stream()
                .map(line -> new String(line, ISO_8859_1).stripTrailing()).toList();
    }

    private void seedOneCategory() {
        jdbc.sql("delete from transaction_category_balances").update();
        jdbc.sql("delete from card_xref where xref_card_num = ?").param(CARD).update();
        jdbc.sql("delete from cards where card_num = ?").param(CARD).update();
        jdbc.sql("delete from accounts where acct_id = ?").param(ACCOUNT).update();
        jdbc.sql("delete from customers where cust_id = 880000001").update();
        jdbc.sql("insert into customers (cust_id, cust_first_name, cust_middle_name, cust_last_name,"
                + " cust_addr_line_1, cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd, cust_addr_country_cd,"
                + " cust_addr_zip, cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id,"
                + " cust_dob_yyyy_mm_dd, cust_eft_account_id, cust_pri_card_holder_ind, cust_fico_credit_score)"
                + " values (880000001, 'A', '', 'B', '1 MAIN', '', '', 'NY', 'USA', '10001', '', '', 1, '',"
                + " '1980-01-01', '', 'Y', 700)").update();
        jdbc.sql("insert into accounts (acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,"
                + " acct_cash_credit_limit, acct_open_date, acct_expiraion_date, acct_reissue_date,"
                + " acct_curr_cyc_credit, acct_curr_cyc_debit, acct_addr_zip, acct_group_id)"
                + " values (?, 'Y', 194.00, 2020.00, 1020.00, '2020-01-01', '2028-01-01', '2024-01-01',"
                + " 30.00, -5.00, '10001', 'DEFAULT')").param(ACCOUNT).update();
        jdbc.sql("insert into cards (card_num, card_acct_id, card_cvv_cd, card_embossed_name, card_expiraion_date,"
                + " card_active_status) values (?, ?, 123, 'A B', '2028-01-01', 'Y')").params(CARD, ACCOUNT).update();
        jdbc.sql("insert into card_xref (xref_card_num, xref_cust_id, xref_acct_id) values (?, 880000001, ?)")
                .params(CARD, ACCOUNT).update();
        jdbc.sql("insert into disclosure_groups (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate)"
                + " values ('DEFAULT', '01', 1, 15.00)"
                + " on conflict (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)"
                + " do update set dis_int_rate = excluded.dis_int_rate").update();
        jdbc.sql("insert into transaction_category_balances"
                + " (trancat_acct_id, trancat_type_cd, trancat_cd, tran_cat_bal) values (?, '01', 1, 1000.00)")
                .param(ACCOUNT).update();
    }

    private static byte[] rejectedDaily() {
        byte[] daily = new byte[350];
        Arrays.fill(daily, (byte) ' ');
        Fields.text(daily, 0, 16, "T000000000000099");
        Fields.text(daily, 16, 2, "01");
        Fields.zoned(daily, 18, 4, 0, false, BigDecimal.ONE);
        Fields.text(daily, 22, 10, "POS TERM");
        Fields.text(daily, 32, 100, "Rejected");
        Fields.zoned(daily, 132, 11, 2, true, new BigDecimal("1.00"));
        Fields.zoned(daily, 143, 9, 0, false, BigDecimal.ZERO);
        Fields.text(daily, 262, 16, "0000000000000000");
        Fields.text(daily, 278, 26, "2025-01-15 12:00:00.000000");
        return daily;
    }

    private Cataloged cataloged(Ran ran, String dsn) {
        return jdbc.sql("select id, generation, recfm, lrecl from datasets"
                        + " where job_execution_id = ? and dsn = ? and state = 'CATALOGED'")
                .params(ran.jobExecutionId(), dsn)
                .query((rs, row) -> new Cataloged(rs.getLong("id"), rs.getInt("generation"), rs.getString("recfm"),
                        rs.getInt("lrecl")))
                .single();
    }

    private byte[] record(long datasetId) {
        return jdbc.sql("select record from dataset_records where dataset_id = ?")
                .param(datasetId).query((rs, row) -> rs.getBytes("record")).single();
    }

    private record Cataloged(long id, int generation, String recfm, int lrecl) {}
}
