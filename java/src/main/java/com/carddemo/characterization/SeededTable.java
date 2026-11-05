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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import com.carddemo.records.Fields;
import com.carddemo.records.KeyedTable;
import com.carddemo.records.RecordImage;
import com.carddemo.vsam.Column;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One KSDS of the harness registry held in its Postgres table. Seeding, snapshots and diffs follow
 * {@code characterization/harness/datasets.py}: records are keyed by their latin-1 key characters, and the
 * diff decodes fields the way the harness does, so the two compare equal.
 */
final class SeededTable {

    /** Every keyed dataset of the registry. */
    static final List<String> SEEDED = List.of("USRSEC", "ACCTDATA", "CUSTDATA", "CARDDATA", "CARDXREF", "TRANSACT",
            "DISCGRP", "TCATBALF", "TRANCATG", "TRANTYPE");

    private final String name;
    private final KeyedTable table;
    private final HarnessLayout layout;
    private final Path seed;

    private SeededTable(String name, KeyedTable table, HarnessLayout layout, Path seed) {
        this.name = name;
        this.table = table;
        this.layout = layout;
        this.seed = seed;
    }

    static SeededTable of(JsonNode registry, Path repo, String name) throws IOException {
        KeyedTable table = KeyedTable.valueOf(name);
        JsonNode entry = registry.path("datasets").path(name);
        if (entry.get("reclen").asInt() != table.recordLength() || entry.get("key").get(0).asInt() != table.key().offset()
                || entry.get("key").get(1).asInt() != table.key().length()) {
            throw new IllegalArgumentException(name + " in the registry does not match " + table.dsn());
        }
        return new SeededTable(name, table, HarnessLayout.of(repo, registry, name), repo.resolve(entry.get("source").asText()));
    }

    static List<SeededTable> all(JsonNode registry, Path repo) throws IOException {
        List<SeededTable> tables = new ArrayList<>();
        for (String name : SEEDED) {
            tables.add(of(registry, repo, name));
        }
        return tables;
    }

    /**
     * Every table from its seed and the case's edits, with no job queued, claimed or spooled and no data set
     * cataloged, in one transaction. A case may delete a record others refer to, as the harness's text files
     * allow, so foreign-key triggers are off while seeding, and with them the cascades.
     */
    static void seed(Connection db, List<SeededTable> tables, JsonNode setup) throws IOException, SQLException {
        db.setAutoCommit(false);
        try (Statement replica = db.createStatement()) {
            replica.execute("SET LOCAL session_replication_role = replica");
            for (String batchState : List.of("job_submission_runs", "job_submissions", "spool", "dataset_records", "datasets")) {
                replica.execute("DELETE FROM " + batchState);
            }
        }
        for (SeededTable table : tables) {
            table.materialize(db, setup.path(table.name()));
        }
        db.commit();
        db.setAutoCommit(true);
    }

    String name() {
        return name;
    }

    KeyedTable table() {
        return table;
    }

    HarnessLayout layout() {
        return layout;
    }

    /** The seed records, then the case's {@code replace}, {@code upsert} and {@code delete} edits. */
    void materialize(Connection db, JsonNode edit) throws IOException, SQLException {
        List<byte[]> records = new ArrayList<>();
        if (edit.has("replace")) {
            edit.get("replace").forEach(r -> records.add(layout.encode(r, null)));
        } else {
            records.addAll(seedRecords(seed, table.recordLength()));
        }
        TreeMap<String, byte[]> byKey = new TreeMap<>();
        records.forEach(r -> byKey.put(key(r), r));
        for (JsonNode upsert : edit.path("upsert")) {
            byte[] fresh = layout.encode(upsert, null);
            byte[] base = byKey.get(key(fresh));
            byKey.put(key(fresh), base == null ? fresh : layout.encode(upsert, base));
        }
        for (JsonNode delete : edit.path("delete")) {
            byte[] key = Fields.spaces(table.key().length());
            Fields.text(key, 0, key.length, delete.asText());
            byKey.remove(new String(key, ISO_8859_1));
        }
        write(db, byKey.values());
    }

    /** Record by key, as the harness snapshots a {@code .dat} file. */
    TreeMap<String, byte[]> snapshot(Connection db) throws SQLException {
        TreeMap<String, byte[]> byKey = new TreeMap<>();
        try (Statement select = db.createStatement();
                ResultSet rows = select.executeQuery("SELECT " + columns() + " FROM " + table.table())) {
            while (rows.next()) {
                byte[] record = RecordImage.of(table, rows);
                byKey.put(key(record), record);
            }
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
                deleted.add(Python.rstrip(key));
            } else if (!Arrays.equals(old, now)) {
                Map<String, String> was = layout.decode(old);
                Map<String, List<String>> changed = new LinkedHashMap<>();
                layout.decode(now).forEach((field, value) -> {
                    if (!value.equals(was.get(field))) {
                        changed.put(field, List.of(was.get(field), value));
                    }
                });
                updated.add(Map.of("key", Python.rstrip(key), "changed", changed));
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

    private void write(Connection db, Iterable<byte[]> records) throws SQLException {
        String placeholders = String.join(",", table.columns().stream().map(c -> "?").toList());
        try (Statement delete = db.createStatement();
                PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO " + table.table() + " (" + columns() + ") VALUES (" + placeholders + ")")) {
            delete.execute("DELETE FROM " + table.table());
            for (byte[] record : records) {
                Object[] values = RecordImage.columns(table, record);
                for (int i = 0; i < values.length; i++) {
                    insert.setObject(i + 1, values[i]);
                }
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private String columns() {
        return String.join(", ", table.columns().stream().map(Column::name).toList());
    }

    private String key(byte[] record) {
        return new String(record, table.key().offset(), table.key().length(), ISO_8859_1);
    }
}
