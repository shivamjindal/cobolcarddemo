package com.carddemo.characterization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Every batch golden through {@link BatchCase} and the internal reader of this context, compared as
 * {@code characterization/run.py} compares them: the whole observation unless the case matches a subset, and
 * keys starting with {@code _} dropped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"carddemo.batch.drivers.enabled=true", "carddemo.batch.reader.poll=PT0.05S"})
@DirtiesContext
class BatchGoldensIT {

    /** Programs whose port has not landed; their cases are skipped. */
    private static final Set<String> PENDING_PROGRAMS = Set.of("CBACT01C", "CBACT02C", "CBACT03C", "CBACT04C",
            "CBCUS01C", "CBSTM03A", "CBTRN02C", "CBTRN03C");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CASES = Path.of("..", "characterization", "cases", "batch");
    private static final Path REGISTRY = Path.of("..", "characterization", "fixtures", "datasets.json");
    private static final LocalDateTime HARNESS_CLOCK = LocalDateTime.of(2026, 11, 5, 10, 0);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(HARNESS_CLOCK.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Clock clock;

    @TestFactory
    Stream<DynamicTest> goldens() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> files = Files.list(CASES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonNode suite = JSON.readTree(file.toFile());
                for (JsonNode testCase : suite.get("cases")) {
                    tests.add(DynamicTest.dynamicTest(testCase.get("id").asText(), () -> golden(suite, testCase)));
                }
            }
        }
        return tests.stream();
    }

    private void golden(JsonNode suite, JsonNode testCase) throws Exception {
        String program = testCase.get("job").get("program").asText();
        assumeFalse(PENDING_PROGRAMS.contains(program), program + " is not ported yet");
        ObjectNode request = JSON.createObjectNode()
                .put("kind", suite.get("kind").asText())
                .put("feature", suite.get("feature").asText())
                .put("datasets", REGISTRY.toAbsolutePath().normalize().toString());
        ObjectNode sent = request.putObject("case");
        for (String key : List.of("id", "setup", "job")) {
            if (testCase.has(key)) {
                sent.set(key, testCase.get(key));
            }
        }
        Map<String, Object> observation = BatchCase.of(request).run(dataSource, clock);
        List<String> diffs = testCase.has("expect")
                ? compare(testCase.get("expect"), visible(JSON.valueToTree(observation)),
                        testCase.path("match").asText().equals("subset"), "$")
                : List.of("no golden recorded");
        assertEquals(List.of(), diffs, testCase.get("id").asText());
    }

    /** {@code run.public}. */
    private static JsonNode visible(JsonNode node) {
        if (node.isObject()) {
            ObjectNode kept = JSON.createObjectNode();
            node.properties().forEach(field -> {
                if (!field.getKey().startsWith("_")) {
                    kept.set(field.getKey(), visible(field.getValue()));
                }
            });
            return kept;
        }
        if (node.isArray()) {
            ArrayNode kept = JSON.createArrayNode();
            node.forEach(item -> kept.add(visible(item)));
            return kept;
        }
        return node;
    }

    /** {@code run.compare}. */
    private static List<String> compare(JsonNode expected, JsonNode actual, boolean subset, String path) {
        List<String> diffs = new ArrayList<>();
        if (expected.isObject() && actual.isObject()) {
            TreeSet<String> keys = new TreeSet<>();
            expected.fieldNames().forEachRemaining(keys::add);
            if (!subset) {
                actual.fieldNames().forEachRemaining(keys::add);
            }
            for (String key : keys) {
                if (!actual.has(key)) {
                    diffs.add(path + "." + key + ": expected " + expected.get(key) + ", missing");
                } else if (!expected.has(key)) {
                    diffs.add(path + "." + key + ": unexpected " + actual.get(key));
                } else {
                    diffs.addAll(compare(expected.get(key), actual.get(key), subset, path + "." + key));
                }
            }
        } else if (!expected.equals(actual)) {
            diffs.add(path + ": expected " + expected + ", got " + actual);
        }
        return diffs;
    }
}
