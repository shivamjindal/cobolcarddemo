package com.carddemo.characterization;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import com.carddemo.cics.Extension;
import com.carddemo.dli.SegmentTables;
import com.carddemo.records.Fields;
import com.carddemo.records.KeyedTable;
import com.carddemo.vsam.CopybookParser;
import com.carddemo.vsam.Layout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One keyed dataset of the harness registry held in Postgres: a KSDS in its V1 table, a Db2 table of the
 * transaction-type extension as row images, an IMS segment or AUTHFRDS. Seeding, snapshots and diffs follow
 * {@code characterization/harness/datasets.py}: records are keyed by their latin-1 key characters, and the
 * diff decodes fields the way the harness does, so the two compare equal. A dataset with {@code "encoding":
 * "hex"} has one hex record per seed line, and one with {@code key_fields} reports a key as those decoded
 * fields joined with "/".
 */
public final class SeededTable {

    /** Every keyed dataset the java target seeds. The last three join only once the registry has them. */
    static final List<String> SEEDED = List.of("USRSEC", "ACCTDATA", "CUSTDATA", "CARDDATA", "CARDXREF", "TRANSACT",
            "DISCGRP", "TCATBALF", "TRANCATG", "TRANTYPE", "TRANSACTION_TYPE", "TRANSACTION_TYPE_CATEGORY",
            "PAUTSUM0", "PAUTDTL1", "AUTHFRDS");

    private static final List<String> OPTIONAL = List.of("PAUTSUM0", "PAUTDTL1", "AUTHFRDS");

    private final String name;
    private final Storage storage;
    private final int recordLength;
    private final int keyOffset;
    private final int keyLength;
    private final HarnessLayout layout;
    private final Path seed;
    private final boolean hex;
    private final List<String> keyFields;

    private SeededTable(String name, Storage storage, JsonNode entry, HarnessLayout layout, Path seed) {
        this.name = name;
        this.storage = storage;
        this.recordLength = entry.get("reclen").asInt();
        this.keyOffset = entry.get("key").get(0).asInt();
        this.keyLength = entry.get("key").get(1).asInt();
        this.layout = layout;
        this.seed = seed;
        this.hex = entry.path("encoding").asText().equals("hex");
        List<String> fields = new ArrayList<>();
        entry.path("key_fields").forEach(f -> fields.add(f.asText()));
        this.keyFields = List.copyOf(fields);
    }

    static SeededTable of(JsonNode registry, Path repo, String name) throws IOException {
        JsonNode entry = registry.path("datasets").path(name);
        Path seed = repo.resolve(entry.get("source").asText());
        if (entry.has("db2")) {
            Db2Table table = Db2Table.valueOf(name);
            if (entry.get("reclen").asInt() != table.recordLength() || entry.get("key").get(0).asInt() != 0
                    || entry.get("key").get(1).asInt() != table.keyLength()) {
                throw new IllegalArgumentException(name + " in the registry does not match " + table.table());
            }
            return new SeededTable(name, table, entry, HarnessLayout.of(repo, registry, name), seed);
        }
        return switch (name) {
            case "PAUTSUM0", "PAUTDTL1" -> {
                SegmentStorage segments = SegmentStorage.valueOf(name);
                HarnessLayout layout = HarnessLayout.of(name, entry.get("reclen").asInt(), segments.layout());
                yield new SeededTable(name, segments, entry, layout, seed);
            }
            case "AUTHFRDS" -> {
                Layout copybook = new CopybookParser().parse(
                        Files.readString(repo.resolve(entry.get("copybook").asText()), ISO_8859_1));
                yield new SeededTable(name, new AuthfrdsStorage(entry.get("reclen").asInt(), copybook.fields()),
                        entry, HarnessLayout.of(repo, registry, name), seed);
            }
            default -> {
                KeyedTable table = KeyedTable.valueOf(name);
                if (entry.get("reclen").asInt() != table.recordLength()
                        || entry.get("key").get(0).asInt() != table.key().offset()
                        || entry.get("key").get(1).asInt() != table.key().length()) {
                    throw new IllegalArgumentException(name + " in the registry does not match " + table.dsn());
                }
                yield new SeededTable(name, new Storage.Ksds(table), entry, HarnessLayout.of(repo, registry, name), seed);
            }
        };
    }

