package com.carddemo.vsam;

import com.carddemo.users.Credentials;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeedLoaderIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Path EBCDIC = Path.of(System.getProperty("carddemo.legacy-app"), "data", "EBCDIC");

    @Autowired
    SeedLoader loader;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    void loadTwice() {
        loader.load(EBCDIC, SeedFormat.EBCDIC);
        loader.load(EBCDIC, SeedFormat.EBCDIC);
    }

    @Test
    void everySampleRecordLandsExactlyOnce() {
        Map<String, Integer> expected = Map.of(
                "accounts", 50, "cards", 50, "card_xref", 50, "customers", 50, "transactions", 0,
                "users", 10, "disclosure_groups", 51, "tran_cat_balances", 50, "tran_categories", 18,
                "tran_types", 7);

        expected.forEach((table, count) ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isEqualTo(count));
    }

    @Test
    void accountFieldsRoundTrip() {
        Map<String, Object> account = jdbc.queryForMap("SELECT * FROM accounts WHERE acct_id = 1");

        assertThat(account).contains(
                entry("acct_active_status", "Y"),
                entry("acct_curr_bal", new BigDecimal("194.00")),
                entry("acct_credit_limit", new BigDecimal("2020.00")),
                entry("acct_cash_credit_limit", new BigDecimal("1020.00")),
                entry("acct_open_date", Date.valueOf("2014-11-20")),
                entry("acct_expiraion_date", Date.valueOf("2025-05-20")),
                entry("acct_curr_cyc_debit", new BigDecimal("0.00")),
                // The sample data carries the disclosure group in the ZIP position and leaves the group blank.
                entry("acct_addr_zip", "A000000000"),
                entry("acct_group_id", ""));
    }

    @Test
    void cardXrefAndCustomerFieldsRoundTrip() {
        assertThat(jdbc.queryForMap("SELECT * FROM cards WHERE card_num = '0500024453765740'")).contains(
                entry("card_acct_id", 50L),
                entry("card_cvv_cd", 747),
                entry("card_embossed_name", "Aniya Von"),
                entry("card_expiraion_date", Date.valueOf("2023-03-09")),
                entry("card_active_status", "Y"));
        assertThat(jdbc.queryForMap("SELECT * FROM card_xref WHERE xref_card_num = '0500024453765740'")).contains(
                entry("xref_cust_id", 50),
                entry("xref_acct_id", 50L));
        assertThat(jdbc.queryForMap("SELECT * FROM customers WHERE cust_id = 1")).contains(
                entry("cust_first_name", "Immanuel"),
                entry("cust_addr_line_2", "Apt. 802"),
                entry("cust_phone_num_1", "(908)119-8310"),
                entry("cust_ssn", 20973888),
                entry("cust_dob_yyyy_mm_dd", Date.valueOf("1961-06-08")),
                entry("cust_fico_credit_score", 274));
    }

    @Test
    void usersAndReferenceDataRoundTrip() {
        Map<String, Object> admin = jdbc.queryForMap("SELECT * FROM users WHERE sec_usr_id = 'ADMIN001'");
        assertThat(admin).contains(
                entry("sec_usr_fname", "MARGARET"),
                entry("sec_usr_lname", "GOLD"),
                entry("sec_usr_type", "A"));
        assertThat(Credentials.matches("password", (String) admin.get("sec_usr_pwd"))).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT dis_int_rate FROM disclosure_groups WHERE dis_acct_group_id = 'A000000000'"
                        + " AND dis_tran_type_cd = '01' AND dis_tran_cat_cd = 1", BigDecimal.class))
                .isEqualTo(new BigDecimal("15.00"));
        assertThat(jdbc.queryForObject("SELECT tran_type_desc FROM tran_types WHERE tran_type = '02'", String.class))
                .isEqualTo("Payment");
    }

    @Test
    void alternateIndexesExist() {
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname NOT LIKE '%pkey' AND indexname NOT LIKE 'flyway%'", String.class))
                .containsExactlyInAnyOrder("cards_card_acct_id_idx", "card_xref_xref_acct_id_idx", "transactions_tran_proc_ts_idx");
    }

    @Test
    void picWidthIsEnforced() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tran_types (tran_type, tran_type_desc) VALUES ('123', 'too wide')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO card_xref VALUES ('X', 1, 100000000000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void usersRejectClearTextPasswordsAndLowerCaseIds() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users VALUES ('USER0099', 'A', 'B', 'PASSWORD', 'U')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users VALUES ('user0099', 'A', 'B', ?, 'U')", Credentials.hash("x")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
