package com.carddemo.authorizations;

import com.carddemo.batch.BatchJobs;
import com.carddemo.batch.ProgramSteps;
import com.carddemo.online.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestDatabase.class)
class ExpiredAuthorizationPurgeTest {

    @Autowired
    BatchJobs jobs;

    @Autowired
    ProgramSteps steps;

    @Autowired
    PendingAuthorizations pending;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    @Test
    void aSysinWithoutExpiryOrDebugFlagKeepsFiveDaysAndPrintsOnlyTheTotals() throws IOException {
        LocalDate date = LocalDate.now(clock);
        int today = date.getYear() % 100 * 1000 + date.getDayOfYear();
        jdbc.execute("TRUNCATE pending_auth_summaries CASCADE");
        jdbc.update("INSERT INTO pending_auth_summaries (pa_acct_id, pa_approved_auth_cnt) VALUES (9, 2)");
        jdbc.update("INSERT INTO pending_auth_details (pa_acct_id, pa_auth_date_9c, pa_auth_time_9c, pa_auth_resp_code)"
                + " VALUES (9, ?, 0, '00'), (9, ?, 0, '00')", 99999 - (today - 5), 99999 - (today - 4));

        JobExecution execution = jobs.run("CBPAUP0J", new JobParametersBuilder()
                .addString("run", UUID.randomUUID().toString())
                .addString(ExpiredAuthorizationPurge.SYSIN, "XX,00000,     ,N")
                .toJobParameters());

        String rule = "*-------------------------------------*";
        assertThat(Files.readAllLines(steps.dir(execution).resolve("CBPAUP0C.sysout"))).containsExactly(
                "STARTING PROGRAM CBPAUP0C::", rule, "CBPAUP0C PARM RECEIVED :XX,00000,     ,N ",
                "TODAYS DATE            :%05d".formatted(today), " ", " ", rule,
                "# TOTAL SUMMARY READ  :+00000001", "# SUMMARY REC DELETED :+00000000",
                "# TOTAL DETAILS READ  :+00000002", "# DETAILS REC DELETED :+00000001", rule, " ",
                "", "[return code 0]");
        assertThat(pending.details(9)).extracting(a -> a.key().yyddd()).containsExactly(today - 4);
    }
}
