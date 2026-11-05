package com.carddemo.characterization;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

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
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.carddemo.online.BmsField;
import com.carddemo.records.Fields;
import com.carddemo.online.MapId;
import com.carddemo.online.maps.Maps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The java target of {@code characterization/run.py}. Reads one case on stdin and seeds the warm server's
 * database. An online case plays each step over HTTP the way the browser does; a batch case is a deck the
 * server's internal reader runs ({@link BatchCase}). Writes {@code {"steps": [observation, ...]}} in the shape
 * {@code characterization/harness/online.py} and {@code batch.py} report. {@code java/characterization-adapter}
 * starts the server and runs this class.
 */
public final class Adapter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> IGNORED_FIELDS = Set.of("CURDATE", "CURTIME", "APPLID", "SYSID", "TITLE01", "TITLE02");

    public static void main(String[] args) throws Exception {
        JsonNode request = JSON.readTree(System.in);
        try (Connection db = DriverManager.getConnection(
                env("CARDDEMO_CHARACTERIZATION_JDBC_URL", "jdbc:postgresql://localhost:5432/carddemo_characterization"),
                env("CARDDEMO_JDBC_USER", "carddemo"), env("CARDDEMO_JDBC_PASSWORD", "carddemo"))) {
            List<Map<String, Object>> observations = switch (request.path("kind").asText()) {
                case "online" -> online(request, db);
                case "batch" -> List.of(BatchCase.of(request).run(new SingleConnectionDataSource(db, true),
                        Clock.systemDefaultZone()));
                default -> throw new IllegalArgumentException("the java target runs online and batch cases, not "
                        + request.path("kind"));
            };
            System.out.print(JSON.writeValueAsString(Map.of("steps", observations)));
        }
    }

    private static List<Map<String, Object>> online(JsonNode request, Connection db) throws Exception {
        JsonNode testCase = request.get("case");
        JsonNode setup = testCase.path("setup");
        setup.fieldNames().forEachRemaining(name -> {
            if (!SeededTable.SEEDED.contains(name)) {
                throw new IllegalArgumentException("the java target seeds " + SeededTable.SEEDED + "; the case sets up " + name);
            }
        });
        Path registryFile = Path.of(request.get("datasets").asText()).toAbsolutePath();
        Path repo = registryFile.getParent().getParent().getParent();
        List<SeededTable> seeded = SeededTable.all(JSON.readTree(registryFile.toFile()), repo);
        SeededTable.seed(db, seeded, testCase);
        Browser browser = new Browser(URI.create(env("CARDDEMO_CHARACTERIZATION_URL", "http://127.0.0.1:18080")));
        List<Map<String, Object>> observations = new ArrayList<>();
        int[] messagesPut = {0};
        for (JsonNode step : testCase.get("steps")) {
            List<TreeMap<String, byte[]>> before = new ArrayList<>();
            for (SeededTable table : seeded) {
                before.add(table.snapshot(db));
            }
            long jobsBefore = lastJob(db);
            long tdBefore = lastTdRecord(db);
            JsonNode action = step.get("do");
            Map<String, Object> observation = action.has("mq")
                    ? mqStep(browser, db, action.get("mq"), messagesPut)
                    : browser.step(action);
            awaitSubmitted(db, jobsBefore);
            Map<String, Object> files = new LinkedHashMap<>();
            for (int i = 0; i < seeded.size(); i++) {
                SeededTable table = seeded.get(i);
                table.diff(before.get(i), table.snapshot(db)).ifPresent(d -> files.put(table.name(), d));
            }
            if (!files.isEmpty()) {
                observation.put("files", files);
            }
            Map<String, List<String>> queues = queued(db, jobsBefore);
            written(db, tdBefore).forEach((queue, records) -> queues.computeIfAbsent(queue, q -> new ArrayList<>())
                    .addAll(records));
            if (!queues.isEmpty()) {
                observation.put("queues", queues);
            }
            observations.add(observation);
        }
        return observations;
    }

    /**
     * {@code OnlineSession.mq_step}: the case's messages get msgIds HARNESS and a counter that runs across the
     * case's steps. The programs' puts, on any queue, are reported and taken off their queues, as the harness
     * reads and drops them; {@code unread} counts the case's messages still on the step's queue.
     */
    static Map<String, Object> mqStep(Browser browser, Connection db, JsonNode spec, int[] messagesPut)
            throws IOException, InterruptedException, SQLException {
        String queue = spec.get("queue").asText();
        HexFormat hex = HexFormat.of();
        List<Map<String, String>> messages = new ArrayList<>();
        for (JsonNode m : spec.path("messages")) {
            messagesPut[0]++;
            String correl = m.path("correl_id").asText("");
            messages.add(Map.of(
                    "data", hex.formatHex(m.path("data").asText("").getBytes(ISO_8859_1)),
                    "msgId", hex.formatHex(String.format("HARNESS%017d", messagesPut[0]).getBytes(ISO_8859_1)),
                    "correlId", hex.formatHex(correl.isEmpty() ? new byte[24] : padded(correl, 24)),
                    "replyTo", m.path("reply_to").asText("").replaceAll(" +$", "")));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queue", queue);
        body.put("trigger", spec.get("trigger").asText());
        body.put("messages", messages);
        HttpResponse<String> response = browser.post("/characterization/mq", body);
        Map<String, Object> observation = new LinkedHashMap<>();
        if (response.statusCode() != 200) {
            observation.put("runtime_error", "HTTP " + response.statusCode() + ": " + response.body());
            return observation;
        }
        JsonNode reply = JSON.readTree(response.body());
        long putThrough = reply.get("putThrough").asLong();
        Map<String, List<Map<String, String>>> puts = new TreeMap<>();
        try (PreparedStatement select = db.prepareStatement(
                "SELECT queue, data, correl_id FROM mq_messages WHERE sequence > ? ORDER BY sequence");
                PreparedStatement delete = db.prepareStatement("DELETE FROM mq_messages WHERE sequence > ?")) {
            select.setLong(1, putThrough);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    puts.computeIfAbsent(rows.getString("queue"), q -> new ArrayList<>()).add(Map.of(
                            "data", Python.rstrip(new String(rows.getBytes("data"), ISO_8859_1)),
                            "correl_id", new String(rows.getBytes("correl_id"), ISO_8859_1).replaceAll("[ \0]+$", "")));
                }
            }
            delete.setLong(1, putThrough);
            delete.executeUpdate();
        }
        observation.put("mq", puts);
        try (PreparedStatement count = db.prepareStatement("SELECT count(*) FROM mq_messages WHERE queue = ?")) {
            count.setString(1, queue);
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                if (rows.getInt(1) > 0) {
                    observation.put("unread", Map.of(queue, rows.getInt(1)));
                }
            }
        }
        if (reply.has("abend")) {
            observation.put("abend", reply.get("abend").asText());
        }
        if (reply.has("notDefined")) {
            observation.put("runtime_error", "transaction " + reply.get("notDefined").asText() + " is not defined");
        }
        return observation;
    }

    private static byte[] padded(String text, int length) {
        byte[] bytes = Fields.spaces(length);
        Fields.text(bytes, 0, length, text);
        return bytes;
    }

    private static long lastJob(Connection db) throws SQLException {
        try (Statement select = db.createStatement();
                ResultSet last = select.executeQuery("SELECT coalesce(max(id), 0) FROM job_submissions")) {
            last.next();
            return last.getLong(1);
        }
    }

    /**
     * Waits until every job submitted during the step has been claimed and has ended, so the next case's seed
     * does not delete a data set the reader is still writing.
     */
    private static void awaitSubmitted(Connection db, long after) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (true) {
            boolean pending;
            try (PreparedStatement select = db.prepareStatement("""
                    select count(*) from job_submissions s
                    left join job_submission_runs r on r.submission_id = s.id
                    where s.id > ? and (r.submission_id is null or r.ended_at is null)""")) {
                select.setLong(1, after);
                try (ResultSet result = select.executeQuery()) {
                    result.next();
                    pending = result.getInt(1) > 0;
                }
            }
            if (!pending) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("a job submitted after " + after + " did not end");
            }
            Thread.sleep(50);
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

    private static long lastTdRecord(Connection db) throws SQLException {
        try (Statement select = db.createStatement();
                ResultSet last = select.executeQuery("SELECT coalesce(max(sequence), 0) FROM td_queue_records")) {
            last.next();
            return last.getLong(1);
        }
    }

    /** The harness's {@code queues} from WRITEQ TD: each record after {@code after}, as text without trailing whitespace. */
    private static Map<String, List<String>> written(Connection db, long after) throws SQLException {
        Map<String, List<String>> queues = new LinkedHashMap<>();
        try (PreparedStatement select = db.prepareStatement(
                "SELECT queue, data FROM td_queue_records WHERE sequence > ? ORDER BY sequence")) {
            select.setLong(1, after);
            try (ResultSet records = select.executeQuery()) {
                while (records.next()) {
                    queues.computeIfAbsent(records.getString("queue"), queue -> new ArrayList<>())
                            .add(Python.rstrip(new String(records.getBytes("data"), ISO_8859_1)));
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
    static final class Browser {

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

        HttpResponse<String> post(String path, Object body) throws IOException, InterruptedException {
            return http.send(HttpRequest.newBuilder(server.resolve(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.ofString());
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
                if (reply.hasNonNull("abend")) {
                    observation.put("abend", reply.get("abend").asText());
                } else {
                    observation.put("session", "ended");
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
