package com.carddemo.characterization;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.carddemo.records.KeyedTable;
import com.carddemo.records.RecordImage;
import com.carddemo.vsam.Column;

/** Where a registry dataset's records live in Postgres, read and written as the harness's record images. */
interface Storage {

    String table();

    /** Every record, in no particular order. */
    List<byte[]> read(Connection db) throws SQLException;

    /** The table emptied, then holding exactly {@code records}. */
    void replace(Connection db, Iterable<byte[]> records) throws SQLException;

    /** A KSDS held in its V1 table. */
    record Ksds(KeyedTable keyed) implements Storage {

        @Override
        public String table() {
            return keyed.table();
        }

        @Override
        public List<byte[]> read(Connection db) throws SQLException {
            List<byte[]> records = new ArrayList<>();
            try (Statement select = db.createStatement();
                    ResultSet rows = select.executeQuery("SELECT " + columns() + " FROM " + keyed.table())) {
                while (rows.next()) {
                    records.add(RecordImage.of(keyed, rows));
                }
            }
            return records;
        }

        @Override
        public void replace(Connection db, Iterable<byte[]> records) throws SQLException {
            String placeholders = String.join(",", keyed.columns().stream().map(c -> "?").toList());
            try (Statement delete = db.createStatement();
                    PreparedStatement insert = db.prepareStatement(
                            "INSERT INTO " + keyed.table() + " (" + columns() + ") VALUES (" + placeholders + ")")) {
                delete.execute("DELETE FROM " + keyed.table());
                for (byte[] record : records) {
                    bind(insert, RecordImage.columns(keyed, record));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }

        private String columns() {
            return String.join(", ", keyed.columns().stream().map(Column::name).toList());
        }
    }

    static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }
}
