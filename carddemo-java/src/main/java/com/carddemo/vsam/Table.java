package com.carddemo.vsam;

import com.carddemo.copybook.Copybook;
import com.carddemo.copybook.Field;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/** A VSAM dataset's Postgres table, derived from its copybook. FILLER fields are dropped. */
public record Table(VsamDataset dataset, List<Column> columns, List<Column> primaryKey,
                    List<List<Column>> alternateIndexes) {

    public static Table of(VsamDataset dataset) {
        Copybook copybook = Copybook.parse(readCopybook(dataset.copybook));
        if (copybook.length() != dataset.recordLength) {
            throw new IllegalStateException(dataset.copybook + " is " + copybook.length()
                    + " bytes but " + dataset.cluster + " LRECL is " + dataset.recordLength);
        }
        List<Column> columns = copybook.fields().stream()
                .filter(f -> !f.isFiller())
                .map(f -> Column.of(f, dataset.kindOverrides.get(f.name())))
                .toList();
        dataset.kindOverrides.keySet().forEach(copybook::field);
        return new Table(dataset, columns,
                columnsCovering(columns, dataset.primaryKey),
                dataset.alternateIndexes.stream().map(r -> columnsCovering(columns, r)).toList());
    }

    public String name() {
        return dataset.table;
    }

    public String ddl() {
        StringBuilder sql = new StringBuilder();
        sql.append("-- ").append(dataset.cluster).append(" (").append(dataset.copybook).append(")\n");
        sql.append("CREATE TABLE ").append(name()).append(" (\n");
        for (Column column : columns) {
            sql.append("    ").append(column.ddl()).append(",\n");
        }
        sql.append("    PRIMARY KEY (").append(names(primaryKey)).append(")\n);\n");
        for (List<Column> index : alternateIndexes) {
            String indexName = name() + "_" + index.stream().map(Column::name).collect(Collectors.joining("_")) + "_idx";
            sql.append("CREATE INDEX ").append(indexName).append(" ON ").append(name())
                    .append(" (").append(names(index)).append(");\n");
        }
        return sql.toString();
    }

    public String insertSql() {
        return "INSERT INTO " + name() + " (" + names(columns) + ") VALUES ("
                + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
    }

    public int columnIndex(String name) {
        return columns.indexOf(columns.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow());
    }

    public Object[] decode(String record) {
        return columns.stream().map(c -> c.decode(record)).toArray();
    }

    /** The record {@code row} (in column order) came from; FILLER is spaces. */
    public String encode(Object[] row) {
        StringBuilder record = new StringBuilder(" ".repeat(dataset.recordLength));
        for (int i = 0; i < columns.size(); i++) {
            Field field = columns.get(i).field();
            record.replace(field.offset(), field.end(), columns.get(i).encode(row[i]));
        }
        return record.toString();
    }

    /** IDCAMS REPRO of the cluster to a flat file: every record, in primary key byte order. */
    public List<String> unload(JdbcTemplate jdbc) {
        String order = primaryKey.stream()
                .map(c -> c.kind() == ColumnKind.TEXT ? c.name() + " COLLATE \"C\"" : c.name())
                .collect(Collectors.joining(", "));
        return jdbc.query("SELECT " + names(columns) + " FROM " + name() + " ORDER BY " + order, (rs, n) -> {
            Object[] row = new Object[columns.size()];
            for (int i = 0; i < row.length; i++) {
                row[i] = switch (columns.get(i).kind()) {
                    case TEXT -> rs.getString(i + 1);
                    case DATE -> rs.getObject(i + 1, LocalDate.class);
                    case TIMESTAMP -> rs.getObject(i + 1, LocalDateTime.class);
                    case INTEGER, DECIMAL -> rs.getBigDecimal(i + 1);
                };
            }
            return encode(row);
        });
    }

    public boolean isPlaceholder(String record) {
        return record.substring(dataset.primaryKey.offset(), dataset.primaryKey.end()).chars().allMatch(c -> c == 0);
    }

    private static String names(List<Column> columns) {
        return columns.stream().map(Column::name).collect(Collectors.joining(", "));
    }

    /** VSAM keys are byte ranges; they must line up exactly with whole copybook fields. */
    private static List<Column> columnsCovering(List<Column> columns, VsamDataset.ByteRange range) {
        List<Column> covered = columns.stream()
                .filter(c -> c.field().offset() >= range.offset() && c.field().end() <= range.end())
                .toList();
        int start = covered.isEmpty() ? -1 : covered.getFirst().field().offset();
        int end = covered.isEmpty() ? -1 : covered.getLast().field().end();
        int length = covered.stream().map(Column::field).mapToInt(Field::length).sum();
        if (start != range.offset() || end != range.end() || length != range.length()) {
            throw new IllegalStateException("Key " + range + " does not align with fields " + covered);
        }
        return covered;
    }

    private static String readCopybook(String name) {
        try (InputStream in = Table.class.getResourceAsStream("/copybooks/" + name + ".cpy")) {
            if (in == null) {
                throw new IllegalStateException("Copybook " + name + " not on classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
