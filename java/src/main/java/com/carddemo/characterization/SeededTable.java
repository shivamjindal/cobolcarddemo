package com.carddemo.characterization;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.carddemo.vsam.Column;
import com.carddemo.vsam.Field;
import com.carddemo.vsam.Pic;
import com.carddemo.vsam.SqlType;
import com.carddemo.vsam.SqlTypes;
import com.carddemo.vsam.TableDef;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One KSDS of the harness registry held in its Postgres table. Seeding, snapshots and diffs follow
 * {@code characterization/harness/datasets.py} and {@code copybook.py}: a record is its latin-1
 * characters, signed zoned digits carry the EBCDIC overpunch ({@code {A-I} positive, {@code }J-R}
 * negative), and a field is decoded the way the harness decodes it, so the diffs compare equal.
 * A NULL column is the field's LOW-VALUES, or SPACES for a numeric column (see V2 of the migration).
 * The unnamed FILLER is stored only where V2 added a column for it.
 */
final class SeededTable {

    private static final Map<String, String> FILLER_COLUMNS =
            Map.of("accounts", "acct_filler", "customers", "cust_filler", "cards", "card_filler");
    private static final Pattern ZONED = Pattern.compile("[0-9]*[0-9{}A-R]");
    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";

    /** A stored part of the record: a named column, or the FILLER column. */
    private record Part(Field field, String column, SqlType type) {}

    private final String name;
    private final String table;
    private final List<Field> fields;
    private final List<Part> parts;
    private final int recordLength;
    private final int keyOffset;
    private final int keyLength;
    private final Path seed;

    private SeededTable(String name, TableDef def, JsonNode entry, Path repo) {
        this.name = name;
        this.table = def.dataset().table();
        this.recordLength = entry.get("reclen").asInt();
        this.keyOffset = entry.get("key").get(0).asInt();
        this.keyLength = entry.get("key").get(1).asInt();
        this.seed = repo.resolve(entry.get("source").asText());
        List<Part> stored = new ArrayList<>();
        for (Column column : def.columns()) {
            stored.add(new Part(column.field(), column.name(), column.type()));
        }
        String fillerColumn = FILLER_COLUMNS.get(table);
        if (fillerColumn != null) {
            Field filler = def.layout().fillers().getLast();
            stored.add(new Part(filler, fillerColumn, new SqlType.Text(filler.length())));
        }
        this.parts = List.copyOf(stored);
        List<Field> all = new ArrayList<>(def.layout().fields());
        all.addAll(def.layout().fillers());
        all.sort((a, b) -> Integer.compare(a.offset(), b.offset()));
        this.fields = List.copyOf(all);
    }

