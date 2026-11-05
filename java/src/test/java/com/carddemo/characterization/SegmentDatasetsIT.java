package com.carddemo.characterization;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The IMS and AUTHFRDS datasets of the registry decode, key and survive their tables the way
 * {@code characterization/harness/datasets.py} sees them. The comparison with Python runs where python3 is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SegmentDatasetsIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static JsonNode registry;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    static void readRegistry() throws IOException {
        registry = JSON.readTree(REPO.resolve("characterization/fixtures/datasets.json").toFile());
        Assumptions.assumeTrue(registry.path("datasets").has("PAUTDTL1"), "the registry has no IMS datasets yet");
    }

    @Test
    void decodesAndKeysTheSeedsAsTheHarnessDoes() throws Exception {
        Assumptions.assumeTrue(python(), "python3 is not available");
        for (String name : List.of("PAUTSUM0", "PAUTDTL1")) {
            SeededTable table = SeededTable.of(registry, REPO, name);
            JsonNode harness = JSON.readTree(runPython("""
                    import json, sys
                    sys.path.insert(0, 'characterization/harness')
                    from datasets import Datasets
                    d = Datasets()
                    print(json.dumps([[d.key_text('%1$s', r), d.decode('%1$s', r)] for r in d.seed_records('%1$s')]))
                    """.formatted(name)));
            List<byte[]> records = SeededTable.hexRecords(REPO.resolve(registry.at("/datasets/" + name + "/source").asText()),
                    registry.at("/datasets/" + name + "/reclen").asInt());
            assertEquals(harness.size(), records.size(), name);
            for (int i = 0; i < records.size(); i++) {
                Map<String, String> decoded = table.layout().decode(records.get(i));
                assertEquals(JSON.convertValue(harness.get(i).get(1), Map.class), decoded, name + " record " + i);
                assertEquals(harness.get(i).get(0).asText(), table.keyTextOf(records.get(i)), name + " key " + i);
            }
        }
    }

    @Test
    void encodesPackedAndBinaryFieldsAsTheHarnessDoes() throws Exception {
        Assumptions.assumeTrue(python(), "python3 is not available");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("PA-ACCT-ID", "41000000001");
        values.put("PA-APPROVED-AUTH-CNT", "-3");
        values.put("PA-DECLINED-AUTH-CNT", "300");
        values.put("PA-CREDIT-LIMIT", "-12.5");
        values.put("PA-CUST-ID", "7");
        values.put("PA-ACCOUNT-STATUS(2)", "A1");
        String python = runPython("""
                import json, sys
                sys.path.insert(0, 'characterization/harness')
                from datasets import Datasets
                print(Datasets().encode('PAUTSUM0', json.loads(sys.argv[1])).hex())
                """, JSON.writeValueAsString(values)).strip();
        byte[] java = SeededTable.of(registry, REPO, "PAUTSUM0").layout().encode(JSON.valueToTree(values), null);
        assertEquals(python, HexFormat.of().formatHex(java));
    }

    @Test
    void negativeZeroAndBinaryDecodeLikePythonDecimal() throws IOException {
        HarnessLayout layout = SeededTable.of(registry, REPO, "PAUTSUM0").layout();
        byte[] record = layout.encode(JSON.readTree("{\"PA-ACCT-ID\": \"1\", \"PA-APPROVED-AUTH-CNT\": \"-2\"}"), null);
        Arrays.fill(record, 26, 31, (byte) 0);
        record[31] = 0x0d;
        Map<String, String> decoded = layout.decode(record);
        assertEquals("-0.00", decoded.get("PA-CREDIT-LIMIT"));
        assertEquals("-2", decoded.get("PA-APPROVED-AUTH-CNT"));
        assertEquals("1", decoded.get("PA-ACCT-ID"));
    }

    @Test
    void theSeedsSurviveTheirTablesAndEditsFindRecordsByKeyText() throws Exception {
        try (Connection db = dataSource.getConnection()) {
            db.setAutoCommit(false);
            try {
                SeededTable summaries = SeededTable.of(registry, REPO, "PAUTSUM0");
                SeededTable details = SeededTable.of(registry, REPO, "PAUTDTL1");
                SeededTable frauds = SeededTable.of(registry, REPO, "AUTHFRDS");
                for (SeededTable table : List.of(summaries, details, frauds)) {
                    roundTrips(db, table);
                }

                String acct = summaries.keyTextOf(summaries.snapshot(db).firstEntry().getValue());
                details.materialize(db, JSON.readTree("{\"where\": {\"PAUT-PARENT-ACCT-ID\": [\"" + acct + "\"]}}"));
                long children = details.snapshot(db).values().stream()
                        .filter(r -> details.layout().decode(r).get("PAUT-PARENT-ACCT-ID").equals(acct)).count();
                assertTrue(children > 0);
                assertEquals(details.snapshot(db).size(), children);
                if (python()) {
                    assertEquals(runPython("""
                            import sys
                            sys.path.insert(0, 'characterization/harness')
                            from datasets import Datasets
                            d = Datasets()
                            print(len(d.select('PAUTDTL1', d.seed_records('PAUTDTL1'), {'PAUT-PARENT-ACCT-ID': [sys.argv[1]]})))
                            """, acct).strip(), Long.toString(children));
                }
                details.materialize(db, JSON.createObjectNode());

                TreeMap<String, byte[]> before = details.snapshot(db);
                String first = details.keyTextOf(before.firstEntry().getValue());
                details.materialize(db, JSON.readTree("{\"delete\": [\"" + first + "\"], \"upsert\": [{"
                        + "\"PAUT-PARENT-ACCT-ID\": \"1\", \"PA-AUTH-DATE-9C\": \"99999\", \"PA-AUTH-TIME-9C\": \"5\","
                        + " \"PA-PROCESSING-CODE\": \"0\", \"PA-POS-ENTRY-MODE\": \"1\","
                        + " \"PA-TRANSACTION-AMT\": \"1.00\", \"PA-APPROVED-AMT\": \"0\","
                        + " \"PA-MERCHANT-NAME\": \"FIXTURE\"}]}"));
                Map<String, Object> diff = details.diff(before, details.snapshot(db)).orElseThrow();
                assertEquals(List.of(first), diff.get("deleted"));
                @SuppressWarnings("unchecked")
                Map<String, String> inserted = ((List<Map<String, String>>) diff.get("inserted")).get(0);
                assertEquals("FIXTURE", inserted.get("PA-MERCHANT-NAME"));

                byte[] fraud = frauds.layout().encode(JSON.readTree("""
                                {"CARD-NUM": "4111111111111111", "AUTH-TS": "2026-11-05-10.00.00.123456",
                                 "TRANSACTION-AMT": "-12.34", "POS-ENTRY-MODE": "5", "FRAUD-RPT-DATE": "2026-11-05",
                                 "ACCT-ID": "41", "AUTH-FRAUD": "F"}"""), null);
                frauds.storage().replace(db, List.of(fraud));
                byte[] back = frauds.storage().read(db).get(0);
                assertEquals(new String(fraud, ISO_8859_1), new String(back, ISO_8859_1));
                assertEquals("41111111111111112026-11-05-10.00.00.123456", frauds.keyTextOf(back));
            } finally {
                db.rollback();
            }
        }
    }

    private static void roundTrips(Connection db, SeededTable table) throws IOException, SQLException {
        JsonNode entry = registry.path("datasets").path(table.name());
        Path source = REPO.resolve(entry.get("source").asText());
        List<byte[]> seed = entry.path("encoding").asText().equals("hex")
                ? SeededTable.hexRecords(source, entry.get("reclen").asInt())
                : SeededTable.seedRecords(source, entry.get("reclen").asInt());
        table.materialize(db, JSON.createObjectNode());
        TreeMap<String, byte[]> stored = table.snapshot(db);
        assertEquals(seed.size(), stored.size(), table.name());
        List<Map<String, String>> expected = new ArrayList<>();
        seed.forEach(r -> expected.add(table.layout().decode(r)));
        List<Map<String, String>> actual = new ArrayList<>();
        stored.values().forEach(r -> actual.add(table.layout().decode(r)));
        assertTrue(actual.containsAll(expected) && expected.containsAll(actual), table.name());
        assertFalse(table.diff(stored, table.snapshot(db)).isPresent(), table.name());
    }

    private static boolean python() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String runPython(String script, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("python3", "-c", script));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(REPO.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), ISO_8859_1);
        assertEquals(0, process.waitFor(), out);
        return out;
    }
}
