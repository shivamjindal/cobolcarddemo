package com.carddemo.jobs;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JobSubmissionsIT {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 10, 0);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private JobSubmissions jobs;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void empty() {
        jdbc.update("delete from job_submissions");
    }

    @Test
    void aSubmitRecordsOneRowWithItsCardsAndTheTaskClock() throws Exception {
        List<String> jcl = List.of("//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,", "PARM-START-DATE,C'2026-11-01'",
                "/*EOF");
        assertTrue(jobs.submit(new SubmittedJob("JOBS", "TRNRPT00", "TRANREPT", "2026-11-01", "2026-11-30", jcl,
                "ADMIN001")));

        Map<String, Object> row = jdbc.queryForMap("select * from job_submissions");
        assertEquals("JOBS", row.get("queue"));
        assertEquals("TRNRPT00", row.get("job_name"));
        assertEquals("TRANREPT", row.get("procedure"));
        assertEquals("2026-11-01", row.get("start_date"));
        assertEquals("2026-11-30", row.get("end_date"));
        assertEquals(jcl, Arrays.asList((String[]) ((Array) row.get("jcl")).getArray()));
        assertEquals("ADMIN001", row.get("submitted_by"));
        assertEquals(NOW, ((Timestamp) row.get("submitted_at")).toLocalDateTime());
    }

    @Test
    void aSubmitTheQueueCannotHoldIsRefused() {
        assertFalse(jobs.submit(new SubmittedJob("JOBS", "TRNRPT00X", "TRANREPT", "2026-11-01", "2026-11-30",
                List.of("/*EOF"), "ADMIN001")));
        assertEquals(0L, jdbc.queryForObject("select count(*) from job_submissions", Long.class));
    }
}
