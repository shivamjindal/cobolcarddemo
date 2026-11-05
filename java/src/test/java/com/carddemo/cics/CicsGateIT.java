package com.carddemo.cics;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.carddemo.TestDatabase;
import com.carddemo.accounts.AccountRecord;
import com.carddemo.accounts.AccountStore;
import com.carddemo.accounts.CustomerStore;
import com.carddemo.accounts.Read;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CEMT SET FILE against the online screens over real HTTP. Expected messages come from the COBOL: a closed file
 * answers RESP NOTOPEN (19), which each program sends down its WHEN OTHER branch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CicsGateIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CARD = "0927987108636232";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CicsRegion region;

    @Autowired
    private AccountStore accounts;

    @Autowired
    private CustomerStore customers;

    @Autowired
    private TransactionTemplate tasks;

    @BeforeEach
    void seed() {
        openAll();
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
    }

    @AfterEach
    void openAll() {
        jdbc.update("update cics_region set closed = '{}'");
    }

    @Test
    void aClosedAcctdatIsAFileErrorOnTheAccountViewUntilItIsOpenedAgain() throws Exception {
        Browser browser = new Browser(URI.create("http://127.0.0.1:" + port));
        JsonNode view = browser.press(signOn(browser), "select", Map.of("OPTION", "1"));
        assertEquals("account-view", view.path("screen").asText());

        region.close(CicsFile.ACCTDAT);
        JsonNode closed = browser.press(view, "lookup", Map.of("ACCTSID", "00000000001"));
        assertEquals("File Error: READ     on ACCTDAT   returned RESP 000000019 ,RESP2 000000000",
                closed.at("/fields/ERRMSG").asText().stripTrailing());
        assertEquals("", closed.at("/fields/ACURBAL").asText(), "no account record was read");
        assertEquals("JANE", closed.at("/fields/ACSFNAM").asText().strip(), "COACTVWC still reads CUSTDAT");

        region.open(CicsFile.ACCTDAT);
        JsonNode opened = browser.press(closed, "lookup", Map.of("ACCTSID", "00000000001"));
        assertEquals("", opened.at("/fields/ERRMSG").asText().strip());
        assertFalse(opened.at("/fields/ACURBAL").asText().isBlank(), opened.toString());
    }

    @Test
    void signOnWithUsrsecClosedIsUnableToVerifyTheUser() throws Exception {
        Browser browser = new Browser(URI.create("http://127.0.0.1:" + port));
        JsonNode signon = browser.start("CC00");

        region.close(CicsFile.USRSEC);
        JsonNode refused = browser.press(signon, "sign-on", Map.of("USERID", "USER0001", "PASSWD", "PASSWORD"));
        assertEquals("signon", refused.path("screen").asText());
        assertEquals("Unable to verify the User ...", refused.at("/fields/ERRMSG").asText().stripTrailing());
        assertEquals("USERID", refused.path("cursor").asText());

        region.open(CicsFile.USRSEC);
        assertEquals("main-menu", browser.press(refused, "sign-on",
                Map.of("USERID", "USER0001", "PASSWD", "PASSWORD")).path("screen").asText());
    }

    @Test
    void cemtCloseWaitsForTheTaskUsingTheFileToEnd() throws Exception {
        AtomicReference<CompletableFuture<Void>> closing = new AtomicReference<>();
        tasks.executeWithoutResult(task -> {
            assertInstanceOf(Read.Found.class, accounts.find(1));
            closing.set(CompletableFuture.runAsync(() -> region.close(CicsFile.ACCTDAT)));
            assertThrows(TimeoutException.class, () -> closing.get().get(500, TimeUnit.MILLISECONDS),
                    "CEMT closed ACCTDAT under a running task");
            assertInstanceOf(Read.Found.class, customers.find(1), "the running task keeps its files");
        });

        closing.get().get(10, TimeUnit.SECONDS);
        assertEquals(new Read.Failed<AccountRecord>(Read.NOTOPEN, 0),
                tasks.execute(task -> accounts.find(1)));
        assertEquals(Set.of(CicsFile.ACCTDAT), region.closed());
    }

    private static JsonNode signOn(Browser browser) throws Exception {
        JsonNode menu = browser.press(browser.start("CC00"), "sign-on",
                Map.of("USERID", "USER0001", "PASSWD", "PASSWORD"));
        assertEquals("main-menu", menu.path("screen").asText());
        return menu;
    }

    private static final class Browser {
        private final URI server;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        Browser(URI server) throws Exception {
            this.server = server;
            http.send(HttpRequest.newBuilder(server.resolve("/api/session")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
        }

        JsonNode start(String transid) throws Exception {
            return post("/api/session", Map.of("transid", transid));
        }

        JsonNode press(JsonNode on, String action, Map<String, String> fields) throws Exception {
            return post("/api/screens/" + on.path("screen").asText() + "/" + action,
                    Map.of("turn", on.path("turn").asLong(), "fields", fields));
        }

        private JsonNode post(String path, Object body) throws Exception {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(server.resolve(path))
                    .header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.ofString());
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