    public static List<SeededTable> all(JsonNode registry, Path repo) throws IOException {
        List<SeededTable> tables = new ArrayList<>();
        for (String name : SEEDED) {
            if (!OPTIONAL.contains(name) || registry.path("datasets").has(name)) {
                tables.add(of(registry, repo, name));
            }
        }
        return tables;
    }

    /**
     * Every table from its seed and the case's {@code setup}, with no job queued, claimed or spooled, no data set
     * cataloged, no message on any queue, every CICS file open and only the case's {@code install} extensions
     * installed, in one transaction. A case may delete a record others refer to, as the harness's text files allow, so foreign-key
     * triggers are off while seeding, and with them the cascades.
     */
    public static void seed(Connection db, List<SeededTable> tables, JsonNode testCase) throws IOException, SQLException {
        List<String> install = new ArrayList<>();
        testCase.path("install").forEach(extension -> install.add(Extension.byId(extension.asText()).id()));
        db.setAutoCommit(false);
        try (Statement replica = db.createStatement();
                PreparedStatement region = db.prepareStatement("UPDATE cics_region SET closed = '{}', installed = ?")) {
            replica.execute("SET LOCAL session_replication_role = replica");
            for (String batchState : List.of("job_submission_runs", "job_submissions", "spool", "dataset_records",
                    "datasets", "mq_messages", "mq_queues", "td_queue_records", SegmentTables.DETAILS,
                    SegmentTables.SUMMARIES, "authfrds")) {
                replica.execute("DELETE FROM " + batchState);
            }
            region.setArray(1, db.createArrayOf("text", install.toArray()));
            region.executeUpdate();
        }
        for (SeededTable table : tables) {
            table.materialize(db, testCase.path("setup").path(table.name()));
        }
        db.commit();
        db.setAutoCommit(true);
    }

    String name() {
        return name;
    }

    Storage storage() {
        return storage;
    }

    HarnessLayout layout() {
        return layout;
    }

    /** The seed records or the case's {@code replace}, narrowed by {@code where}, then {@code upsert} and {@code delete}. */
    void materialize(Connection db, JsonNode edit) throws IOException, SQLException {
        List<byte[]> records = new ArrayList<>();
        if (edit.has("replace")) {
            edit.get("replace").forEach(r -> records.add(layout.encode(r, null)));
        } else {
            records.addAll(seeded());
        }
        TreeMap<String, byte[]> byKey = new TreeMap<>();
        where(layout, records, edit.path("where")).forEach(r -> byKey.put(key(r), r));
        for (JsonNode upsert : edit.path("upsert")) {
            byte[] fresh = layout.encode(upsert, null);
            byte[] base = byKey.get(key(fresh));
            byKey.put(key(fresh), base == null ? fresh : layout.encode(upsert, base));
        }
        for (JsonNode delete : edit.path("delete")) {
            byKey.remove(keyOf(delete.asText()));
        }
        storage.replace(db, byKey.values());
    }

    /** {@code Datasets.seed_records}: the seed's records in seed order, before any case edit. */
    List<byte[]> seeded() throws IOException {
        return hex ? hexRecords(seed, recordLength) : seedRecords(seed, recordLength);
    }

    /** Record by key, as the harness snapshots a {@code .dat} file. */
    TreeMap<String, byte[]> snapshot(Connection db) throws SQLException {
        TreeMap<String, byte[]> byKey = new TreeMap<>();
        for (byte[] record : storage.read(db)) {
            byKey.put(key(record), record);
        }
        return byKey;
    }

