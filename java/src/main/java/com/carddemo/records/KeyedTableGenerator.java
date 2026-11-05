package com.carddemo.records;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.carddemo.vsam.AlternateIndex;
import com.carddemo.vsam.Catalog;
import com.carddemo.vsam.Cluster;
import com.carddemo.vsam.Column;
import com.carddemo.vsam.Field;
import com.carddemo.vsam.ListcatParser;
import com.carddemo.vsam.Pic;
import com.carddemo.vsam.SchemaGenerator;
import com.carddemo.vsam.SqlType;
import com.carddemo.vsam.TableDef;

/**
 * Writes {@code KeyedTable}: every stored column of the ten V1 tables at its copybook offset, from the same
 * {@link SchemaGenerator} run that wrote V1, plus the FILLER columns V2 added. The application has no copybooks
 * at run time, so this is how a row becomes the record a batch program reads.
 */
public final class KeyedTableGenerator {

    public static final Path OUTPUT = Path.of("src/main/java/com/carddemo/records/KeyedTable.java");

    private static final Map<String, String> FILLER_COLUMNS = Map.of(
            "accounts", "acct_filler", "customers", "cust_filler", "cards", "card_filler",
            "transaction_category_balances", "tran_cat_filler");
    private static final Map<String, SqlType> V3_TYPES = Map.of("tran_orig_ts", new SqlType.Text(26));

    private KeyedTableGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: KeyedTableGenerator <appRoot>");
            System.exit(2);
        }
        Files.writeString(OUTPUT, generate(Path.of(args[0])), StandardCharsets.UTF_8);
    }

    public static String generate(Path appRoot) throws IOException {
        Catalog catalog = new ListcatParser().parse(
                Files.readString(appRoot.resolve("catlg").resolve("LISTCAT.txt"), ISO_8859_1));
        List<String> constants = new ArrayList<>();
        for (TableDef table : SchemaGenerator.generate(appRoot).tables()) {
            constants.add(constant(table, catalog));
        }
        return HEADER + String.join(",\n", constants) + ";\n" + BODY;
    }

    private static String constant(TableDef table, Catalog catalog) {
        String suffix = "." + table.dataset().clusterDsn() + ".VSAM.KSDS";
        Cluster cluster = catalog.clusters().stream()
                .filter(c -> c.dsn().endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no cluster for " + table.dataset().table()));
        List<AlternateIndex> indexes = catalog.indexes().stream()
                .filter(index -> index.clusterDsn().equals(cluster.dsn()))
                .toList();
        if (indexes.size() > 1 || indexes.size() != table.indexes().size()
                || table.indexes().stream().anyMatch(index -> index.columns().size() != 1)) {
            throw new IllegalArgumentException(table.dataset().table() + " needs one single-column alternate index at most");
        }
        List<Column> columns = new ArrayList<>(table.columns().stream()
                .map(c -> new Column(c.field(), c.name(), V3_TYPES.getOrDefault(c.name(), c.type())))
                .toList());
        String filler = FILLER_COLUMNS.get(table.dataset().table());
        if (filler != null) {
            Field field = table.layout().fillers().getLast();
            columns.add(new Column(field, filler, new SqlType.Text(field.length())));
        }
        String alternatePath = indexes.isEmpty() ? "null" : quote(indexes.getFirst().dsn() + ".PATH");
        String alternateColumn = table.indexes().isEmpty()
                ? "null"
                : quote(table.indexes().getFirst().columns().getFirst().name());
        return "    %s(%s, %s, %d, new KeyRange(%d, %d), %s, %s, List.of(\n%s))".formatted(
                table.dataset().clusterDsn(), quote(table.dataset().table()), quote(cluster.dsn()),
                cluster.recordLength(), cluster.key().offset(), cluster.key().length(), alternatePath, alternateColumn,
                columns.stream().map(KeyedTableGenerator::column).collect(Collectors.joining(",\n")));
    }

    private static String column(Column column) {
        Field field = column.field();
        return "            new Column(new Field(%s, %d, %d, %s, %s), %s, %s)".formatted(
                quote(field.cobolName()), field.offset(), field.length(), pic(field.pic()), quote(field.picClause()),
                quote(column.name()), type(column.type()));
    }

    private static String pic(Pic pic) {
        return switch (pic) {
            case Pic.Alnum alnum -> "new Pic.Alnum(%d)".formatted(alnum.length());
            case Pic.Zoned zoned -> "new Pic.Zoned(%d, %d, %b)".formatted(zoned.digits(), zoned.scale(), zoned.signed());
            case Pic.Packed packed -> "new Pic.Packed(%d, %d, %b)".formatted(packed.digits(), packed.scale(), packed.signed());
            case Pic.Binary binary -> "new Pic.Binary(%d, %d, %b)".formatted(binary.digits(), binary.scale(), binary.signed());
        };
    }

    private static String type(SqlType type) {
        return switch (type) {
            case SqlType.Text text -> "new SqlType.Text(%d)".formatted(text.length());
            case SqlType.Int number -> "new SqlType.Int(%d)".formatted(number.digits());
            case SqlType.BigInt number -> "new SqlType.BigInt(%d)".formatted(number.digits());
            case SqlType.Numeric number ->
                    "new SqlType.Numeric(%d, %d, %b)".formatted(number.precision(), number.scale(), number.signed());
            case SqlType.Date ignored -> "new SqlType.Date()";
            case SqlType.Timestamp ignored -> "new SqlType.Timestamp()";
        };
    }

    private static String quote(String text) {
        return "\"" + text + "\"";
    }

    private static final String HEADER = """
            // Generated by com.carddemo.records.KeyedTableGenerator from app/cpy, app/catlg and app/data. Do not edit.
            package com.carddemo.records;

            import java.util.Arrays;
            import java.util.List;
            import java.util.Optional;

            import com.carddemo.vsam.Column;
            import com.carddemo.vsam.Field;
            import com.carddemo.vsam.KeyRange;
            import com.carddemo.vsam.Pic;
            import com.carddemo.vsam.SqlType;

            /** The KSDS clusters held in V1 tables, each stored column at its offset in the cluster's record. */
            public enum KeyedTable {
            """;

    private static final String BODY = """

                private final String table;
                private final String dsn;
                private final int recordLength;
                private final KeyRange key;
                private final String alternatePath;
                private final String alternateColumn;
                private final List<Column> columns;

                KeyedTable(String table, String dsn, int recordLength, KeyRange key, String alternatePath,
                        String alternateColumn, List<Column> columns) {
                    this.table = table;
                    this.dsn = dsn;
                    this.recordLength = recordLength;
                    this.key = key;
                    this.alternatePath = alternatePath;
                    this.alternateColumn = alternateColumn;
                    this.columns = columns;
                }

                public String table() {
                    return table;
                }

                public String dsn() {
                    return dsn;
                }

                public int recordLength() {
                    return recordLength;
                }

                public KeyRange key() {
                    return key;
                }

                public List<Column> columns() {
                    return columns;
                }

                public List<Column> primaryKey() {
                    return columns.stream()
                            .filter(c -> c.field().offset() >= key.offset()
                                    && c.field().offset() + c.field().length() <= key.offset() + key.length())
                            .toList();
                }

                public Optional<Column> alternateKey() {
                    return columns.stream().filter(c -> c.name().equals(alternateColumn)).findFirst();
                }

                public Optional<String> alternatePath() {
                    return Optional.ofNullable(alternatePath);
                }

                public static Optional<KeyedTable> byDsn(String dsn) {
                    return Arrays.stream(values()).filter(t -> t.dsn.equals(dsn)).findFirst();
                }
            }
            """;
}
