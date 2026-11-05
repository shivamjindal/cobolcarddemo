package com.carddemo.characterization;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.carddemo.online.BmsField;
import com.carddemo.online.MapId;
import com.carddemo.online.maps.Maps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The java target of {@code characterization/run.py}. Reads one online case on stdin, seeds the warm
 * server's database, plays each step over HTTP the way the browser does, and writes
 * {@code {"steps": [observation, ...]}} in the shape {@code characterization/harness/online.py}
 * reports. {@code java/characterization-adapter} starts the server and runs this class.
 */
public final class Adapter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> IGNORED_FIELDS = Set.of("CURDATE", "CURTIME", "APPLID", "SYSID", "TITLE01", "TITLE02");
    private static final List<String> SEEDED = List.of("USRSEC", "ACCTDATA", "CUSTDATA", "CARDDATA", "CARDXREF", "TRANSACT");

    public static void main(String[] args) throws Exception {
        JsonNode request = JSON.readTree(System.in);
        if (!"online".equals(request.path("kind").asText())) {
            throw new IllegalArgumentException("the java target runs online cases only");
        }
        JsonNode testCase = request.get("case");
        JsonNode setup = testCase.path("setup");
        setup.fieldNames().forEachRemaining(name -> {
            if (!SEEDED.contains(name)) {
                throw new IllegalArgumentException("the java target seeds " + SEEDED + "; the case sets up " + name);
            }
        });
        Path registryFile = Path.of(request.get("datasets").asText()).toAbsolutePath();
        Path repo = registryFile.getParent().getParent().getParent();
        JsonNode registry = JSON.readTree(registryFile.toFile());
        List<SeededTable> seeded = new ArrayList<>();
        for (String name : SEEDED) {
            seeded.add(SeededTable.of(registry, repo, name));
        }
        URI server = URI.create(env("CARDDEMO_CHARACTERIZATION_URL", "http://127.0.0.1:18080"));
        try (Connection db = DriverManager.getConnection(
                env("CARDDEMO_CHARACTERIZATION_JDBC_URL", "jdbc:postgresql://localhost:5432/carddemo_characterization"),
                env("CARDDEMO_JDBC_USER", "carddemo"), env("CARDDEMO_JDBC_PASSWORD", "carddemo"))) {
            seed(db, seeded, setup);
            Browser browser = new Browser(server);
            List<Map<String, Object>> observations = new ArrayList<>();
            for (JsonNode step : testCase.get("steps")) {
                List<TreeMap<String, byte[]>> before = new ArrayList<>();
                for (SeededTable table : seeded) {
                    before.add(table.snapshot(db));
                }
                long jobsBefore = lastJob(db);
                Map<String, Object> observation = browser.step(step.get("do"));
                Map<String, Object> files = new LinkedHashMap<>();
                for (int i = 0; i < seeded.size(); i++) {
                    SeededTable table = seeded.get(i);
                    table.diff(before.get(i), table.snapshot(db)).ifPresent(d -> files.put(table.name(), d));
                }
                if (!files.isEmpty()) {
                    observation.put("files", files);
                }
                Map<String, List<String>> queues = queued(db, jobsBefore);
                if (!queues.isEmpty()) {
                    observation.put("queues", queues);
                }
                observations.add(observation);
            }
            System.out.print(JSON.writeValueAsString(Map.of("steps", observations)));
        }
    }

    /**
     * Every dataset and the empty TD queues in one transaction. A case may delete a record others refer
     * to, as the harness's text files allow, so foreign-key triggers are off while seeding.
     */
    private static void seed(Connection db, List<SeededTable> seeded, JsonNode setup) throws Exception {
        db.setAutoCommit(false);
        try (Statement replica = db.createStatement()) {
            replica.execute("SET LOCAL session_replication_role = replica");
            replica.execute("DELETE FROM job_submissions");
        }
        for (SeededTable table : seeded) {
            table.materialize(db, setup.path(table.name()));
        }
        db.commit();
        db.setAutoCommit(true);
    }

    private static long lastJob(Connection db) throws SQLException {
        try (Statement select = db.createStatement();
                ResultSet last = select.executeQuery("SELECT coalesce(max(id), 0) FROM job_submissions")) {
            last.next();
            return last.getLong(1);
        }
    }

    /** The harness's {@code queues}: each TD queue's lines of the jobs submitted after job {@code after}, in order. */
    private static Map<String, List<String>> queued(Connection db, long after) throws SQLException {
        Map<String, List<String>> queues = new LinkedHashMap<>();
        try (PreparedStatement select = db.prepareStatement("SELECT queue, jcl FROM job_submissions WHERE id > ? ORDER BY id")) {
            select.setLong(1, after);
            try (ResultSet jobs = select.executeQuery()) {
                while (jobs.next()) {
                    queues.computeIfAbsent(jobs.getString("queue"), queue -> new ArrayList<>())
                            .addAll(Arrays.asList((String[]) jobs.getArray("jcl").getArray()));
                }
            }
        }
        return queues;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** One terminal: a cookie jar, the CSRF header, and the reply on screen. */
    private static final class Browser {

        private final URI server;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http;
        private JsonNode screen;

        Browser(URI server) throws IOException, InterruptedException {
            this.server = server;
            this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
            http.send(HttpRequest.newBuilder(server.resolve("/api/session")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
        }

        Map<String, Object> step(JsonNode step) throws IOException, InterruptedException {
            ObjectNode body = JSON.createObjectNode();
            String path;
            if (step.has("transid")) {
                body.put("transid", step.get("transid").asText());
                path = "/api/session";
            } else {
                if (screen == null || screen.path("kind").asText().equals("ended")) {
                    throw new IllegalStateException("session has ended; start the step with a transid");
                }
                String aid = step.path("aid").asText("ENTER");
                String action = "invalid-key";
                for (JsonNode bound : screen.path("actions")) {
                    if (bound.get("key").asText().equals(aid)) {
                        action = bound.get("name").asText();
                    }
                }
                body.put("turn", screen.get("turn").asLong());
                body.set("fields", step.has("fields") ? step.get("fields") : JSON.createObjectNode());
                if (action.equals("invalid-key")) {
                    body.put("key", aid);
                }
                path = "/api/screens/" + screen.get("screen").asText() + "/" + action;
            }
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(server.resolve(path))
                    .header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", csrfToken())
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> observation = new LinkedHashMap<>();
            if (response.statusCode() != 200) {
                observation.put("runtime_error", "HTTP " + response.statusCode() + ": " + response.body());
                return observation;
            }
            screen = JSON.readTree(response.body());
            observe(screen, observation);
            return observation;
        }

        private String csrfToken() {
            return cookies.getCookieStore().get(server).stream()
                    .filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue)
                    .findFirst()
                    .orElse("");
        }
    }

    /** What {@code screen.Terminal.snapshot()} reports, read from a reply. */
    private static void observe(JsonNode reply, Map<String, Object> observation) {
        switch (reply.get("kind").asText()) {
            case "text" -> observation.put("text", reply.get("text").asText());
            case "ended" -> {
                observation.put("text", reply.get("text").asText());
                observation.put("session", "ended");
                if (reply.hasNonNull("abend")) {
                    observation.put("abend", reply.get("abend").asText());
                }
            }
            case "screen" -> {
                String name = reply.get("map").get("name").asText();
                MapId<?> map = Maps.ALL.stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
                Map<String, String> fields = new LinkedHashMap<>();
                reply.get("fields").fields().forEachRemaining(f -> {
                    if (!IGNORED_FIELDS.contains(f.getKey()) && !f.getValue().asText().isBlank()) {
                        fields.put(f.getKey(), f.getValue().asText());
                    }
                });
                Map<String, String> colors = new LinkedHashMap<>();
                List<String> protect = new ArrayList<>();
                List<String> unprotect = new ArrayList<>();
                Set<String> protectedNow = new HashSet<>();
                reply.get("protectedFields").forEach(n -> protectedNow.add(n.asText()));
                for (var field : map.fields()) {
                    String fieldName = field.name();
                    BmsField bms = field.bms();
                    JsonNode color = reply.get("colors").get(fieldName);
                    if (color != null && !IGNORED_FIELDS.contains(fieldName)
                            && (bms.color() == null || !bms.color().name().equals(color.asText()))) {
                        colors.put(fieldName, color.asText());
                    }
                    if (protectedNow.contains(fieldName) && !bms.protectedByDefault()) {
                        protect.add(fieldName);
                    } else if (!protectedNow.contains(fieldName) && bms.protectedByDefault()) {
                        unprotect.add(fieldName);
                    }
                }
                observation.put("map", name);
                observation.put("fields", fields);
                if (!colors.isEmpty()) {
                    observation.put("colors", colors);
                }
                if (!protect.isEmpty()) {
                    observation.put("protected", protect.stream().sorted().toList());
                }
                if (!unprotect.isEmpty()) {
                    observation.put("unprotected", unprotect.stream().sorted().toList());
                }
                if (reply.hasNonNull("cursor")) {
                    observation.put("cursor", reply.get("cursor").asText());
                }
            }
            default -> throw new IllegalStateException("unknown reply kind " + reply.get("kind"));
        }
    }
}
