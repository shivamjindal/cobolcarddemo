package com.carddemo.billpay;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Array;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bill pay and report submit over real HTTP. Expected replies and rows come from COBIL00C and CORPT00C,
 * with the clock fixed at the characterization instant so the timestamps and month range are determined.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillPayReportIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 10, 0);
    private static final String TIMESTAMP = "2026-11-05 10:00:00.000000";
    private static final String CARD = "9680294154603697";
    private static final String PREVIOUS = "0000000000000005";
    private static final String NEXT = "0000000000000006";

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

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("delete from job_submissions");
        jdbc.update("delete from transactions");
        jdbc.update("delete from transaction_category_balances");
        jdbc.update("delete from card_xref");
        jdbc.update("delete from cards");
        jdbc.update("delete from accounts");
        jdbc.update("delete from customers");
        jdbc.update("delete from users");
        jdbc.update("insert into users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type, sec_usr_filler)"
                + " values ('USER0001', 'JANE', 'DOE', 'PASSWORD', 'U', '')");
        jdbc.update("insert into customers (cust_id, cust_first_name, cust_middle_name, cust_last_name,"
                + " cust_addr_line_1, cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd, cust_addr_country_cd,"
                + " cust_addr_zip, cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id,"
                + " cust_dob_yyyy_mm_dd, cust_eft_account_id, cust_pri_card_holder_ind, cust_fico_credit_score)"
                + " values (1, 'A', '', 'B', '1 MAIN', '', '', 'NY', 'USA', '10001', '555', '', 1, 'X',"
                + " '1980-01-01', '1', 'Y', 700)");
        account(1, "194.00");
        account(2, "15.50");
        account(3, "1000000000.50");
        account(4, "0.00");
        account(5, "-25.00");
        jdbc.update("insert into cards (card_num, card_acct_id, card_cvv_cd, card_embossed_name, card_expiraion_date,"
                + " card_active_status) values (?, 1, 123, 'JANE DOE', '2028-01-01', 'Y')", CARD);
        jdbc.update("insert into card_xref (xref_card_num, xref_cust_id, xref_acct_id) values (?, 1, 1)", CARD);
        jdbc.update("insert into transactions (tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt,"
                + " tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip, tran_card_num,"
                + " tran_orig_ts, tran_proc_ts) values (?, '01', 1, 'POS TERM', 'seed', 1.00, 1, 'SHOP', 'CITY',"
                + " '00000', ?, '2020-01-01', '2020-01-01')", PREVIOUS, CARD);
    }

    @Test
    void payingTheShownBalanceWritesTheNextTransactionAndZeroesTheAccount() throws Exception {
        Browser browser = signedOn();
        JsonNode asked = browser.press(openBillPay(browser), "pay", Map.of("ACTIDIN", "00000000001"));
        assertEquals("+0000000194.00", asked.at("/fields/CURBAL").asText());
        assertEquals("Confirm to make a bill payment...", asked.at("/fields/ERRMSG").asText());
        assertEquals("CONFIRM", asked.path("cursor").asText());

        JsonNode paid = browser.press(asked, "pay", Map.of("CONFIRM", "Y"));
        assertEquals("Payment successful.  Your Transaction ID is " + NEXT + ".", paid.at("/fields/ERRMSG").asText());
        assertEquals("GREEN", paid.at("/colors/ERRMSG").asText());
        assertEquals("ACTIDIN", paid.path("cursor").asText());
        assertTrue(paid.at("/fields/ACTIDIN").isMissingNode());
        assertMoney("0.00", balance(1));

        Map<String, Object> row = jdbc.queryForMap("select * from transactions where tran_id = ?", NEXT);
        assertEquals("02", row.get("tran_type_cd"));
        assertEquals(2, ((Number) row.get("tran_cat_cd")).intValue());
        assertEquals("POS TERM", row.get("tran_source"));
        assertEquals("BILL PAYMENT - ONLINE", row.get("tran_desc"));
        assertMoney("194.00", (BigDecimal) row.get("tran_amt"));
        assertEquals(999999999, ((Number) row.get("tran_merchant_id")).intValue());
        assertEquals("BILL PAYMENT", row.get("tran_merchant_name"));
        assertEquals("N/A", row.get("tran_merchant_city"));
        assertEquals("N/A", row.get("tran_merchant_zip"));
        assertEquals(CARD, row.get("tran_card_num"));
        assertEquals(TIMESTAMP, row.get("tran_orig_ts"));
        assertEquals(TIMESTAMP, row.get("tran_proc_ts"));

        JsonNode again = browser.press(paid, "pay", Map.of("ACTIDIN", "00000000001", "CONFIRM", "Y"));
        assertEquals("You have nothing to pay...", again.at("/fields/ERRMSG").asText());
        assertEquals("+0000000000.00", again.at("/fields/CURBAL").asText());
        assertEquals(2, tranCount());
    }

    @Test
    void anAccountWithNoCardStillPaysAndStoresALowValuesCard() throws Exception {
        Browser browser = signedOn();
        JsonNode paid = browser.press(openBillPay(browser), "pay", Map.of("ACTIDIN", "00000000002", "CONFIRM", "Y"));
        assertEquals("Payment successful.  Your Transaction ID is " + NEXT + ".", paid.at("/fields/ERRMSG").asText());
        assertEquals("GREEN", paid.at("/colors/ERRMSG").asText());
        assertMoney("0.00", balance(2));
        Map<String, Object> row = jdbc.queryForMap("select tran_card_num, tran_amt from transactions where tran_id = ?",
                NEXT);
        assertNull(row.get("tran_card_num"));
        assertMoney("15.50", (BigDecimal) row.get("tran_amt"));
    }

    @Test
    void aShortAccountIsNotFound() throws Exception {
        Browser browser = signedOn();
        JsonNode missed = browser.press(openBillPay(browser), "pay", Map.of("ACTIDIN", "1"));
        assertEquals("1", missed.at("/fields/ACTIDIN").asText());
        assertEquals("Account ID NOT found...", missed.at("/fields/ERRMSG").asText());
        assertEquals("ACTIDIN", missed.path("cursor").asText());
        assertMoney("194.00", balance(1));
        assertEquals(1, tranCount());
    }

    @Test
    void aBalancePastNineDigitsPaysOnlyTheLowOrderPart() throws Exception {
        Browser browser = signedOn();
        JsonNode paid = browser.press(openBillPay(browser), "pay", Map.of("ACTIDIN", "00000000003", "CONFIRM", "Y"));
        assertEquals("Payment successful.  Your Transaction ID is " + NEXT + ".", paid.at("/fields/ERRMSG").asText());
        assertMoney("1000000000.00", balance(3));
        assertMoney("0.50", jdbc.queryForObject("select tran_amt from transactions where tran_id = ?", BigDecimal.class,
                NEXT));
    }

    @Test
    void aZeroOrCreditBalanceIsShownAndNotPaid() throws Exception {
        Browser browser = signedOn();
        JsonNode bill = openBillPay(browser);
        JsonNode zero = browser.press(bill, "pay", Map.of("ACTIDIN", "00000000004"));
        assertEquals("+0000000000.00", zero.at("/fields/CURBAL").asText());
        assertEquals("You have nothing to pay...", zero.at("/fields/ERRMSG").asText());
        JsonNode credit = browser.press(zero, "pay", Map.of("ACTIDIN", "00000000005"));
        assertEquals("-0000000025.00", credit.at("/fields/CURBAL").asText());
        assertEquals("You have nothing to pay...", credit.at("/fields/ERRMSG").asText());
        assertEquals(1, tranCount());
    }

    @Test
    void confirmNClearsTheScreenAndWritesNothing() throws Exception {
        Browser browser = signedOn();
        JsonNode bill = openBillPay(browser);
        JsonNode asked = browser.press(bill, "pay", Map.of("ACTIDIN", "00000000001"));
        JsonNode cleared = browser.press(asked, "pay", Map.of("CONFIRM", "N"));
        assertTrue(cleared.at("/fields/ERRMSG").isMissingNode());
        assertTrue(cleared.at("/fields/ACTIDIN").isMissingNode());
        assertEquals("ACTIDIN", cleared.path("cursor").asText());
        assertMoney("194.00", balance(1));
    }

    @Test
    void monthlyAndYearlyJobsUseTheClockAndLowercaseConfirm() throws Exception {
        Browser browser = signedOn();
        JsonNode monthly = browser.press(openReports(browser), "submit", Map.of("MONTHLY", "X", "CONFIRM", "y"));
        assertEquals("Monthly report submitted for printing ...", monthly.at("/fields/ERRMSG").asText());
        assertEquals("GREEN", monthly.at("/colors/ERRMSG").asText());
        assertEquals("MONTHLY", monthly.path("cursor").asText());
        assertJob(1, "2026-11-01", "2026-11-30");

        JsonNode menu = browser.press(monthly, "back", Map.of());
        JsonNode yearly = browser.press(browser.press(menu, "select", Map.of("OPTION", "9")), "submit",
                Map.of("YEARLY", "x", "CONFIRM", "Y"));
        assertEquals("Yearly report submitted for printing ...", yearly.at("/fields/ERRMSG").asText());
        assertJob(2, "2026-01-01", "2026-12-31");
    }

    @Test
    void oneDigitCustomDatesArePaddedAndReversedOrPre1582RangesAreSubmitted() throws Exception {
        Browser browser = signedOn();
        JsonNode reports = openReports(browser);
        JsonNode asked = browser.press(reports, "submit", Map.of("CUSTOM", "X", "SDTMM", "1", "SDTDD", "5",
                "SDTYYYY", "2026", "EDTMM", "2", "EDTDD", "9", "EDTYYYY", "2026"));
        assertEquals("01", asked.at("/fields/SDTMM").asText());
        assertEquals("05", asked.at("/fields/SDTDD").asText());
        assertEquals("02", asked.at("/fields/EDTMM").asText());
        assertEquals("09", asked.at("/fields/EDTDD").asText());
        assertEquals("Please confirm to print the Custom report...", asked.at("/fields/ERRMSG").asText());
        assertEquals("CONFIRM", asked.path("cursor").asText());
        assertEquals(0, jobCount());

        JsonNode submitted = browser.press(asked, "submit", Map.of("CONFIRM", "Y"));
        assertEquals("Custom report submitted for printing ...", submitted.at("/fields/ERRMSG").asText());
        assertJob(1, "2026-01-05", "2026-02-09");

        JsonNode menu = browser.press(submitted, "back", Map.of());
        JsonNode reversed = browser.press(browser.press(menu, "select", Map.of("OPTION", "9")), "submit",
                Map.of("CUSTOM", "X", "SDTMM", "12", "SDTDD", "31", "SDTYYYY", "2026", "EDTMM", "01", "EDTDD", "01",
                        "EDTYYYY", "2026", "CONFIRM", "Y"));
        assertEquals("Custom report submitted for printing ...", reversed.at("/fields/ERRMSG").asText());
        assertJob(2, "2026-12-31", "2026-01-01");

        menu = browser.press(reversed, "back", Map.of());
        JsonNode ancient = browser.press(browser.press(menu, "select", Map.of("OPTION", "9")), "submit",
                Map.of("CUSTOM", "X", "SDTMM", "01", "SDTDD", "01", "SDTYYYY", "1500", "EDTMM", "01", "EDTDD", "31",
                        "EDTYYYY", "2026", "CONFIRM", "Y"));
        assertEquals("Custom report submitted for printing ...", ancient.at("/fields/ERRMSG").asText());
        assertJob(3, "1500-01-01", "2026-01-31");
    }

    @Test
    void lettersInACustomDatePartBecomeZero() throws Exception {
        Browser browser = signedOn();
        JsonNode month = browser.press(openReports(browser), "submit", Map.of("CUSTOM", "X", "SDTMM", "AB",
                "SDTDD", "01", "SDTYYYY", "2026", "EDTMM", "11", "EDTDD", "30", "EDTYYYY", "2026", "CONFIRM", "Y"));
        assertEquals("00", month.at("/fields/SDTMM").asText());
        assertEquals("Start Date - Not a valid date...", month.at("/fields/ERRMSG").asText());
        assertEquals("SDTMM", month.path("cursor").asText());
        assertEquals(0, jobCount());

        JsonNode menu = browser.press(month, "back", Map.of());
        JsonNode year = browser.press(browser.press(menu, "select", Map.of("OPTION", "9")), "submit",
                Map.of("CUSTOM", "X", "SDTMM", "01", "SDTDD", "01", "SDTYYYY", "20X6", "EDTMM", "11", "EDTDD", "30",
                        "EDTYYYY", "2026", "CONFIRM", "Y"));
        assertEquals("Custom report submitted for printing ...", year.at("/fields/ERRMSG").asText());
        assertJob(1, "0000-01-01", "2026-11-30");
    }

    @Test
    void confirmNSubmitsNothing() throws Exception {
        Browser browser = signedOn();
        JsonNode cleared = browser.press(openReports(browser), "submit", Map.of("MONTHLY", "X", "CONFIRM", "n"));
        assertTrue(cleared.at("/fields/ERRMSG").isMissingNode());
        assertTrue(cleared.at("/fields/MONTHLY").isMissingNode());
        assertEquals("MONTHLY", cleared.path("cursor").asText());
        assertEquals(0, jobCount());
    }

    private void account(long id, String balance) {
        jdbc.update("insert into accounts (acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,"
                + " acct_cash_credit_limit, acct_open_date, acct_expiraion_date, acct_reissue_date,"
                + " acct_curr_cyc_credit, acct_curr_cyc_debit, acct_addr_zip, acct_group_id)"
                + " values (?, 'Y', ?, 5000, 1000, '2020-01-01', '2028-01-01', '2024-01-01', 0, 0, '10001', 'GRP')",
                id, new BigDecimal(balance));
    }

    private BigDecimal balance(long id) {
        return jdbc.queryForObject("select acct_curr_bal from accounts where acct_id = ?", BigDecimal.class, id);
    }

    private int tranCount() {
        return jdbc.queryForObject("select count(*) from transactions", Integer.class);
    }

    private int jobCount() {
        return jdbc.queryForObject("select count(*) from job_submissions", Integer.class);
    }

    private void assertJob(int count, String start, String end) throws Exception {
        assertEquals(count, jobCount());
        Map<String, Object> row = jdbc.queryForMap(
                "select * from job_submissions order by id desc limit 1");
        assertEquals("JOBS", row.get("queue"));
        assertEquals("TRNRPT00", row.get("job_name"));
        assertEquals("TRANREPT", row.get("procedure"));
        assertEquals(start, row.get("start_date"));
        assertEquals(end, row.get("end_date"));
        assertEquals("USER0001", row.get("submitted_by"));
        assertEquals(NOW, ((java.sql.Timestamp) row.get("submitted_at")).toLocalDateTime());
        List<String> jcl = Arrays.asList((String[]) ((Array) row.get("jcl")).getArray());
        assertEquals(List.of("//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,", "// NOTIFY=&SYSUID", "//*",
                "//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')", "//*", "//STEP10 EXEC PROC=TRANREPT", "//*",
                "//STEP05R.SYMNAMES DD *", "TRAN-CARD-NUM,263,16,ZD", "TRAN-PROC-DT,305,10,CH",
                "PARM-START-DATE,C'" + start + "'", "PARM-END-DATE,C'" + end + "'", "/*", "//STEP10R.DATEPARM DD *",
                start + " " + end, "/*", "/*EOF"), jcl);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), actual.toPlainString());
    }

    private Browser signedOn() throws Exception {
        Browser browser = new Browser(URI.create("http://127.0.0.1:" + port));
        browser.get("/api/session");
        JsonNode signon = browser.start("CC00");
        JsonNode menu = browser.press(signon, "sign-on", Map.of("USERID", "USER0001", "PASSWD", "PASSWORD"));
        assertEquals("main-menu", menu.path("screen").asText());
        browser.menu = menu;
        return browser;
    }

    private static JsonNode openBillPay(Browser browser) throws Exception {
        JsonNode bill = browser.press(browser.menu, "select", Map.of("OPTION", "10"));
        assertEquals("bill-pay", bill.path("screen").asText());
        assertEquals("ACTIDIN", bill.path("cursor").asText());
        return bill;
    }

    private static JsonNode openReports(Browser browser) throws Exception {
        JsonNode reports = browser.press(browser.menu, "select", Map.of("OPTION", "9"));
        assertEquals("report-submit", reports.path("screen").asText());
        assertEquals("MONTHLY", reports.path("cursor").asText());
        return reports;
    }

    private static final class Browser {
        private final URI server;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        private JsonNode menu;

        Browser(URI server) {
            this.server = server;
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(server.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        JsonNode start(String transid) throws Exception {
            return ok(post("/api/session", Map.of("transid", transid)));
        }

        JsonNode press(JsonNode on, String action, Map<String, String> fields) throws Exception {
            return ok(post("/api/screens/" + on.path("screen").asText() + "/" + action,
                    Map.of("turn", on.path("turn").asLong(), "fields", fields)));
        }

        private HttpResponse<String> post(String path, Object body) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(server.resolve(path))
                    .header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }

        private String cookie(String name) {
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
