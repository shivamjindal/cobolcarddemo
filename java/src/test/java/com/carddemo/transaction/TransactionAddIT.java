package com.carddemo.transaction;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** COTRN02C over real HTTP: a user adds a transaction by card number and copies it back with PF5. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionAddIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CARD = "4111222233334444";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("delete from transactions");
        jdbc.update("delete from card_xref");
        jdbc.update("delete from cards");
        jdbc.update("delete from transaction_category_balances");
        jdbc.update("delete from accounts");
        jdbc.update("delete from customers");
        jdbc.update("delete from users");
        jdbc.update("insert into users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type, sec_usr_filler)"
                + " values ('USER0001', 'ARUN', 'RAO', 'PASSWORD', 'U', '')");
        jdbc.update("insert into accounts (acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,"
                + " acct_cash_credit_limit, acct_open_date, acct_expiraion_date, acct_reissue_date, acct_curr_cyc_credit,"
                + " acct_curr_cyc_debit, acct_addr_zip, acct_group_id)"
                + " values (41, 'Y', 0, 5000, 1000, '2020-01-01', '2030-01-01', '2025-01-01', 0, 0, '98101', 'A')");
        jdbc.update("insert into customers (cust_id, cust_first_name, cust_middle_name, cust_last_name, cust_addr_line_1,"
                + " cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd, cust_addr_country_cd, cust_addr_zip,"
                + " cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id, cust_dob_yyyy_mm_dd,"
                + " cust_eft_account_id, cust_pri_card_holder_ind, cust_fico_credit_score)"
                + " values (7, 'ARUN', '', 'RAO', '1 Main St', '', 'Seattle', 'WA', 'USA', '98101', '', '', 123456789,"
                + " '', '1980-01-01', '', 'Y', 700)");
        jdbc.update("insert into cards (card_num, card_acct_id, card_cvv_cd, card_embossed_name, card_expiraion_date,"
                + " card_active_status) values (?, 41, 123, 'ARUN RAO', '2030-01-01', 'Y')", CARD);
        jdbc.update("insert into card_xref (xref_card_num, xref_cust_id, xref_acct_id) values (?, 7, 41)", CARD);
        for (String id : new String[] {"0000000000000007", "0000000000000041"}) {
            jdbc.update("insert into transactions (tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt,"
                            + " tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip, tran_card_num,"
                            + " tran_orig_ts, tran_proc_ts) values (?, '01', 1, 'POS TERM', 'Seeded', 1.00, 1, 'M', 'C', 'Z',"
                            + " ?, '2022-06-10 08:00:00.000000', '2022-06-11 08:00:00.000000')", id, CARD);
        }
    }

    @Test
    void aUserAddsATransactionByCardAndCopiesItBack() throws Exception {
        Browser browser = new Browser(URI.create("http://127.0.0.1:" + port));
        JsonNode signon = browser.start("CC00");
        JsonNode menu = browser.press(signon, "sign-on", Map.of("USERID", "USER0001", "PASSWD", "PASSWORD"));
        JsonNode add = browser.press(menu, "select", Map.of("OPTION", "8"));
        assertEquals("transaction-add", add.path("screen").asText());
        assertEquals("ACTIDIN", add.path("cursor").asText());

        Map<String, String> typed = new HashMap<>(Map.of("CARDNIN", CARD, "TTYPCD", "02", "TCATCD", "0005",
                "TRNSRC", "OPERATOR", "TDESC", "Refund at counter", "TRNAMT", "-00000100.25", "TORIGDT", "2026-11-01",
                "TPROCDT", "2026-11-03", "MID", "000000077", "MNAME", "Corner Cafe"));
        typed.putAll(Map.of("MCITY", "Seattle", "MZIP", "98101"));
        JsonNode prompted = browser.press(add, "add", typed);
        assertEquals("Confirm to add this transaction...", prompted.at("/fields/ERRMSG").asText());
        assertEquals("00000000041", prompted.at("/fields/ACTIDIN").asText(), "the card's account from the xref");
        assertEquals("CONFIRM", prompted.path("cursor").asText());

        JsonNode declined = browser.press(prompted, "add", Map.of("CONFIRM", "N"));
        assertEquals("Confirm to add this transaction...", declined.at("/fields/ERRMSG").asText());
        assertEquals(2, rows(), "confirm N writes nothing");

        JsonNode added = browser.press(declined, "add", Map.of("CONFIRM", "Y"));
        assertEquals("Transaction added successfully.  Your Tran ID is 0000000000000042.",
                added.at("/fields/ERRMSG").asText());
        assertEquals("GREEN", added.at("/colors/ERRMSG").asText());
        assertEquals("", added.at("/fields/TDESC").asText(), "the fields are cleared");
        assertEquals("ACTIDIN", added.path("cursor").asText());
        Map<String, Object> row = jdbc.queryForMap("select * from transactions where tran_id = '0000000000000042'");
        assertEquals("02", row.get("tran_type_cd"));
        assertEquals(5, row.get("tran_cat_cd"));
        assertEquals("OPERATOR", row.get("tran_source"));
        assertEquals("Refund at counter", row.get("tran_desc"));
        assertEquals(new BigDecimal("-100.25"), row.get("tran_amt"));
        assertEquals(77, row.get("tran_merchant_id"));
        assertEquals(CARD, row.get("tran_card_num"));
        assertEquals("2026-11-01", row.get("tran_orig_ts"));
        assertEquals("2026-11-03", row.get("tran_proc_ts"));

        JsonNode copied = browser.press(added, "copy-last", Map.of("ACTIDIN", "00000000041"));
        assertEquals(CARD, copied.at("/fields/CARDNIN").asText());
        assertEquals("Refund at counter", copied.at("/fields/TDESC").asText());
        assertEquals("-00000100.25", copied.at("/fields/TRNAMT").asText());
        assertEquals("0005", copied.at("/fields/TCATCD").asText());
        assertEquals("000000077", copied.at("/fields/MID").asText());
        assertEquals("2026-11-03", copied.at("/fields/TPROCDT").asText());
        assertEquals("Confirm to add this transaction...", copied.at("/fields/ERRMSG").asText());
        assertEquals(3, rows());
    }

    private int rows() {
        return jdbc.queryForObject("select count(*) from transactions", Integer.class);
    }

    private static final class Browser {
        private final URI server;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        Browser(URI server) {
            this.server = server;
        }

        JsonNode start(String transid) throws Exception {
            http.send(HttpRequest.newBuilder(server.resolve("/api/session")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return post("/api/session", Map.of("transid", transid));
        }

        JsonNode press(JsonNode on, String action, Map<String, String> fields) throws Exception {
            return post("/api/screens/" + on.path("screen").asText() + "/" + action,
                    Map.of("turn", on.path("turn").asLong(), "fields", fields));
        }

        private JsonNode post(String path, Object body) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(server.resolve(path))
                    .header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return JSON.readTree(response.body());
        }

        private String cookie(String name) {
            return cookies.getCookieStore().get(server).stream()
                    .filter(c -> c.getName().equals(name))
                    .map(HttpCookie::getValue)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no cookie " + name));
        }
    }
}
