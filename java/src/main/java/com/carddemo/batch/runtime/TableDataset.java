package com.carddemo.batch.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.carddemo.records.KeyedTable;
import com.carddemo.records.RecordImage;
import com.carddemo.vsam.Column;

/**
 * A KSDS held in its V1 table, each statement committed on its own as VSAM I/O is. Key order is the primary key
 * columns' order, which is the record key's byte order: unsigned digits and {@code COLLATE "C"} text.
 */
final class TableDataset extends KeyedFile {

    private final JdbcClient jdbc;
    private final KeyedTable table;
    private final List<Column> key;
    private final String select;
    private final String order;
    private final String keyEquals;

    TableDataset(JdbcClient jdbc, KeyedTable table) {
        super(table.key(), table.recordLength());
        this.jdbc = jdbc;
        this.table = table;
        this.key = table.primaryKey();
        this.select = "select " + names(table.columns()) + " from " + table.table();
        this.order = " order by " + names(key);
        this.keyEquals = key.stream().map(c -> c.name() + " = ?").collect(Collectors.joining(" and "));
    }

    /** Every record in key order, as a sequential read of the cluster sees them. */
    static List<byte[]> records(JdbcClient jdbc, KeyedTable table) {
        return new TableDataset(jdbc, table).query("", List.of(), "");
    }

    @Override
    boolean writable() {
        return true;
    }

    @Override
    Optional<byte[]> find(byte[] key) {
        return RecordImage.key(table, key)
                .flatMap(values -> query(" where " + keyEquals, Arrays.asList(values), "").stream().findFirst());
    }

    @Override
    Optional<byte[]> next(byte[] from, boolean inclusive) {
        if (from.length == 0) {
            return query("", List.of(), " limit 1").stream().findFirst();
        }
        Optional<Object[]> values = RecordImage.key(table, from);
        if (values.isPresent()) {
            String placeholders = key.stream().map(c -> "?").collect(Collectors.joining(", "));
            return query(" where (" + names(key) + ") " + (inclusive ? ">=" : ">") + " (" + placeholders + ")",
                    Arrays.asList(values.get()), " limit 1").stream().findFirst();
        }
        return query("", List.of(), "").stream().filter(record -> {
            int compared = Arrays.compareUnsigned(keyOf(record), from);
            return inclusive ? compared >= 0 : compared > 0;
        }).findFirst();
    }

    @Override
    List<byte[]> findAlternate(byte[] key, int limit) {
        Column alternate = table.alternateKey()
                .orElseThrow(() -> new IllegalStateException(table + " has no alternate key"));
        return RecordImage.alternate(table, key)
                .map(value -> query(" where " + alternate.name() + " = ?", List.of(value), " limit " + limit))
                .orElse(List.of());
    }

    @Override
    boolean insert(byte[] record) {
        Object[] values = RecordImage.columns(table, record);
        String placeholders = table.columns().stream().map(c -> "?").collect(Collectors.joining(", "));
        return jdbc.sql("insert into " + table.table() + " (" + names(table.columns()) + ") values (" + placeholders
                        + ") on conflict do nothing")
                .params(values).update() == 1;
    }

    @Override
    boolean update(byte[] record) {
        Object[] values = RecordImage.columns(table, record);
        List<Object> params = new ArrayList<>();
        List<String> sets = new ArrayList<>();
        List<Object> keyValues = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            Column column = table.columns().get(i);
            if (key.contains(column)) {
                keyValues.add(values[i]);
            } else {
                sets.add(column.name() + " = ?");
                params.add(values[i]);
            }
        }
        params.addAll(keyValues);
        return jdbc.sql("update " + table.table() + " set " + String.join(", ", sets) + " where " + keyEquals)
                .params(params).update() == 1;
    }

    @Override
    boolean remove(byte[] key) {
        return RecordImage.key(table, key)
                .map(values -> jdbc.sql("delete from " + table.table() + " where " + keyEquals).params(values)
                        .update() == 1)
                .orElse(false);
    }

    private List<byte[]> query(String where, List<Object> params, String limit) {
        return jdbc.sql(select + where + order + limit).params(params)
                .query((rs, row) -> RecordImage.of(table, rs)).list();
    }

    private static String names(List<Column> columns) {
        return columns.stream().map(Column::name).collect(Collectors.joining(", "));
    }
}