    static SeededTable of(JsonNode registry, Path repo, String name, List<TableDef> tables) {
        TableDef def = tables.stream()
                .filter(t -> t.dataset().clusterDsn().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no table for dataset " + name));
        return new SeededTable(name, def, registry.path("datasets").path(name), repo);
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
                for (int i = 0; i < parts.size(); i++) {
                    Part part = parts.get(i);
                    String image = image(part, rows.getObject(i + 1));
                    image.getChars(0, image.length(), record, part.field().offset());
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

    /** {@code Layout.encode}: each named field's value moved into a copy of {@code base}, or of spaces. */
    private String encode(JsonNode values, String base) {
        char[] record = (base == null ? " ".repeat(recordLength) : base).toCharArray();
        values.fields().forEachRemaining(entry -> {
            Field field = fields.stream()
                    .filter(f -> f.cobolName().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(name + " has no field " + entry.getKey()));
            String moved = switch (field.pic()) {
                case Pic.Zoned zoned -> zoned(new BigDecimal(entry.getValue().asText()), zoned, field.length());
                default -> fit(entry.getValue().asText(), field.length());
            };
            moved.getChars(0, field.length(), record, field.offset());
        });
        return new String(record);
    }

    private Map<String, String> decode(String record) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Field field : fields) {
            if (!field.cobolName().contains("FILLER")) {
                values.put(field.cobolName(), harnessValue(field, slice(record, field)).stripTrailing());
            }
        }
        return values;
    }

    /** {@code copybook.decode_leaf}: zoned digits as a scaled decimal, anything else as its characters. */
    private static String harnessValue(Field field, String raw) {
        if (!(field.pic() instanceof Pic.Zoned zoned) || raw.replaceAll("[ \0]", "").isEmpty()
                || !ZONED.matcher(raw).matches()) {
            return raw;
        }
        BigDecimal value = new BigDecimal(unpunch(raw), zoned.scale());
        return value.setScale(zoned.scale()).toPlainString();
    }

    private void write(Connection db, Iterable<String> records) throws SQLException {
        String placeholders = String.join(",", parts.stream().map(p -> "?").toList());
        try (Statement delete = db.createStatement();
                PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO " + table + " (" + columns() + ") VALUES (" + placeholders + ")")) {
            delete.execute("DELETE FROM " + table);
            for (String record : records) {
                for (int i = 0; i < parts.size(); i++) {
                    insert.setObject(i + 1, column(parts.get(i), slice(record, parts.get(i).field())));
                }
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** The column value for a field's characters. */
    private static Object column(Part part, String raw) {
        if (part.field().pic() instanceof Pic.Alnum) {
            if (raw.chars().allMatch(c -> c == 0)) {
                return null;
            }
            String text = raw.stripTrailing();
            return part.type() instanceof SqlType.Date ? LocalDate.parse(text, SqlTypes.DATE) : text;
        }
        Pic.Zoned zoned = (Pic.Zoned) part.field().pic();
        if (raw.isBlank()) {
            return null;
        }
        if (!ZONED.matcher(raw).matches()) {
            throw new IllegalArgumentException(part.column() + " holds '" + raw + "', which is not zoned decimal");
        }
        BigDecimal value = new BigDecimal(unpunch(raw), zoned.scale());
        return switch (part.type()) {
            case SqlType.Int ignored -> value.intValueExact();
            case SqlType.BigInt ignored -> value.longValueExact();
            default -> value;
        };
    }

    /** A column value back as the field's characters. */
    private static String image(Part part, Object value) {
        Field field = part.field();
        if (value == null) {
            return field.pic() instanceof Pic.Alnum ? "\0".repeat(field.length()) : " ".repeat(field.length());
        }
        return switch (field.pic()) {
            case Pic.Zoned zoned -> zoned(new BigDecimal(value.toString()), zoned, field.length());
            default -> fit(value instanceof java.sql.Date date ? date.toLocalDate().format(SqlTypes.DATE) : value.toString(),
                    field.length());
        };
    }

    private static BigInteger unpunch(String raw) {
        char last = raw.charAt(raw.length() - 1);
        int positive = POSITIVE.indexOf(last);
        int negative = NEGATIVE.indexOf(last);
        String digits = raw.substring(0, raw.length() - 1)
                + (positive >= 0 ? (char) ('0' + positive) : negative >= 0 ? (char) ('0' + negative) : last);
        BigInteger unscaled = new BigInteger(digits);
        return negative >= 0 ? unscaled.negate() : unscaled;
    }

    /** {@code copybook.encode_leaf} for DISPLAY numerics. */
    private static String zoned(BigDecimal value, Pic.Zoned zoned, int length) {
        BigInteger unscaled = value.movePointRight(zoned.scale()).toBigInteger();
        String digits = pad0(unscaled.abs().toString(), length);
        if (!zoned.signed()) {
            return digits;
        }
        String table = unscaled.signum() < 0 ? NEGATIVE : POSITIVE;
        return digits.substring(0, length - 1) + table.charAt(digits.charAt(length - 1) - '0');
    }

    private String columns() {
        return String.join(", ", parts.stream().map(Part::column).toList());
    }

    private String key(String record) {
        return record.substring(keyOffset, keyOffset + keyLength);
    }

    private static String slice(String record, Field field) {
        return record.substring(field.offset(), field.offset() + field.length());
    }

    private static String fit(String text, int length) {
        return pad(text.length() > length ? text.substring(0, length) : text, length);
    }

    private static String pad(String text, int length) {
        return text.length() >= length ? text : text + " ".repeat(length - text.length());
    }

    private static String pad0(String digits, int length) {
        String padded = digits.length() >= length ? digits : "0".repeat(length - digits.length()) + digits;
        return padded.substring(padded.length() - length);
    }
}
