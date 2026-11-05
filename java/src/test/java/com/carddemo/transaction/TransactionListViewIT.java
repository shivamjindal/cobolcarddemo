package com.carddemo.transaction;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * List and view over real HTTP. Expected fields come from the COBOL pictures and the seeded rows,
 * and neither screen writes TRANSACT or the account balance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionListViewIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CARD = "0927987108636232";
    private static final String ORIG = "2022-06-10 19:27:53.000000";
    private static final String FIRST = "0000000000000001";
    private static final String FULL_DESC = "Return item at Nitzsche, Nicolas and Lowe";

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
        jdbc.update("delete from accounts");
        jdbc.update("delete from customers");
        jdbc.update("delete from users");
        jdbc.update("insert into users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type, sec_usr_filler)"
                + " values ('USER0001', 'JANE', 'USER', 'PASSWORD', 'U', '')");
        jdbc.update("insert into customers (cust_id, cust_first_name, cust_middle_name, cust_last_name,"
                + " cust_addr_line_1, cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd, cust_addr_country_cd,"
                + " cust_addr_zip, cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id,"
                + " cust_dob_yyyy_mm_dd, cust_eft_account_id, cust_pri_card_holder_ind, cust_fico_credit_score)"
                + " values (1, 'JANE', '', 'USER', '1 MAIN', '', '', 'TX', 'USA', '00000', '1', '', 1, '1',"
                + " '1980-01-01', '1', 'Y', 700)");
        jdbc.update("insert into accounts (acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,"
                + " acct_cash_credit_limit, acct_open_date, acct_expiraion_date, acct_reissue_date,"
                + " acct_curr_cyc_credit, acct_curr_cyc_debit, acct_addr_zip, acct_group_id)"
                + " values (1, 'Y', 100.00, 1000.00, 200.00, '2020-01-01', '2028-01-01', '2024-01-01', 0, 0,"
                + " '00000', 'GRP')");
        jdbc.update("insert into cards (card_num, card_acct_id, card_cvv_cd, card_embossed_name,"
                + " card_expiraion_date, card_active_status) values (?, 1, 123, 'JANE USER', '2028-01-01', 'Y')", CARD);
        jdbc.update("insert into card_xref (xref_card_num, xref_cust_id, xref_acct_id) values (?, 1, 1)", CARD);
        jdbc.update("insert into transactions (tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt,"
                + " tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip, tran_card_num,"
                + " tran_orig_ts, tran_proc_ts) values (?, '01', 1, 'POS TERM', ?, -919.00, 800000000,"
                + " 'Nitzsche, Nicolas and Lowe', 'Fidelshire', '53378', ?, ?, '')",
                FIRST, FULL_DESC, CARD, ORIG);
        for (int n = 2; n <= 12; n++) {
            jdbc.update("insert into transactions (tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc,"
                    + " tran_amt, tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip,"
                    + " tran_card_num, tran_orig_ts, tran_proc_ts) values (?, '01', 1, 'POS TERM', ?, ?, 1, 'M', 'C',"
                    + " '1', ?, ?, '')",
                    "%016d".formatted(n), "Purchase " + n, BigDecimal.valueOf(n), CARD, ORIG);
        }
    }

    @Test
    void theFirstPageShowsTenRowsAndWritesNothing() throws Exception {
        Browser browser = signedOn();
        JsonNode list = openList(browser);
        assertEquals("COTRN0A", list.at("/map/name").asText());
        assertEquals("00000001", list.at("/fields/PAGENUM").asText());
        assertEquals(FIRST, list.at("/fields/TRNID01").asText());
        assertEquals("06/10/22", list.at("/fields/TDATE01").asText());
        assertEquals("Return item at Nitzsche, N", list.at("/fields/TDESC01").asText());
        assertEquals("-00000919.00", list.at("/fields/TAMT001").asText());
        assertEquals("0000000000000010", list.at("/fields/TRNID10").asText());
        assertEquals("+00000010.00", list.at("/fields/TAMT010").asText());
        assertEquals("", list.at("/fields/ERRMSG").asText());
        assertUnchanged();
    }

    @Test
    void pagingForwardThenBackLeavesTheTopMessage() throws Exception {
        Browser browser = signedOn();
        JsonNode list = openList(browser);
        JsonNode page2 = browser.press(list, "forward", Map.of());
        assertEquals("00000002", page2.at("/fields/PAGENUM").asText());
        assertEquals("0000000000000011", page2.at("/fields/TRNID01").asText());
        assertEquals("0000000000000012", page2.at("/fields/TRNID02").asText());
        assertEquals("", page2.at("/fields/TRNID03").asText());
        assertEquals("You have reached the bottom of the page...", page2.at("/fields/ERRMSG").asText());

        JsonNode back = browser.press(page2, "backward", Map.of());
        assertEquals("00000001", back.at("/fields/PAGENUM").asText());
        assertEquals(FIRST, back.at("/fields/TRNID01").asText());
        assertEquals("0000000000000010", back.at("/fields/TRNID10").asText());
        assertEquals("You have reached the top of the page...", back.at("/fields/ERRMSG").asText());
    }

    @Test
    void aFilterStartsAtThatKeyAndAShortIdIsRejected() throws Exception {
        Browser browser = signedOn();
        JsonNode list = openList(browser);
        JsonNode rejected = browser.press(list, "continue", Map.of("TRNIDIN", "ABC"));
        assertEquals("ABC", rejected.at("/fields/TRNIDIN").asText());
        assertEquals(FIRST, rejected.at("/fields/TRNID01").asText());
        assertEquals("00000001", rejected.at("/fields/PAGENUM").asText());
        assertEquals("Tran ID must be Numeric ...", rejected.at("/fields/ERRMSG").asText());

        JsonNode filtered = browser.press(rejected, "continue", Map.of("TRNIDIN", "0000000000000011"));
        assertEquals("00000001", filtered.at("/fields/PAGENUM").asText());
        assertEquals("0000000000000011", filtered.at("/fields/TRNID01").asText());
        assertEquals("", filtered.at("/fields/TRNIDIN").asText());
        assertEquals("You have reached the bottom of the page...", filtered.at("/fields/ERRMSG").asText());
    }

    @Test
    void selectingARowOpensTheViewAndPf3ReturnsToTheList() throws Exception {
        Browser browser = signedOn();
        JsonNode list = openList(browser);
        JsonNode view = browser.press(list, "continue", Map.of("SEL0001", "S"));
        assertEquals("transaction-view", view.path("screen").asText());
        assertEquals(FIRST, view.at("/fields/TRNIDIN").asText());
        assertEquals(FIRST, view.at("/fields/TRNID").asText());
        assertEquals(CARD, view.at("/fields/CARDNUM").asText());
        assertEquals("01", view.at("/fields/TTYPCD").asText());
        assertEquals("0001", view.at("/fields/TCATCD").asText());
        assertEquals("POS TERM", view.at("/fields/TRNSRC").asText());
        assertEquals(FULL_DESC, view.at("/fields/TDESC").asText());
        assertEquals("-00000919.00", view.at("/fields/TRNAMT").asText());
        assertEquals("2022-06-10", view.at("/fields/TORIGDT").asText());
        assertFalse(view.path("fields").has("TPROCDT"));
        assertEquals("800000000", view.at("/fields/MID").asText());
        assertEquals("Nitzsche, Nicolas and Lowe", view.at("/fields/MNAME").asText());
        assertEquals("Fidelshire", view.at("/fields/MCITY").asText());
        assertEquals("53378", view.at("/fields/MZIP").asText());

        JsonNode returned = browser.press(view, "back", Map.of());
        assertEquals("transaction-list", returned.path("screen").asText());
        assertEquals("00000001", returned.at("/fields/PAGENUM").asText());
        assertEquals(FIRST, returned.at("/fields/TRNID01").asText());
        assertUnchanged();
    }

    @Test
    void aShortTranIdIsNotPaddedAndIsNotFound() throws Exception {
        Browser browser = signedOn();
        JsonNode menu = menu(browser);
        JsonNode view = browser.press(menu, "select", Map.of("OPTION", "7"));
        JsonNode missing = browser.press(view, "fetch", Map.of("TRNIDIN", "1"));
        assertEquals("1", missing.at("/fields/TRNIDIN").asText());
        assertEquals("Transaction ID NOT found...", missing.at("/fields/ERRMSG").asText());
        assertFalse(missing.path("fields").has("TRNID"));
        assertUnchanged();
    }

    @Test
    void anInvalidSelectionResetsToPageOneAndKeepsTheCode() throws Exception {
        Browser browser = signedOn();
        JsonNode page2 = browser.press(openList(browser), "forward", Map.of());
        JsonNode reset = browser.press(page2, "continue", Map.of("SEL0002", "X"));
        assertEquals("00000001", reset.at("/fields/PAGENUM").asText());
        assertEquals(FIRST, reset.at("/fields/TRNID01").asText());
        assertEquals("0000000000000002", reset.at("/fields/TRNID02").asText());
        assertEquals("X", reset.at("/fields/SEL0002").asText());
        assertEquals("Invalid selection. Valid value is S", reset.at("/fields/ERRMSG").asText());
    }

    @Test
    void aTranIdPastEveryKeyAbends() throws Exception {
        Browser browser = signedOn();
        JsonNode list = openList(browser);
        HttpResponse<String> response = browser.post("/api/screens/transaction-list/continue",
                Map.of("turn", list.path("turn").asLong(), "fields", Map.of("TRNIDIN", "9999999999999999")), true);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode ended = JSON.readTree(response.body());
        assertEquals("ended", ended.path("kind").asText());
        assertTrue(ended.path("text").asText().contains("abend AEIP"), ended.toString());
        assertEquals(12, countTransactions());
    }

    @Test
    void pf5FromTheViewSkipsTheFirstTransaction() throws Exception {
        Browser browser = signedOn();
        JsonNode menu = menu(browser);
        JsonNode view = browser.press(menu, "select", Map.of("OPTION", "7"));
        JsonNode list = browser.press(view, "browse", Map.of());
        assertEquals("transaction-list", list.path("screen").asText());
        assertEquals("00000001", list.at("/fields/PAGENUM").asText());
        assertEquals("0000000000000002", list.at("/fields/TRNID01").asText());
        assertEquals("0000000000000011", list.at("/fields/TRNID10").asText());
    }

    private void assertUnchanged() {
        assertEquals(12, countTransactions());
        assertEquals(0, new BigDecimal("100.00").compareTo(
                jdbc.queryForObject("select acct_curr_bal from accounts where acct_id = 1", BigDecimal.class)));
    }

    private int countTransactions() {
        return jdbc.queryForObject("select count(*) from transactions", Integer.class);
    }

    private JsonNode openList(Browser browser) throws Exception {
        JsonNode list = browser.press(menu(browser), "select", Map.of("OPTION", "6"));
        assertEquals("transaction-list", list.path("screen").asText());
        return list;
    }

    private JsonNode menu(Browser browser) throws Exception {
        JsonNode signon = browser.start("CC00");
        JsonNode menu = browser.press(signon, "sign-on", Map.of("USERID", "USER0001", "PASSWD", "PASSWORD"));
        assertEquals("main-menu", menu.path("screen").asText());
        return menu;
    }

    private Browser signedOn() {
        return new Browser(URI.create("http://127.0.0.1:" + port));
    }

    private static final class Browser {
        private final URI server;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        Browser(URI server) {
            this.server = server;
            try {
                http.send(HttpRequest.newBuilder(server.resolve("/api/session")).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        HttpResponse<String> post(String path, Object body, boolean withCsrf) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(server.resolve(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            if (withCsrf) {
                request.header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"));
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        JsonNode start(String transid) throws Exception {
            return ok(post("/api/session", Map.of("transid", transid), true));
        }

        JsonNode press(JsonNode on, String action, Map<String, String> fields) throws Exception {
            return ok(post("/api/screens/" + on.path("screen").asText() + "/" + action,
                    Map.of("turn", on.path("turn").asLong(), "fields", fields), true));
        }

        String cookie(String name) {
            return cookies.getCookieStore().get(server).stream()
                    .filter(c -> c.getName().equals(name))
                    .map(HttpCookie::getValue)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no cookie " + name));
        }

        private static JsonNode ok(HttpResponse<String> response) throws Exception {
            assertEquals(200, response.statusCode(), response.body());
            return JSON.readTree(response.body());
        }
    }
}
