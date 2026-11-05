package com.carddemo.characterization;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import com.carddemo.vsam.CopybookParser;
import com.carddemo.vsam.Dataset;
import com.carddemo.vsam.Datasets;
import com.carddemo.vsam.Field;
import com.carddemo.vsam.Layout;
import com.carddemo.vsam.Names;
import com.carddemo.vsam.Pic;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One KSDS of the harness registry held in its Postgres table. Seeding, snapshots and diffs follow
 * {@code characterization/harness/datasets.py}: a record is the 80 latin-1 characters of its copybook,
 * and a column holds its field rstripped. Only alphanumeric layouts, which is all this slice reads.
 */
final class SeededTable {

    private final String name;
    private final String table;
    private final List<Field> fields;
    private final int recordLength;
    private final int keyOffset;
    private final int keyLength;
    private final Path seed;

    private SeededTable(String name, String table, List<Field> fields, int recordLength, int keyOffset,
            int keyLength, Path seed) {
        this.name = name;
        this.table = table;
        this.fields = fields;
        this.recordLength = recordLength;
        this.keyOffset = keyOffset;
        this.keyLength = keyLength;
        this.seed = seed;
    }

    static SeededTable of(JsonNode registry, Path repo, String name) throws IOException {
        JsonNode entry = registry.path("datasets").path(name);
        Dataset dataset = Datasets.ALL.stream()
                .filter(d -> d.clusterDsn().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no table for dataset " + name));
        Layout layout = new CopybookParser().parse(Files.readString(repo.resolve(entry.get("copybook").asText()), ISO_8859_1));
        for (Field field : layout.fields()) {
            if (!(field.pic() instanceof Pic.Alnum)) {
                throw new IllegalArgumentException(name + " field " + field.cobolName() + " is not alphanumeric");
            }
        }
        return new SeededTable(name, dataset.table(), layout.fields(), entry.get("reclen").asInt(),
                entry.get("key").get(0).asInt(), entry.get("key").get(1).asInt(), repo.resolve(entry.get("source").asText()));
    }

    String name() {
        return name;
    }

    /** The seed records, then the case's {@code replace}, {@code upsert} and {@code delete} edits. */
    void materialize(Connection db, JsonNode edit) throws IOException, SQLException {
        List<String> records = edit.has("replace") ? encodeAll(edit.get("replace")) : seedRecords();
        TreeMap<String, String> byKey = new TreeMap<>();
        records.forEach(r -> byKey.put(key(r), r));
        for (JsonNode upsert : edit.path("upsert")) {
            String fresh = encode(upsert, null);
            String base = byKey.get(key(fresh));
            byKey.put(key(fresh), base == null ? fresh : encode(upsert, base));
        }
        for (JsonNode delete : edit.path("delete")) {
            byKey.remove(pad(delete.asText(), keyLength));
        }
        write(db, byKey.values());
    }

    /** Record by key, as the harness snapshots a {@code .dat} file. */
    TreeMap<String, String> snapshot(Connection db) throws SQLException {
        TreeMap<String, String> byKey = new TreeMap<>();
        try (Statement select = db.createStatement();
                ResultSet rows = select.executeQuery("SELECT " + columns() + " FROM " + table)) {
            while (rows.next()) {
                char[] record = " ".repeat(recordLength).toCharArray();
                for (int i = 0; i < fields.size(); i++) {
                    String value = rows.getString(i + 1);
                    value.getChars(0, value.length(), record, fields.get(i).offset());
                }
                String text = new String(record);
                byKey.put(key(text), text);
            }
        }
        return byKey;
    }

    /** The harness's {@code files} entry for this dataset, empty when nothing changed. */
    Optional<Map<String, Object>> diff(TreeMap<String, String> before, TreeMap<String, String> after) {
        List<Map<String, String>> inserted = new ArrayList<>();
        List<Map<String, Object>> updated = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            String old = before.get(key);
            String now = after.get(key);
            if (old == null) {
                inserted.add(decode(now));
            } else if (now == null) {
                deleted.add(key.stripTrailing());
            } else if (!old.equals(now)) {
                Map<String, String> was = decode(old);
                Map<String, List<String>> changed = new LinkedHashMap<>();
                decode(now).forEach((field, value) -> {
                    if (!value.equals(was.get(field))) {
                        changed.put(field, List.of(was.get(field), value));
                    }
                });
                updated.add(Map.of("key", key.stripTrailing(), "changed", changed));
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

    private List<String> seedRecords() throws IOException {
        List<String> records = new ArrayList<>();
        for (String line : new String(Files.readAllBytes(seed), ISO_8859_1).split("\n", -1)) {
            String record = line.replaceAll("\r+$", "");
            if (!record.isBlank()) {
                records.add(pad(record.length() > recordLength ? record.substring(0, recordLength) : record, recordLength));
            }
        }
        return records;
    }

    private List<String> encodeAll(JsonNode replace) {
        List<String> records = new ArrayList<>();
        replace.forEach(r -> records.add(encode(r, null)));
        return records;
    }

    private String encode(JsonNode values, String base) {
        char[] record = (base == null ? " ".repeat(recordLength) : base).toCharArray();
        values.fields().forEachRemaining(entry -> {
            Field field = fields.stream()
                    .filter(f -> f.cobolName().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(name + " has no field " + entry.getKey()));
            String value = entry.getValue().asText();
            String moved = pad(value.length() > field.length() ? value.substring(0, field.length()) : value, field.length());
            moved.getChars(0, field.length(), record, field.offset());
        });
        return new String(record);
    }

    private Map<String, String> decode(String record) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Field field : fields) {
            if (!field.cobolName().contains("FILLER")) {
                values.put(field.cobolName(), slice(record, field).stripTrailing());
            }
        }
        return values;
    }

    private void write(Connection db, Iterable<String> records) throws SQLException {
        String placeholders = String.join(",", fields.stream().map(f -> "?").toList());
        try (Statement truncate = db.createStatement();
                PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO " + table + " (" + columns() + ") VALUES (" + placeholders + ")")) {
            truncate.execute("TRUNCATE " + table);
            for (String record : records) {
                for (int i = 0; i < fields.size(); i++) {
                    insert.setString(i + 1, slice(record, fields.get(i)).stripTrailing());
                }
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private String columns() {
        return String.join(", ", fields.stream().map(f -> Names.column(f.cobolName())).toList());
    }

    private String key(String record) {
        return record.substring(keyOffset, keyOffset + keyLength);
    }

    private static String slice(String record, Field field) {
        return record.substring(field.offset(), field.offset() + field.length());
    }

    private static String pad(String text, int length) {
        return text.length() >= length ? text : text + " ".repeat(length - text.length());
    }
}
