package com.carddemo.online;

import com.carddemo.copybook.Pic;
import com.carddemo.users.Credentials;
import com.carddemo.vsam.Column;
import com.carddemo.vsam.Table;
import com.carddemo.vsam.VsamDataset;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * The tables the online programs use, seen as the CICS files of a spec: loaded from given.files, diffed
 * into file_effects, and failed on purpose for given.file_conditions. Records are keyed by copybook name
 * and compared as text: integers zero-padded to their PIC width, decimals at their scale, dates ISO, timestamps
 * as {@code yyyy-MM-dd HH:mm:ss.SSSSSS}.
 */
class Datasets {

    record Effect(String file, String op, Map<String, String> record) {
    }

    /**
     * A table whose spec records do not follow a VSAM copybook layout: an IMS segment or a Db2 table. Fields map
     * to columns by name and travel as Postgres text in both directions; a blank non-text field is NULL.
     */
    record Relation(String table, Map<String, String> columns, List<String> key) {

        /** Columns named after the fields, lower case with underscores. */
        static Relation of(String table, List<String> key, String... fields) {
            Map<String, String> columns = new LinkedHashMap<>();
            for (String field : fields) {
                columns.put(field, field.toLowerCase(Locale.ROOT).replace('-', '_'));
            }
            return new Relation(table, columns, key);
        }
    }

    /**
     * IMS segment and Db2 table names, as specs use them, to their tables. A PAUTDTL1 key comes from the clock, so
     * a detail is identified by its account and PA-TRANSACTION-ID. Records load in given.files order, so a spec lists
     * TRANSACTION_TYPE before TRANSACTION_TYPE_CATEGORY for the foreign key.
     */
    static final Map<String, Relation> RELATIONS = Map.of(
            "TRANSACTION_TYPE", new Relation("tran_types",
                    Map.of("TR_TYPE", "tran_type", "TR_DESCRIPTION", "tran_type_desc"), List.of("TR_TYPE")),
            "TRANSACTION_TYPE_CATEGORY", new Relation("tran_categories",
                    Map.of("TRC_TYPE_CODE", "tran_type_cd", "TRC_TYPE_CATEGORY", "tran_cat_cd",
                            "TRC_CAT_DATA", "tran_cat_type_desc"),
                    List.of("TRC_TYPE_CODE", "TRC_TYPE_CATEGORY")),
            "PAUTSUM0", Relation.of("pending_auth_summaries", List.of("PA-ACCT-ID"),
                    "PA-ACCT-ID", "PA-CUST-ID", "PA-AUTH-STATUS", "PA-ACCOUNT-STATUS", "PA-CREDIT-LIMIT",
                    "PA-CASH-LIMIT", "PA-CREDIT-BALANCE", "PA-CASH-BALANCE", "PA-APPROVED-AUTH-CNT",
                    "PA-DECLINED-AUTH-CNT", "PA-APPROVED-AUTH-AMT", "PA-DECLINED-AUTH-AMT"),
            "PAUTDTL1", Relation.of("pending_auth_details", List.of("PA-ACCT-ID", "PA-TRANSACTION-ID"),
                    "PA-ACCT-ID", "PA-AUTH-DATE-9C", "PA-AUTH-TIME-9C", "PA-AUTH-ORIG-DATE", "PA-AUTH-ORIG-TIME",
                    "PA-CARD-NUM", "PA-AUTH-TYPE", "PA-CARD-EXPIRY-DATE", "PA-MESSAGE-TYPE", "PA-MESSAGE-SOURCE",
                    "PA-AUTH-ID-CODE", "PA-AUTH-RESP-CODE", "PA-AUTH-RESP-REASON", "PA-PROCESSING-CODE",
                    "PA-TRANSACTION-AMT", "PA-APPROVED-AMT", "PA-MERCHANT-CATAGORY-CODE", "PA-ACQR-COUNTRY-CODE",
                    "PA-POS-ENTRY-MODE", "PA-MERCHANT-ID", "PA-MERCHANT-NAME", "PA-MERCHANT-CITY",
                    "PA-MERCHANT-STATE", "PA-MERCHANT-ZIP", "PA-TRANSACTION-ID", "PA-MATCH-STATUS", "PA-AUTH-FRAUD",
                    "PA-FRAUD-RPT-DATE"),
            "AUTHFRDS", Relation.of("authfrds", List.of("CARD_NUM", "AUTH_TS"),
                    "CARD_NUM", "AUTH_TS", "AUTH_TYPE", "CARD_EXPIRY_DATE", "MESSAGE_TYPE", "MESSAGE_SOURCE",
                    "AUTH_ID_CODE", "AUTH_RESP_CODE", "AUTH_RESP_REASON", "PROCESSING_CODE", "TRANSACTION_AMT",
                    "APPROVED_AMT", "MERCHANT_CATAGORY_CODE", "ACQR_COUNTRY_CODE", "POS_ENTRY_MODE", "MERCHANT_ID",
                    "MERCHANT_NAME", "MERCHANT_CITY", "MERCHANT_STATE", "MERCHANT_ZIP", "TRANSACTION_ID",
                    "MATCH_STATUS", "AUTH_FRAUD", "FRAUD_RPT_DATE", "ACCT_ID", "CUST_ID"));

