package com.carddemo;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One admin journey over real HTTP, holding cookies and the CSRF header the way the browser does. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpJourneyIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedUsers() {
        jdbc.update("delete from users");
        jdbc.update("insert into users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type, sec_usr_filler)"
                        + " values (?, ?, ?, ?, ?, ''), (?, ?, ?, ?, ?, '')",
                "ADMIN001", "MARGARET", "GOLD", "PASSWORD", "A",
                "USER0002", "AJITH", "KUMAR", "PASSWORD", "U");
    }

    @Test
    void anAdminSignsOnAndRenamesAUser() throws Exception {
        Browser browser = new Browser(URI.create("http://127.0.0.1:" + port));
        assertEquals(404, browser.get("/api/session").statusCode());
        assertEquals(403, browser.post("/api/session", Map.of(), false).statusCode(), "a POST without X-XSRF-TOKEN");
        assertEquals(401, browser.post("/api/screens/user-update/fetch", Map.of("turn", 0), true).statusCode(),
                "an admin screen before sign-on");

        JsonNode signon = browser.start("CC00");
        assertEquals("screen", signon.path("kind").asText());
        assertEquals("COSGN0A", signon.at("/map/name").asText());
        assertEquals("USERID", signon.path("cursor").asText());
        String anonymousSession = browser.cookie("JSESSIONID");

        JsonNode menu = browser.press(signon, "sign-on", Map.of("USERID", "ADMIN001", "PASSWD", "PASSWORD"));
        assertEquals("admin-menu", menu.path("screen").asText());
        assertNotEquals(anonymousSession, browser.cookie("JSESSIONID"), "sign-on changes the session id");

        JsonNode update = browser.press(menu, "select", Map.of("OPTION", "3"));
        assertEquals("user-update", update.path("screen").asText());
        JsonNode fetched = browser.press(update, "fetch", Map.of("USRIDIN", "USER0002"));
        assertEquals("AJITH", fetched.at("/fields/FNAME").asText());
        assertTrue(fetched.path("hiddenFields").toString().contains("\"PASSWD\""), fetched.toString());

        HttpResponse<String> stale = browser.post("/api/screens/user-update/fetch",
                Map.of("turn", update.path("turn").asLong(), "fields", Map.of()), true);
        assertEquals(409, stale.statusCode());
        assertEquals(fetched, JSON.readTree(stale.body()), "a stale turn gets the reply on screen");

        JsonNode saved = browser.press(fetched, "save", Map.of("LNAME", "RENAMED"));
        assertEquals("User USER0002 has been updated ...", saved.at("/fields/ERRMSG").asText());
        assertEquals("GREEN", saved.at("/colors/ERRMSG").asText());
        assertEquals("RENAMED", jdbc.queryForObject("select sec_usr_lname from users where sec_usr_id = 'USER0002'",
                String.class));

        JsonNode back = browser.press(saved, "save-and-exit", Map.of());
        assertEquals("admin-menu", back.path("screen").asText());
        assertEquals(back, JSON.readTree(browser.get("/api/session").body()));
    }

    private static final class Browser {
        private final URI server;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        Browser(URI server) {
            this.server = server;
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(server.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
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
