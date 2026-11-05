package com.carddemo.dli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Replaces DBPAUTP0's tables with the sample unload, as PAUDBLOD reloads the database, in one transaction. */
@Component
public class PendingAuthorizationLoader {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PendingAuthorizationLoader(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
    }

    public Dbpautp0Unload.Contents load(Path appRoot) throws IOException {
        Dbpautp0Unload.Contents contents = Dbpautp0Unload.read(appRoot.resolve(Dbpautp0Unload.DATA_FILE));
        transactions.executeWithoutResult(status -> {
            jdbc.execute("TRUNCATE " + SegmentTables.SUMMARIES + ", " + SegmentTables.DETAILS);
            jdbc.batchUpdate(SegmentTables.INSERT_SUMMARY,
                    contents.roots().stream().map(SegmentTables::values).toList());
            jdbc.batchUpdate(SegmentTables.INSERT_DETAIL,
                    contents.children().stream().map(SegmentTables::values).toList());
        });
        return contents;
    }

    /** Rows in each table, to report next to the unload's segment counts. */
    public List<Integer> counts() {
        return List.of(jdbc.queryForObject("SELECT count(*) FROM " + SegmentTables.SUMMARIES, Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM " + SegmentTables.DETAILS, Integer.class));
    }
}
