package com.carddemo.vsam;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class VsamLoader {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RecordCodec codec = new RecordCodec();

    public VsamLoader(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
    }

    public void load(Path appRoot, List<TableDef> tables) throws IOException {
        List<List<Object[]>> decoded = new ArrayList<>();
        for (TableDef table : tables) {
            decoded.add(decode(appRoot, table));
        }
        transactions.executeWithoutResult(status -> write(tables, decoded));
    }

    private List<Object[]> decode(Path appRoot, TableDef table) throws IOException {
        List<Object[]> rows = new ArrayList<>();
        for (byte[] record : Records.read(Records.dataFile(appRoot, table.dataset()), table.layout().recordLength())) {
            rows.add(codec.decode(record, table));
        }
        return rows;
    }

    private void write(List<TableDef> tables, List<List<Object[]>> decoded) {
        String names = tables.stream().map(table -> table.dataset().table()).collect(Collectors.joining(", "));
        jdbc.execute("TRUNCATE " + names);
        for (int i = 0; i < tables.size(); i++) {
            insert(tables.get(i), decoded.get(i));
        }
    }

    private void insert(TableDef table, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        String columns = table.columns().stream().map(Column::name).collect(Collectors.joining(", "));
        String placeholders = table.columns().stream().map(column -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + table.dataset().table() + " (" + columns + ") VALUES (" + placeholders + ")";
        jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int row) throws SQLException {
                Object[] values = rows.get(row);
                for (int i = 0; i < values.length; i++) {
                    bind(statement, i + 1, values[i], table.columns().get(i).type());
                }
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    private static void bind(PreparedStatement statement, int index, Object value, SqlType type) throws SQLException {
        switch (type) {
            case SqlType.Text ignored -> statement.setString(index, (String) value);
            case SqlType.Int ignored -> statement.setInt(index, ((Number) value).intValue());
            case SqlType.BigInt ignored -> statement.setLong(index, ((Number) value).longValue());
            case SqlType.Numeric ignored -> statement.setBigDecimal(index, (BigDecimal) value);
            case SqlType.Date ignored -> statement.setObject(index, (LocalDate) value);
            case SqlType.Timestamp ignored -> statement.setString(index, ((LocalDateTime) value).format(SqlTypes.TIMESTAMP));
        }
    }
}
