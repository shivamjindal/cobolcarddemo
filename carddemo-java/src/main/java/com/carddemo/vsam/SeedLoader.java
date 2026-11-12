package com.carddemo.vsam;

import com.carddemo.users.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** Replaces the VSAM tables' contents with the sample files, like the REPRO steps in app/jcl. */
@Component
public class SeedLoader {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final JdbcTemplate jdbc;

    public SeedLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<VsamDataset, Integer> load(Path dir, SeedFormat format) {
        String allTables = Arrays.stream(VsamDataset.values()).map(d -> d.table).collect(Collectors.joining(", "));
        // A fresh TRANSACT makes the outcomes of any earlier feed meaningless.
        jdbc.execute("TRUNCATE " + allTables + ", daily_rejects");
        Map<VsamDataset, Integer> loaded = new EnumMap<>(VsamDataset.class);
        for (VsamDataset dataset : loadOrder()) {
            Table table = Table.of(dataset);
            format.records(dir, dataset).ifPresentOrElse(
                    records -> loaded.put(dataset, insert(table, records,
                            dataset == VsamDataset.USERS ? row -> secure(table, row) : row -> row)),
                    () -> log.info("No {} sample file for {}", format, dataset.table));
        }
        return loaded;
    }

    /** Categories reference types. Load the parent table first so the foreign key can be checked per insert. */
    private static List<VsamDataset> loadOrder() {
        List<VsamDataset> order = new ArrayList<>(List.of(VsamDataset.values()));
        int types = order.indexOf(VsamDataset.TRAN_TYPES);
        int categories = order.indexOf(VsamDataset.TRAN_CATEGORIES);
        if (types > categories) {
            order.remove(types);
            order.add(categories, VsamDataset.TRAN_TYPES);
        }
        return order;
    }

    private int insert(Table table, List<String> records, UnaryOperator<Object[]> transform) {
        // TRANSACT is seeded from a single LOW-VALUES record (DALYTRAN.PS.INIT); it is not a transaction.
        List<Object[]> rows = records.stream().filter(r -> !table.isPlaceholder(r)).map(table::decode)
                .map(transform)
                .toList();
        jdbc.batchUpdate(table.insertSql(), rows);
        log.info("Loaded {} rows into {} ({} placeholder records skipped)",
                rows.size(), table.name(), records.size() - rows.size());
        return rows.size();
    }

    /** USRSEC holds clear-text passwords; V2 stores bcrypt hashes and upper-case IDs. */
    private static Object[] secure(Table table, Object[] row) {
        int id = table.columnIndex("sec_usr_id");
        int password = table.columnIndex("sec_usr_pwd");
        row[id] = Credentials.normalize((String) row[id]);
        row[password] = Credentials.hash((String) row[password]);
        return row;
    }
}
