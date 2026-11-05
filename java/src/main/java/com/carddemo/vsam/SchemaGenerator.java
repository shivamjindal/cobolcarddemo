package com.carddemo.vsam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class SchemaGenerator {

    private SchemaGenerator() {}

    public record Generated(List<TableDef> tables, String sql, String markdown) {}

    public static Generated generate(Path appRoot) throws IOException {
        Catalog catalog = new ListcatParser().parse(read(appRoot.resolve("catlg").resolve("LISTCAT.txt")));
        RecordCodec codec = new RecordCodec();
        List<Built> built = new ArrayList<>();
        for (Dataset dataset : Datasets.ALL) {
            built.add(build(appRoot, catalog, codec, dataset));
        }
        List<TableDef> tables = foreignKeys(built);
        List<Cluster> clusters = built.stream().map(Built::cluster).toList();
        List<List<String>> reasons = built.stream().map(Built::reasons).toList();
        return new Generated(List.copyOf(tables), Ddl.migration(tables, clusters), MappingDoc.render(tables, clusters, reasons));
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: SchemaGenerator <appRoot>");
            System.exit(2);
        }
        Generated generated = generate(Path.of(args[0]));
        Path sql = Path.of("src/main/resources/db/migration/V1__vsam_ksds_tables.sql");
        Path markdown = Path.of("docs/schema-mapping.md");
        Files.createDirectories(sql.getParent());
        Files.createDirectories(markdown.getParent());
        Files.writeString(sql, generated.sql());
        Files.writeString(markdown, generated.markdown());
    }

    private static Built build(Path appRoot, Catalog catalog, RecordCodec codec, Dataset dataset) throws IOException {
        Cluster cluster = cluster(catalog, dataset);
        Layout layout = new CopybookParser().parse(read(appRoot.resolve("cpy").resolve(dataset.copybook() + ".cpy")));
        if (layout.recordLength() != cluster.recordLength()) {
            throw new IllegalArgumentException(dataset.table() + " copybook length " + layout.recordLength()
                    + " != " + cluster.dsn() + " MAXLRECL " + cluster.recordLength());
        }
        List<byte[]> records = Records.read(Records.dataFile(appRoot, dataset), layout.recordLength());
        String where = dataset.table() + "/" + layout.recordName();
        List<Column> columns = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (Field field : layout.fields()) {
            List<Object> observed = new ArrayList<>();
            for (byte[] record : records) {
                observed.add(codec.decodeRaw(record, field, where));
            }
            SqlTypes.Mapped mapped = SqlTypes.map(field, observed);
            columns.add(new Column(field, Names.column(field.cobolName()), mapped.type()));
            reasons.add(mapped.reason());
        }
        List<Column> primaryKey = Keys.cover(layout, cluster.key()).stream()
                .map(field -> column(columns, field))
                .toList();
        List<IndexDef> indexes = new ArrayList<>();
        catalog.indexes().stream()
                .filter(index -> index.clusterDsn().equals(cluster.dsn()))
                .sorted(Comparator.comparing(AlternateIndex::dsn))
                .forEach(index -> {
                    List<Column> key = Keys.cover(layout, index.key()).stream()
                            .map(field -> column(columns, field))
                            .toList();
                    String name = dataset.table() + "_"
                            + key.stream().map(Column::name).collect(Collectors.joining("_")) + "_aix";
                    indexes.add(new IndexDef(name, key, index.unique()));
                });
        List<String> notes = new ArrayList<>();
        for (Field filler : layout.fillers()) {
            notes.add("FILLER offset " + filler.offset() + " length " + filler.length() + " is not stored");
        }
        TableDef table = new TableDef(dataset, layout, columns, primaryKey, indexes, List.of(), notes);
        List<Object[]> rows = new ArrayList<>();
        Set<List<Object>> seen = new HashSet<>();
        for (byte[] record : records) {
            Object[] row = codec.decode(record, table);
            if (!seen.add(values(row, columns, primaryKey))) {
                throw new IllegalArgumentException(dataset.table() + " has a duplicate primary key");
            }
            rows.add(row);
        }
        return new Built(table, cluster, rows, List.copyOf(reasons));
    }

    private static List<TableDef> foreignKeys(List<Built> built) {
        List<ForeignKeyDef> none = List.of();
        List<List<ForeignKeyDef>> keys = new ArrayList<>();
        List<List<String>> extraNotes = new ArrayList<>();
        for (int i = 0; i < built.size(); i++) {
            keys.add(new ArrayList<>());
            extraNotes.add(new ArrayList<>());
        }
        for (ForeignKeyCandidate candidate : Datasets.FOREIGN_KEYS) {
            int childIndex = indexOf(built, candidate.childTable());
            int parentIndex = indexOf(built, candidate.parentTable());
            Built child = built.get(childIndex);
            Built parent = built.get(parentIndex);
            List<Column> childColumns = candidate.childColumns().stream()
                    .map(name -> columnNamed(child.table.columns(), candidate.childTable(), name))
                    .toList();
            if (childColumns.size() != parent.table.primaryKey().size()) {
                throw new IllegalArgumentException(candidate.childTable() + " -> " + candidate.parentTable()
                        + " does not match the parent key");
            }
            Set<List<Object>> parentKeys = new HashSet<>();
            for (Object[] row : parent.rows) {
                parentKeys.add(values(row, parent.table.columns(), parent.table.primaryKey()));
            }
            List<List<Object>> orphans = new ArrayList<>();
            for (Object[] row : child.rows) {
                List<Object> key = values(row, child.table.columns(), childColumns);
                if (!parentKeys.contains(key)) {
                    orphans.add(key);
                }
            }
            if (orphans.isEmpty()) {
                String name = candidate.childTable() + "_" + String.join("_", candidate.childColumns()) + "_fk";
                keys.get(childIndex).add(new ForeignKeyDef(
                        name,
                        childColumns,
                        candidate.parentTable(),
                        parent.table.primaryKey().stream().map(Column::name).toList()));
            } else {
                String examples = orphans.stream().limit(3).map(SchemaGenerator::tuple).collect(Collectors.joining(", "));
                extraNotes.get(childIndex).add("FK to " + candidate.parentTable() + " skipped: " + orphans.size()
                        + " orphan rows, e.g. " + examples);
            }
        }
        List<TableDef> tables = new ArrayList<>();
        for (int i = 0; i < built.size(); i++) {
            TableDef table = built.get(i).table;
            List<String> notes = new ArrayList<>(table.notes());
            notes.addAll(extraNotes.get(i));
            tables.add(new TableDef(
                    table.dataset(),
                    table.layout(),
                    table.columns(),
                    table.primaryKey(),
                    table.indexes(),
                    keys.get(i).isEmpty() ? none : List.copyOf(keys.get(i)),
                    notes));
        }
        return tables;
    }

    private static Cluster cluster(Catalog catalog, Dataset dataset) {
        String suffix = "." + dataset.clusterDsn() + ".VSAM.KSDS";
        List<Cluster> found = catalog.clusters().stream()
                .filter(item -> item.dsn().endsWith(suffix))
                .toList();
        if (found.size() != 1) {
            throw new IllegalArgumentException(dataset.table() + " matched " + found.size() + " clusters for " + suffix);
        }
        return found.get(0);
    }

    private static Column column(List<Column> columns, Field field) {
        return columns.stream()
                .filter(item -> item.field().equals(field))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no column for " + field.cobolName()));
    }

    private static Column columnNamed(List<Column> columns, String table, String name) {
        return columns.stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(table + " has no column " + name));
    }

    private static int indexOf(List<Built> built, String table) {
        for (int i = 0; i < built.size(); i++) {
            if (built.get(i).table.dataset().table().equals(table)) {
                return i;
            }
        }
        throw new IllegalArgumentException("no dataset " + table);
    }

    private static List<Object> values(Object[] row, List<Column> columns, List<Column> selected) {
        List<Object> key = new ArrayList<>();
        for (Column wanted : selected) {
            int index = columns.indexOf(wanted);
            if (index < 0) {
                throw new IllegalArgumentException("missing column " + wanted.name());
            }
            key.add(row[index]);
        }
        return List.copyOf(key);
    }

    private static String tuple(List<Object> key) {
        return key.stream().map(String::valueOf).collect(Collectors.joining(", ", "(", ")"));
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.ISO_8859_1);
    }

    private record Built(TableDef table, Cluster cluster, List<Object[]> rows, List<String> reasons) {}
}