    /** The harness's {@code files} entry for this dataset, empty when nothing changed. */
    Optional<Map<String, Object>> diff(TreeMap<String, byte[]> before, TreeMap<String, byte[]> after) {
        List<Map<String, String>> inserted = new ArrayList<>();
        List<Map<String, Object>> updated = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            byte[] old = before.get(key);
            byte[] now = after.get(key);
            if (old == null) {
                inserted.add(layout.decode(now));
            } else if (now == null) {
                deleted.add(keyTextOf(old));
            } else if (!Arrays.equals(old, now)) {
                Map<String, String> was = layout.decode(old);
                Map<String, List<String>> changed = new LinkedHashMap<>();
                layout.decode(now).forEach((field, value) -> {
                    if (!value.equals(was.get(field))) {
                        changed.put(field, List.of(was.get(field), value));
                    }
                });
                updated.add(Map.of("key", keyTextOf(now), "changed", changed));
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        if (!inserted.isEmpty()) {
            entry.put("inserted", inserted);
        }
        if (!updated.isEmpty()) {
            entry.put("updated", updated);
        }
        if (!deleted.isEmpty()) {
            entry.put("deleted", deleted);
        }
        return entry.isEmpty() ? Optional.empty() : Optional.of(entry);
    }

    /** {@code Datasets.seed_records}: each non-blank line, cut or space-filled to the record length. */
    static List<byte[]> seedRecords(Path source, int recordLength) throws IOException {
        List<byte[]> records = new ArrayList<>();
        for (String line : new String(Files.readAllBytes(source), ISO_8859_1).split("\n", -1)) {
            String record = line.replaceAll("\r+$", "");
            if (!record.isBlank()) {
                byte[] bytes = Fields.spaces(recordLength);
                Fields.text(bytes, 0, recordLength, record);
                records.add(bytes);
            }
        }
        return records;
    }

    /** {@code Datasets.select}: the records whose decoded value of each field is one of that field's values. */
    static List<byte[]> where(HarnessLayout layout, List<byte[]> records, JsonNode where) {
        if (where.isMissingNode()) {
            return records;
        }
        List<byte[]> kept = new ArrayList<>();
        for (byte[] record : records) {
            Map<String, String> values = layout.decode(record);
            boolean keep = true;
            for (Map.Entry<String, JsonNode> field : where.properties()) {
                List<String> allowed = new ArrayList<>();
                field.getValue().forEach(a -> allowed.add(a.asText()));
                if (!values.containsKey(field.getKey())) {
                    throw new IllegalArgumentException("where names no field of the record: " + field.getKey());
                }
                keep &= allowed.contains(values.get(field.getKey()));
            }
            if (keep) {
                kept.add(record);
            }
        }
        return kept;
    }

    /** {@code Datasets._parse} of a hex dataset: each non-blank line's bytes, cut or space-filled. */
    static List<byte[]> hexRecords(Path source, int recordLength) throws IOException {
        List<byte[]> records = new ArrayList<>();
        for (String line : Files.readString(source, ISO_8859_1).split("\n", -1)) {
            String record = line.replaceAll("\r+$", "");
            if (!record.isBlank()) {
                byte[] bytes = Fields.spaces(recordLength);
                byte[] decoded = HexFormat.of().parseHex(record.strip());
                System.arraycopy(decoded, 0, bytes, 0, Math.min(decoded.length, recordLength));
                records.add(bytes);
            }
        }
        return records;
    }

    /** {@code Datasets.key_text}. */
    String keyTextOf(byte[] record) {
        return keyFields.isEmpty() ? Python.rstrip(key(record)) : layout.keyText(record, keyFields);
    }

    /** {@code Datasets._key_bytes}: a key as a diff reports it, back to the key characters. */
    private String keyOf(String text) {
        if (keyFields.isEmpty()) {
            byte[] key = Fields.spaces(keyLength);
            Fields.text(key, 0, key.length, text);
            return new String(key, ISO_8859_1);
        }
        String[] parts = text.split("/", -1);
        ObjectNode values = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < keyFields.size() && i < parts.length; i++) {
            values.put(keyFields.get(i), parts[i]);
        }
        return key(layout.encode(values, null));
    }

    private String key(byte[] record) {
        return new String(record, keyOffset, keyLength, ISO_8859_1);
    }
}
