package com.carddemo.vsam;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.flywaydb.core.Flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = "spring.flyway.clean-disabled=false")
class VsamLoadIT {

    private static final Map<String, Integer> COUNTS = Map.of(
            "accounts", 50,
            "customers", 50,
            "cards", 50,
            "card_xref", 50,
            "transaction_types", 7,
            "transaction_categories", 18,
            "disclosure_groups", 51,
            "transaction_category_balances", 50,
            "transactions", 300,
            "users", 10);

    @Autowired
    private Flyway flyway;

    @Autowired
    private VsamLoader loader;

    @Autowired
    private RoundTripCheck check;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void loadsAndRoundTripsTheSampleFiles() throws Exception {
        flyway.clean();
        flyway.migrate();
        Path appRoot = Fixtures.appRoot();
        SchemaGenerator.Generated generated = SchemaGenerator.generate(appRoot);
        loader.load(appRoot, generated.tables());
        RoundTripCheck.Report report = check.check(appRoot, generated.tables());
        System.out.print(report);

        assertTrue(report.ok(), report.toString());
        assertEquals(0, report.fieldMismatches());
        assertEquals(0, report.countMismatches());
        for (var count : COUNTS.entrySet()) {
            Long loaded = jdbc.queryForObject("SELECT count(*) FROM " + count.getKey(), Long.class);
            assertEquals(count.getValue().longValue(), loaded, count.getKey());
        }

        assertEquals(Set.of("DISCGRP", "TCATBALF", "TRANCATG", "TRANTYPE"),
                report.fillers().stream().map(RoundTripCheck.FillerNote::cluster).collect(Collectors.toSet()));
        Map<String, Integer> fillerRows = Map.of("DISCGRP", 51, "TCATBALF", 50, "TRANCATG", 18, "TRANTYPE", 7);
        for (RoundTripCheck.FillerNote filler : report.fillers()) {
            assertEquals(fillerRows.get(filler.cluster()), filler.records(), filler.cluster());
            assertTrue(filler.example().chars().allMatch(ch -> ch == '0'), filler.example());
        }

        assertEquals(List.of("acct_id"), jdbc.queryForList("""
                SELECT a.attname
                FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
                WHERE i.indrelid = 'accounts'::regclass AND i.indisprimary
                ORDER BY array_position(i.indkey, a.attnum)
                """, String.class));
        assertEquals(List.of(
                "card_xref_xref_acct_id_aix",
                "cards_card_acct_id_aix",
                "transactions_tran_proc_ts_aix"), jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND indexname LIKE '%\\_aix' ESCAPE '\\'
                ORDER BY indexname
                """, String.class));
        assertEquals(10L, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE constraint_schema = 'public' AND constraint_type = 'FOREIGN KEY'
                """, Long.class));
        assertEquals("date", columnType("accounts", "acct_open_date"));
        assertEquals("timestamp without time zone", columnType("transactions", "tran_orig_ts"));
        assertEquals("character varying", columnType("transactions", "tran_proc_ts"));

        jdbc.update("UPDATE accounts SET acct_curr_bal = acct_curr_bal + 0.01 WHERE acct_id = 1");
        jdbc.update("DELETE FROM users WHERE sec_usr_id = 'USER0005'");
        RoundTripCheck.Report drifted = check.check(appRoot, generated.tables());
        assertEquals(1, drifted.fieldMismatches(), drifted.toString());
        assertEquals("ACCT-CURR-BAL", drifted.tables().get(0).mismatches().get(0).field());
        assertEquals(1, drifted.countMismatches(), drifted.toString());
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }
}
