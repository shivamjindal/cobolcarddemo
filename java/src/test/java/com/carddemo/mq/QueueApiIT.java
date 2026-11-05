package com.carddemo.mq;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.cics.CicsRegion;
import com.carddemo.cics.Extension;
import com.carddemo.cics.TransientData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An operator drives an authorization over HTTP: a request put on the PAUTH request queue starts CP00 through its
 * trigger definition, and the reply is browsed and then taken from the reply queue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QueueApiIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUEST = "/api/mq/queues/AWS.M2.CARDDEMO.PAUTH.REQUEST/messages";
    private static final String REPLY = "/api/mq/queues/AWS.M2.CARDDEMO.PAUTH.REPLY";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CicsRegion region;

    @Autowired
    private TransientData transientData;

    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.sql("DELETE FROM mq_messages WHERE queue LIKE 'AWS.M2.CARDDEMO.PAUTH.%'").update();
        // the characterization ITs seed this database, and a seed empties mq_queues
        jdbc.sql("INSERT INTO mq_queues (name, trigger_type, trigger_transid) VALUES"
                + " ('AWS.M2.CARDDEMO.PAUTH.REQUEST', 'FIRST', 'CP00') ON CONFLICT DO NOTHING").update();
        jdbc.sql("DELETE FROM td_queue_records").update();
        jdbc.sql("DELETE FROM users").update();
        jdbc.sql("INSERT INTO users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type, sec_usr_filler)"
                + " VALUES ('ADMIN001', 'MARGARET', 'GOLD', 'PASSWORD', 'A', ''),"
                + " ('USER0002', 'AJITH', 'KUMAR', 'PASSWORD', 'U', '')").update();
        region.install(EnumSet.allOf(Extension.class));
    }

    @Test
    void onlyAnAdminReachesTheQueues() throws Exception {
        Browser anonymous = new Browser(URI.create("http://127.0.0.1:" + port));
        anonymous.signOn(null, null);
        assertEquals(401, anonymous.get(REPLY + "/messages").statusCode());

        Browser user = new Browser(URI.create("http://127.0.0.1:" + port));
        user.signOn("USER0002", "PASSWORD");
        assertEquals(403, user.get(REPLY + "/messages").statusCode());
        assertEquals(403, user.post(REQUEST, Map.of("data", "x")).statusCode());
    }

    @Test
    void aPutRequestIsDecidedByCp00AndItsReplyCanBeBrowsedAndTaken() throws Exception {
        Browser admin = new Browser(URI.create("http://127.0.0.1:" + port));
        admin.signOn("ADMIN001", "PASSWORD");

        HttpResponse<String> put = admin.post(REQUEST, Map.of(
                "data", "261105,093000,4444333322221111,0100,1128,0100,POS001,000000,10.00,5411,USA,01,"
                        + "MER000000000001,FRESH MART,SEATTLE,WA,98101,TXN000000000003",
                "correlId", "WEB0001",
                "replyTo", "AWS.M2.CARDDEMO.PAUTH.REPLY"));
        assertEquals(201, put.statusCode(), put.body());
        assertEquals("WEB0001", JSON.readTree(put.body()).path("correlId").asText());

        JsonNode browsed = awaitOneMessage(admin);
        assertEquals("4444333322221111,TXN000000000003,093000,05,3100,          0.00,\0",
                browsed.path("data").asText());
        assertEquals("WEB0001", browsed.path("correlId").asText());
        assertEquals(1, transientData.records("CSSL").size());
        assertTrue(new String(transientData.records("CSSL").getFirst(), ISO_8859_1).contains("CARD NOT FOUND IN XREF"));

        HttpResponse<String> taken = admin.post(REPLY + "/get", Map.of());
        assertEquals(200, taken.statusCode());
        assertEquals(browsed, JSON.readTree(taken.body()));
        assertEquals(204, admin.post(REPLY + "/get", Map.of()).statusCode());
        assertEquals("[]", admin.get(REPLY + "/messages").body());
    }

    @Test
    void aBadPutIsABadRequest() throws Exception {
        Browser admin = new Browser(URI.create("http://127.0.0.1:" + port));
        admin.signOn("ADMIN001", "PASSWORD");
        assertEquals(400, admin.post(REQUEST, Map.of()).statusCode());
        assertEquals(400, admin.post(REQUEST, Map.of("data", "x", "correlId", "C".repeat(25))).statusCode());
        assertEquals(400, admin.post(REQUEST, Map.of("data", "\u20ac")).statusCode());
    }

    private static JsonNode awaitOneMessage(Browser admin) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (true) {
            JsonNode messages = JSON.readTree(admin.get(REPLY + "/messages").body());
            if (messages.size() == 1) {
                return messages.get(0);
            }
            assertTrue(System.nanoTime() < deadline, "no reply from CP00: " + messages);
            Thread.sleep(100);
        }
    }

    private static final class Browser {
        private final URI server;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        Browser(URI server) {
            this.server = server;
        }

        void signOn(String userId, String password) throws Exception {
            get("/api/session");
            JsonNode signon = JSON.readTree(post("/api/session", Map.of("transid", "CC00")).body());
            if (userId != null) {
                HttpResponse<String> menu = post("/api/screens/" + signon.path("screen").asText() + "/sign-on",
                        Map.of("turn", signon.path("turn").asLong(),
                                "fields", Map.of("USERID", userId, "PASSWD", password)));
                assertEquals(200, menu.statusCode(), menu.body());
            }
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(server.resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, Object body) throws Exception {
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
                    .orElse("");
        }
    }
}
