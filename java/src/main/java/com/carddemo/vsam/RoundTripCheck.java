package com.carddemo.vsam;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RoundTripCheck {

    private static final byte EBCDIC_SPACE = 0x40;

    private final JdbcTemplate jdbc;
    private final RecordCodec codec = new RecordCodec();

    public RoundTripCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Report check(Path appRoot, List<TableDef> tables) throws IOException {
        List<TableResult> results = new ArrayList<>();
        List<FillerNote> fillers = new ArrayList<>();
        for (TableDef table : tables) {
            List<byte[]> records = Records.read(Records.dataFile(appRoot, table.dataset()), table.layout().recordLength());
            results.add(compare(table, records));
            fillers.addAll(fillers(table, records));
        }
        int fieldMismatches = results.stream().mapToInt(result -> result.mismatches().size()).sum();
        int countMismatches = (int) results.stream()
                .filter(result -> result.sourceCount() != result.loadedCount())
                .count();
        return new Report(List.copyOf(results), List.copyOf(fillers), fieldMismatches, countMismatches);
    }

    private TableResult compare(TableDef table, List<byte[]> records) {
        int keyOffset = table.primaryKey().get(0).field().offset();
        int keyLength = table.primaryKey().stream().mapToInt(column -> column.field().length()).sum();
        Map<ByteKey, byte[]> byKey = new HashMap<>();
        for (byte[] record : records) {
            byte[] key = Arrays.copyOfRange(record, keyOffset, keyOffset + keyLength);
            if (byKey.put(new ByteKey(key), record) != null) {
                throw new IllegalArgumentException(table.dataset().table() + " source has a duplicate primary key");
            }
        }
        String columns = table.columns().stream().map(Column::name).collect(Collectors.joining(", "));
        List<Object[]> loaded = jdbc.query(
                "SELECT " + columns + " FROM " + table.dataset().table(),
                (rs, row) -> readRow(rs, table));
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table.dataset().table(), Long.class);
        if (count == null || count != loaded.size()) {
            throw new IllegalStateException(table.dataset().table() + " count " + count + " != " + loaded.size());
        }
        List<FieldMismatch> mismatches = new ArrayList<>();
        Set<ByteKey> seen = new HashSet<>();
        for (Object[] row : loaded) {
            byte[] encoded = codec.encode(row, table);
            ByteKey key = new ByteKey(Arrays.copyOfRange(encoded, keyOffset, keyOffset + keyLength));
            String keyText = keyText(table, row);
            if (!seen.add(key)) {
                mismatches.add(new FieldMismatch(table.dataset().table(), keyText, "(record)", "one row", "duplicate loaded key"));
                continue;
            }
            byte[] source = byKey.get(key);
            if (source == null) {
                mismatches.add(new FieldMismatch(
                        table.dataset().table(), keyText, "(record)", "source row", "no source row"));
                continue;
            }
            for (Column column : table.columns()) {
                Field field = column.field();
                byte[] expected = Arrays.copyOfRange(source, field.offset(), field.offset() + field.length());
                byte[] actual = Arrays.copyOfRange(encoded, field.offset(), field.offset() + field.length());
                if (!Arrays.equals(expected, actual)) {
                    mismatches.add(new FieldMismatch(
                            table.dataset().table(), keyText, field.cobolName(), hex(expected), hex(actual)));
                }
            }
        }
        return new TableResult(table.dataset().table(), records.size(), count.intValue(), List.copyOf(mismatches));
    }

    private static List<FillerNote> fillers(TableDef table, List<byte[]> records) {
        List<FillerNote> notes = new ArrayList<>();
        for (Field filler : table.layout().fillers()) {
            Map<String, Integer> patterns = new LinkedHashMap<>();
            int count = 0;
            for (byte[] record : records) {
                byte[] slice = Arrays.copyOfRange(record, filler.offset(), filler.offset() + filler.length());
                if (isSpaces(slice)) {
                    continue;
                }
                count++;
                patterns.merge(new String(slice, RecordCodec.EBCDIC), 1, Integer::sum);
            }
            if (count == 0) {
                continue;
            }
            String example = patterns.keySet().iterator().next();
            if (patterns.size() > 1) {
                example = example + " (+" + (patterns.size() - 1) + " more)";
            }
            notes.add(new FillerNote(
                    table.dataset().table(), table.dataset().clusterDsn(), filler.offset(), filler.length(), count, example));
        }
        return notes;
    }

    private static Object[] readRow(ResultSet rs, TableDef table) throws SQLException {
        Object[] row = new Object[table.columns().size()];
        for (int i = 0; i < row.length; i++) {
            row[i] = read(rs, i + 1, table.columns().get(i));
        }
        return row;
    }

    private static Object read(ResultSet rs, int index, Column column) throws SQLException {
        Object value = switch (column.type()) {
            case SqlType.Text ignored -> rs.getString(index);
            case SqlType.Int ignored -> rs.getObject(index, Integer.class);
            case SqlType.BigInt ignored -> rs.getObject(index, Long.class);
            case SqlType.Numeric ignored -> rs.getBigDecimal(index);
            case SqlType.Date ignored -> rs.getObject(index, LocalDate.class);
            case SqlType.Timestamp ignored -> rs.getObject(index, LocalDateTime.class);
        };
        if (value == null) {
            throw new IllegalArgumentException(column.name() + " is null");
        }
        return value;
    }

    private static String keyText(TableDef table, Object[] row) {
        List<String> parts = new ArrayList<>();
        for (Column key : table.primaryKey()) {
            int index = table.columns().indexOf(key);
            parts.add(key.name() + "=" + row[index]);
        }
        return String.join(",", parts);
    }

    private static boolean isSpaces(byte[] bytes) {
        for (byte value : bytes) {
            if (value != EBCDIC_SPACE) {
                return false;
            }
        }
        return true;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(Character.forDigit((value >> 4) & 0xF, 16));
            text.append(Character.forDigit(value & 0xF, 16));
        }
        return text.toString();
    }

    public record Report(List<TableResult> tables, List<FillerNote> fillers, int fieldMismatches, int countMismatches) {

        public boolean ok() {
            return fieldMismatches == 0 && countMismatches == 0;
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder();
            for (TableResult table : tables) {
                text.append(table.dataset())
                        .append(": source ").append(table.sourceCount())
                        .append(", loaded ").append(table.loadedCount())
                        .append(", field mismatches ").append(table.mismatches().size())
                        .append('\n');
                for (FieldMismatch mismatch : table.mismatches()) {
                    text.append("  ").append(mismatch.dataset())
                            .append(' ').append(mismatch.key())
                            .append(' ').append(mismatch.field())
                            .append(" expected ").append(mismatch.expected())
                            .append(" actual ").append(mismatch.actual())
                            .append('\n');
                }
            }
            text.append("field mismatches: ").append(fieldMismatches).append('\n');
            text.append("count mismatches: ").append(countMismatches).append('\n');
            text.append("fillers:\n");
            if (fillers.isEmpty()) {
                text.append("  none\n");
            }
            for (FillerNote filler : fillers) {
                text.append("  ").append(filler.dataset())
                        .append(' ').append(filler.cluster())
                        .append(" offset ").append(filler.offset())
                        .append(" length ").append(filler.length())
                        .append(" records ").append(filler.records())
                        .append(" example '").append(filler.example()).append("'\n");
            }
            return text.toString();
        }
    }

    public record TableResult(String dataset, int sourceCount, int loadedCount, List<FieldMismatch> mismatches) {}

    public record FieldMismatch(String dataset, String key, String field, String expected, String actual) {}

    public record FillerNote(String dataset, String cluster, int offset, int length, int records, String example) {}

    private record ByteKey(byte[] data) {

        private ByteKey {
            data = data.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ByteKey key && Arrays.equals(data, key.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }
}
