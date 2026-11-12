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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


/**
 * The tables the online programs use, seen as the CICS files of a spec: loaded from given.files, diffed
 * into file_effects, and failed on purpose for given.file_conditions. Records are keyed by copybook name
 * and compared as text: integers zero-padded to their PIC width, decimals at their scale, dates ISO.
 */
class Datasets {

    record Effect(String file, String op, Map<String, String> record) {
    }

    /** CSD file name to table; CXACAIX is the account-id alternate index over CCXREF. */
    static final Map<String, VsamDataset> FILES = Map.of("USRSEC", VsamDataset.USERS, "ACCTDAT", VsamDataset.ACCOUNTS,
            "CUSTDAT", VsamDataset.CUSTOMERS, "CARDDAT", VsamDataset.CARDS, "CCXREF", VsamDataset.CARD_XREF,
            "CXACAIX", VsamDataset.CARD_XREF);

    private static final List<VsamDataset> TABLES = List.of(VsamDataset.USERS, VsamDataset.ACCOUNTS,
            VsamDataset.CUSTOMERS, VsamDataset.CARDS, VsamDataset.CARD_XREF);
    private static final Set<String> WRITE_OPS = Set.of("WRITE", "REWRITE", "DELETE");

    /** bcrypt is slow on purpose; the specs reuse a handful of passwords. */
    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;
    private final Map<VsamDataset, Table> tables = new LinkedHashMap<>();

    Datasets(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        TABLES.forEach(d -> tables.put(d, Table.of(d)));
    }

    /** Every table holds exactly the records given for it; a table the case does not name is empty. */
    void reset(Map<String, List<Map<String, Object>>> files) {
        jdbc.execute("TRUNCATE " + TABLES.stream().map(d -> d.table).collect(Collectors.joining(", ")));
        files.forEach((file, records) -> {
            Table table = tables.get(dataset(file));
            for (Map<String, Object> record : records) {
                Set<String> unknown = new HashSet<>(record.keySet());
                table.columns().forEach(c -> unknown.remove(c.field().name()));
                if (!unknown.isEmpty()) {
                    throw new IllegalArgumentException(file + " has no fields " + unknown);
                }
                jdbc.update(table.insertSql(), table.columns().stream().map(c -> value(c, record.get(c.field().name()))).toArray());
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
        return all;
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
        return tables.get(dataset(file)).primaryKey().stream().map(c -> c.field().name()).toList();
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
                String table = dataset((String) c.get("file")).table;
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
            default -> value.toString();
        };
    }
}
