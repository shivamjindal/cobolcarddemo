package com.carddemo.trantype;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.cics.CicsRegion;
import com.carddemo.cics.Extension;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COTRTUPC's SQL failure branches, which the goldens cannot reach: a trigger raises the SQLSTATE that Db2 maps to
 * the SQLCODE under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionTypeMaintenanceIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOCK_TIMEOUT = "55P03";
    private static final String DUPLICATE_KEY = "23505";

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

    @BeforeEach
    void seed() {
        region.install(Set.of(Extension.TRANSACTION_TYPE_DB2));
        dropTrigger();
        jdbc.update("delete from carddemo.transaction_type where tr_type in ('91', '92')");
        jdbc.update("insert into carddemo.transaction_type (tr_type, tr_description) values ('91', 'SEEDED')");
        jdbc.update("delete from users where sec_usr_id = 'ADMIN001'");
        jdbc.update("insert into users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type, sec_usr_filler)"
                + " values ('ADMIN001', 'ADA', 'MIN', 'PASSWORD', 'A', '')");
        jdbc.execute("create or replace function carddemo.forced_failure() returns trigger language plpgsql as"
                + " $$ begin raise exception 'forced' using errcode = TG_ARGV[0]; end $$");
    }

    @AfterEach
    void dropTrigger() {
        jdbc.execute("drop trigger if exists forced_failure on carddemo.transaction_type");
        jdbc.execute("drop function if exists carddemo.forced_failure()");
        jdbc.update("delete from carddemo.transaction_type where tr_type in ('91', '92')");
    }

    @Test
    void aLockedRowFailsTheSaveAndTheNextEnterStartsOver() throws Exception {
        Browser browser = new Browser();
        JsonNode validated = validatedChange(browser, "91", "RENAMED");
        failOn("update", LOCK_TIMEOUT);

        JsonNode failed = browser.press(validated, "save", Map.of());

        assertEquals("Could not lock record for update", failed.at("/fields/ERRMSG").asText());
        assertEquals("Changes unsuccessful", failed.at("/fields/INFOMSG").asText().strip());
        assertEquals("RENAMED", failed.at("/fields/TRTYDSC").asText());
        assertFalse(protectedFields(failed).contains("TRTYPCD"), "WHEN OTHER of 3300 leaves the code open");
        assertEquals("SEEDED", description("91"));

        JsonNode restarted = browser.press(failed, "process", Map.of());
        assertEquals("Enter transaction type to be maintained", restarted.at("/fields/INFOMSG").asText());
        assertFalse(restarted.at("/fields/TRTYPCD").isTextual());
    }

    @Test
    void aFailedUpdateIsReportedAsCommitted() throws Exception {
        Browser browser = new Browser();
        JsonNode validated = validatedChange(browser, "91", "RENAMED");
        failOn("update", DUPLICATE_KEY);

        JsonNode saved = browser.press(validated, "save", Map.of());

        assertTrue(saved.at("/fields/ERRMSG").asText().startsWith("Error updating: TRANSACTION_TYPE Table. SQLCODE: -803:"),
                saved.at("/fields/ERRMSG").asText());
        assertEquals("Changes committed to database", saved.at("/fields/INFOMSG").asText().strip());
        assertEquals("SEEDED", description("91"));
    }

    @Test
    void aFailedInsertShowsTheUpdatesSqlcode() throws Exception {
        Browser browser = new Browser();
        JsonNode notFound = browser.press(adminScreen(browser), "process", Map.of("TRTYPCD", "92"));
        JsonNode create = browser.press(notFound, "save", Map.of());
        JsonNode validated = browser.press(create, "process", Map.of("TRTYDSC", "ADDED"));
        assertEquals("Changes validated.Press F5 to save", validated.at("/fields/INFOMSG").asText().strip());
        failOn("insert", DUPLICATE_KEY);

        JsonNode saved = browser.press(validated, "save", Map.of());

        assertTrue(saved.at("/fields/ERRMSG").asText()
                .startsWith("Error inserting record into: TRANSACTION_TYPE Table. SQLCODE:  100:"),
                saved.at("/fields/ERRMSG").asText());
        assertEquals("Changes committed to database", saved.at("/fields/INFOMSG").asText().strip());
        assertEquals(0, jdbc.queryForObject("select count(*) from carddemo.transaction_type where tr_type = '92'",
                Integer.class));
    }

    @Test
    void aDeleteFailureOtherThanChildRowsEndsTheDelete() throws Exception {
        Browser browser = new Browser();
        JsonNode shown = browser.press(adminScreen(browser), "process", Map.of("TRTYPCD", "91"));
        JsonNode confirm = browser.press(shown, "delete", Map.of());
        assertEquals("Delete this record ? Press F4 to confirm", confirm.at("/fields/INFOMSG").asText());
        failOn("delete", LOCK_TIMEOUT);

        JsonNode failed = browser.press(confirm, "delete", Map.of());

        assertTrue(failed.at("/fields/ERRMSG").asText().startsWith("Delete failed with message:SQLCODE : -911:"),
                failed.at("/fields/ERRMSG").asText());
        assertEquals("Changes unsuccessful", failed.at("/fields/INFOMSG").asText().strip());
        assertEquals("RED", failed.at("/colors/TRTYPCD").asText());
        assertEquals("91", failed.at("/fields/TRTYPCD").asText());
        assertEquals("SEEDED", description("91"));

        JsonNode restarted = browser.press(failed, "process", Map.of());
        assertEquals("Enter transaction type to be maintained", restarted.at("/fields/INFOMSG").asText());
    }

    private JsonNode adminScreen(Browser browser) throws Exception {
        JsonNode signon = browser.start("CC00");
        JsonNode menu = browser.press(signon, "sign-on", Map.of("USERID", "ADMIN001", "PASSWD", "PASSWORD"));
        JsonNode screen = browser.press(menu, "select", Map.of("OPTION", "6"));
        assertEquals("tran-type-maint", screen.path("screen").asText());
        return screen;
    }

    private JsonNode validatedChange(Browser browser, String type, String description) throws Exception {
        JsonNode shown = browser.press(adminScreen(browser), "process", Map.of("TRTYPCD", type));
        JsonNode validated = browser.press(shown, "process", Map.of("TRTYDSC", description));
        assertEquals("Changes validated.Press F5 to save", validated.at("/fields/INFOMSG").asText().strip());
        return validated;
    }

    private void failOn(String event, String sqlstate) {
        jdbc.execute("create trigger forced_failure before " + event + " on carddemo.transaction_type"
                + " for each row execute function carddemo.forced_failure('" + sqlstate + "')");
    }

    private String description(String type) {
        return jdbc.queryForObject("select tr_description from carddemo.transaction_type where tr_type = ?",
                String.class, type).stripTrailing();
    }

    private static List<String> protectedFields(JsonNode reply) {
        return JSON.convertValue(reply.path("protectedFields"), JSON.getTypeFactory().constructCollectionType(List.class,
                String.class));
    }

    private final class Browser {
        private final URI server = URI.create("http://127.0.0.1:" + port);
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

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