    /** CSD file name to table; CXACAIX is the account-id alternate index over CCXREF. */
    static final Map<String, VsamDataset> FILES = Map.of("USRSEC", VsamDataset.USERS, "ACCTDAT", VsamDataset.ACCOUNTS,
            "CUSTDAT", VsamDataset.CUSTOMERS, "CARDDAT", VsamDataset.CARDS, "CCXREF", VsamDataset.CARD_XREF,
            "CXACAIX", VsamDataset.CARD_XREF, "TRANSACT", VsamDataset.TRANSACTIONS);

    private static final List<VsamDataset> TABLES = List.of(VsamDataset.USERS, VsamDataset.ACCOUNTS,
            VsamDataset.CUSTOMERS, VsamDataset.CARDS, VsamDataset.CARD_XREF, VsamDataset.TRANSACTIONS);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final Set<String> WRITE_OPS = Set.of("WRITE", "REWRITE", "DELETE");
    private static final Set<String> TEXT_TYPES = Set.of("bpchar", "varchar", "text");

    /** bcrypt is slow on purpose; the specs reuse a handful of passwords. */
    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;
    private final Map<VsamDataset, Table> tables = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> columnTypes = new ConcurrentHashMap<>();

    Datasets(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        TABLES.forEach(d -> tables.put(d, Table.of(d)));
    }

