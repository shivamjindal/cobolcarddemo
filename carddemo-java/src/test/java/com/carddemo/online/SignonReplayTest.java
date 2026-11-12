package com.carddemo.online;

import org.apache.catalina.Context;
import org.apache.catalina.Session;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Replays COSGN00C against a running server: every case starts at {@code /}, follows the OIDC
 * redirects to the sign-on screen and submits it, as a browser would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(TestDatabase.class)
class SignonReplayTest {

    private static final int PORT = TestSocketUtils.findAvailableTcpPort();
    private static final String BASE = "http://localhost:" + PORT;
    private static final Tally TALLY = new Tally();

    @DynamicPropertySource
    static void server(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("carddemo.base-url", () -> BASE);
    }

    @Autowired
    UsersTable users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ServletWebServerApplicationContext server;

    @TestFactory
    Stream<DynamicTest> replay() {
        return SpecCase.load("COSGN00C").stream().map(c -> dynamicTest(c.toString(), () -> replay(c)));
    }

    @AfterAll
    static void printTally() {
        TALLY.print();
    }

    @Test
    void aUserAddedInLowerCaseSignsOnInAnyCase() throws Exception {
        users.reset(List.of(Map.of("SEC-USR-ID", "ADMIN001", "SEC-USR-FNAME", "MARGARET", "SEC-USR-LNAME", "GOLD",
                "SEC-USR-PWD", "PASSWORD", "SEC-USR-TYPE", "A")));
        Browser admin = new Browser(BASE);
        assertThat(signOn(admin, "admin001", "password").path()).isEqualTo("/screens/COADM01C");
        Browser.Page add = admin.get("/screens/COUSR01C");
        Browser.Page added = admin.post("/screens/COUSR01C", Map.of("FNAME", "Mixed", "LNAME", "Case",
                "USERID", "mixed01", "PASSWD", "Secret1", "USRTYPE", "u", "aid", "ENTER", "_csrf", add.csrf()));
        assertThat(added.testid("ERRMSG")).isEqualTo("User MIXED01 has been added ...");
        assertThat(jdbc.queryForObject("SELECT sec_usr_type FROM users WHERE sec_usr_id = 'MIXED01'", String.class))
                .isEqualTo("U");

        assertThat(signOn(new Browser(BASE), "MIXED01", "SECRET1").path()).isEqualTo("/screens/COMEN01C");
        assertThat(signOn(new Browser(BASE), "Mixed01", "secret1").path()).isEqualTo("/screens/COMEN01C");
    }

    private void replay(SpecCase c) throws Exception {
        if (c.kind() == SpecCase.Kind.SKIPPED) {
            TALLY.record(c);
            abort(c.reason());
        }
        users.reset(c.users());
        var before = users.snapshot();
        Browser browser = new Browser(BASE);
        Browser.Page page = browser.get("/");
        if (!c.entry().equals("first-time")) {
            Map<String, String> form = new HashMap<>(c.fields());
            form.put("aid", c.aid());
            form.put("_csrf", page.csrf());
            page = users.with(c.fileConditions(), () -> browser.post("/signon", form));
        }
        c.verify(observe(page, browser, users.effectsSince(before)));
        TALLY.record(c);
    }

    private Browser.Page signOn(Browser browser, String user, String password) throws Exception {
        Browser.Page signon = browser.get("/");
        return browser.post("/signon", Map.of("USERID", user, "PASSWD", password, "aid", "ENTER", "_csrf", signon.csrf()));
    }

    private Observed observe(Browser.Page page, Browser browser, List<UsersTable.Effect> effects) throws Exception {
        String transfer = switch (page.path()) {
            case "/signon" -> "RETURN-TRANSID " + page.testid("TRNNAME");
            case "/signed-off" -> "SEND-TEXT null";
            default -> "XCTL " + page.path().replaceFirst("^/screens/", "");
        };
        String message = page.testid("ERRMSG");
        Element cursor = page.html().selectFirst("[autofocus]");
        Map<String, String> fields = new HashMap<>();
        page.html().select("[data-testid]").forEach(e -> fields.put(e.attr("data-testid"),
                e.tagName().equals("input") ? e.val() : e.text()));
        return new Observed(page.status(), transfer, message.isEmpty() ? null : message,
                cursor == null ? null : cursor.attr("data-testid"), fields,
                conversation(browser.cookie("JSESSIONID")), effects);
    }

    private Conversation conversation(String sessionId) throws Exception {
        Context context = (Context) ((TomcatWebServer) server.getWebServer()).getTomcat().getHost().findChildren()[0];
        Session session = sessionId == null ? null : context.getManager().findSession(sessionId);
        return session == null ? null : (Conversation) session.getSession().getAttribute(Conversation.KEY);
    }
}