    /**
     * Every table holds exactly the records given for it; a table the case does not name is empty. A record given
     * under both CCXREF and CXACAIX is one record.
     */
    void reset(Map<String, List<Map<String, Object>>> files) {
        jdbc.execute("TRUNCATE " + Stream.concat(TABLES.stream().map(d -> d.table),
                RELATIONS.values().stream().map(Relation::table)).distinct().collect(Collectors.joining(", ")));
        Set<List<Object>> inserted = new HashSet<>();
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String file = entry.getKey();
            List<Map<String, Object>> records = entry.getValue();
            Relation relation = RELATIONS.get(file);
            if (relation != null) {
                records.forEach(record -> insert(file, relation, record));
                return;
            }
            Table table = tables.get(dataset(file));
            for (Map<String, Object> record : records) {
                Set<String> unknown = new HashSet<>(record.keySet());
                table.columns().forEach(c -> unknown.remove(c.field().name()));
                if (!unknown.isEmpty()) {
                    throw new IllegalArgumentException(file + " has no fields " + unknown);
                }
                List<Object> row = new ArrayList<>(List.of(table.name()));
                table.columns().forEach(c -> row.add(value(c, record.get(c.field().name()))));
                if (inserted.add(row)) {
                    jdbc.update(table.insertSql(), row.subList(1, row.size()).toArray());
                }
            }
        });
    }

    Map<String, Map<String, Map<String, String>>> snapshot() {
        Map<String, Map<String, Map<String, String>>> all = new TreeMap<>();
        tables.forEach((dataset, table) -> {
            Map<String, Map<String, String>> rows = new TreeMap<>();
            jdbc.query("SELECT * FROM " + table.name(), rs -> {
                Map<String, String> row = new LinkedHashMap<>();
                for (Column c : table.columns()) {
                    row.put(c.field().name(), column(c, rs.getObject(c.name())));
                }
                rows.put(table.primaryKey().stream().map(c -> row.get(c.field().name())).collect(Collectors.joining("|")), row);
            });
            all.put(file(dataset), rows);
        });
        RELATIONS.forEach((file, relation) -> {
            Map<String, Map<String, String>> rows = new TreeMap<>();
            String select = relation.columns().values().stream().map(c -> c + "::text").collect(Collectors.joining(", "));
            jdbc.query("SELECT " + select + " FROM " + relation.table(), rs -> {
                Map<String, String> row = new LinkedHashMap<>();
                int i = 1;
                for (String field : relation.columns().keySet()) {
                    String value = rs.getString(i++);
                    row.put(field, value == null ? "" : value);
                }
                rows.put(relation.key().stream().map(row::get).collect(Collectors.joining("|")), row);
            });
            all.put(file, rows);
        });
        return all;
    }

    private void insert(String file, Relation relation, Map<String, Object> record) {
        Set<String> unknown = new HashSet<>(record.keySet());
        unknown.removeAll(relation.columns().keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(file + " has no fields " + unknown);
        }
        Map<String, String> types = columnTypes.computeIfAbsent(relation.table(), t -> {
            Map<String, String> byColumn = new HashMap<>();
            jdbc.query("SELECT column_name, udt_name FROM information_schema.columns WHERE table_name = ?",
                    rs -> { byColumn.put(rs.getString(1), rs.getString(2)); }, t);
            return byColumn;
        });
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        relation.columns().forEach((field, column) -> {
            if (!record.containsKey(field)) {
                return;
            }
            String type = types.get(column);
            String value = SpecCase.text(record.get(field));
            columns.add(column);
            values.add(value.isBlank() && !TEXT_TYPES.contains(type) ? null : value);
        });
        jdbc.update("INSERT INTO " + relation.table() + " (" + String.join(", ", columns) + ") VALUES ("
                + columns.stream().map(c -> "CAST(? AS " + types.get(c) + ")").collect(Collectors.joining(", ")) + ")",
                values.toArray());
    }

    List<Effect> effectsSince(Map<String, Map<String, Map<String, String>>> before) {
        List<Effect> effects = new ArrayList<>();
        snapshot().forEach((file, after) -> {
            Map<String, Map<String, String>> old = before.get(file);
            after.forEach((key, row) -> {
                if (!old.containsKey(key)) {
                    effects.add(new Effect(file, "WRITE", row));
                } else if (!old.get(key).equals(row)) {
                    effects.add(new Effect(file, "REWRITE", row));
                }
            });
            old.forEach((key, row) -> {
                if (!after.containsKey(key)) {
                    effects.add(new Effect(file, "DELETE", row));
                }
            });
        });
        return effects;
    }

    /** Copybook names of the file's primary key, which identify an effect. */
    List<String> keyFields(String file) {
        Relation relation = RELATIONS.get(file);
        return relation != null ? relation.key()
                : tables.get(dataset(file)).primaryKey().stream().map(c -> c.field().name()).toList();
    }

    /** Whether two spec file names are one table, as CCXREF and its CXACAIX path are. */
    static boolean same(String file, String other) {
        return RELATIONS.containsKey(file) || RELATIONS.containsKey(other) ? file.equals(other)
                : FILES.get(file) == FILES.get(other);
    }

    /**
     * given.file_conditions. A read-side condition takes the table offline (every command on it fails);
     * a write-side one makes that kind of write raise, so the reads before it still work.
     */
    <T> T with(List<Map<String, Object>> conditions, Callable<T> action) throws Exception {
        List<String> undo = new ArrayList<>();
        try {
            for (Map<String, Object> c : conditions) {
                if ("NOTFND".equals(c.get("resp"))) {
                    throw new IllegalArgumentException("NOTFND is a missing record, not an injected fault: " + c);
                }
                String file = (String) c.get("file");
                String table = RELATIONS.containsKey(file) ? RELATIONS.get(file).table() : dataset(file).table;
                String op = (String) c.get("op");
                if (WRITE_OPS.contains(op)) {
                    String event = switch (op) { case "WRITE" -> "INSERT"; case "REWRITE" -> "UPDATE"; default -> "DELETE"; };
                    String trigger = "spec_" + op.toLowerCase() + "_" + table;
                    jdbc.execute("CREATE OR REPLACE FUNCTION spec_fault() RETURNS trigger LANGUAGE plpgsql AS"
                            + " $$ BEGIN RAISE EXCEPTION 'injected %', TG_OP; END $$");
                    jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE " + event + " ON " + table
                            + " FOR EACH ROW EXECUTE FUNCTION spec_fault()");
                    undo.addFirst("DROP TRIGGER " + trigger + " ON " + table);
                } else if (undo.stream().noneMatch(u -> u.startsWith("ALTER TABLE " + table + "_offline"))) {
                    jdbc.execute("ALTER TABLE " + table + " RENAME TO " + table + "_offline");
                    undo.addFirst("ALTER TABLE " + table + "_offline RENAME TO " + table);
                }
            }
            return action.call();
        } finally {
            undo.forEach(jdbc::execute);
        }
    }

    private static VsamDataset dataset(String file) {
        VsamDataset dataset = FILES.get(file);
        if (dataset == null) {
            throw new IllegalArgumentException("No table for CICS file " + file);
        }
        return dataset;
    }

    private static String file(VsamDataset dataset) {
        return switch (dataset) {
            case USERS -> "USRSEC";
            case ACCOUNTS -> "ACCTDAT";
            case CUSTOMERS -> "CUSTDAT";
            case CARDS -> "CARDDAT";
            case CARD_XREF -> "CCXREF";
            case TRANSACTIONS -> "TRANSACT";
            default -> throw new IllegalArgumentException(dataset.name());
        };
    }

    private static Object value(Column c, Object given) {
        String raw = SpecCase.text(given);
        if (c.name().equals("sec_usr_pwd")) {
            return HASHES.computeIfAbsent(raw, Credentials::hash);
        }
        return switch (c.kind()) {
            case TEXT -> raw;
            case DATE -> raw.isBlank() ? null : LocalDate.parse(raw);
            case TIMESTAMP -> raw.isBlank() ? null : Timestamp.valueOf(raw);
            case INTEGER -> raw.isBlank() ? 0L : Long.parseLong(raw);
            case DECIMAL -> raw.isBlank() ? BigDecimal.ZERO : new BigDecimal(raw);
        };
    }

    private static String column(Column c, Object value) {
        if (value == null) {
            return "";
        }
        return switch (c.kind()) {
            case INTEGER -> {
                long n = ((Number) value).longValue();
                int digits = ((Pic.Numeric) c.field().pic()).digits();
                yield (n < 0 ? "-" : "") + ("%0" + digits + "d").formatted(Math.abs(n));
            }
            case DECIMAL -> ((BigDecimal) value).toPlainString();
            case TIMESTAMP -> TIMESTAMP.format(((Timestamp) value).toLocalDateTime());
            default -> value.toString();
        };
    }
}
