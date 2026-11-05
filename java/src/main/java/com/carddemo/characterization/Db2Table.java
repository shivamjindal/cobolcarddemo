package com.carddemo.characterization;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A Db2 table of the transaction-type extension as {@code characterization/harness/db2.py} keeps it: one row
 * image per row, the key columns, the VARCHAR's length in two digits, then its text.
 */
enum Db2Table implements Storage {
    TRANSACTION_TYPE("carddemo.transaction_type", List.of(new KeyColumn("tr_type", 2)), "tr_description"),
    TRANSACTION_TYPE_CATEGORY("carddemo.transaction_type_category",
            List.of(new KeyColumn("trc_type_code", 2), new KeyColumn("trc_type_category", 4)), "trc_cat_data");

    private static final int LENGTH_DIGITS = 2;
    private static final int VARCHAR_LENGTH = 50;

    record KeyColumn(String name, int width) {}

    private final String table;
    private final List<KeyColumn> keys;
    private final String varchar;

    Db2Table(String table, List<KeyColumn> keys, String varchar) {
        this.table = table;
        this.keys = keys;
        this.varchar = varchar;
    }

    @Override
    public String table() {
        return table;
    }

    int recordLength() {
        return keyLength() + LENGTH_DIGITS + VARCHAR_LENGTH;
    }

    int keyLength() {
        return keys.stream().mapToInt(KeyColumn::width).sum();
    }

    /** {@code db2.dump}: the image built in SQL, trailing blanks dropped, then read back to the record length. */
    @Override
    public List<byte[]> read(Connection db) throws SQLException {
        String image = keys.stream().map(k -> "rpad(" + k.name() + ", " + k.width() + ")")
                .collect(Collectors.joining(" || ")) + " || lpad(length(" + varchar + ")::text, 2, '0') || " + varchar;
        List<byte[]> records = new ArrayList<>();
        try (Statement select = db.createStatement();
                ResultSet rows = select.executeQuery("SELECT " + image + " FROM " + table)) {
            while (rows.next()) {
                String line = rows.getString(1).replaceAll(" +$", "");
                byte[] record = " ".repeat(recordLength()).getBytes(ISO_8859_1);
                byte[] text = line.getBytes(ISO_8859_1);
                System.arraycopy(text, 0, record, 0, Math.min(text.length, record.length));
                records.add(record);
            }
        }
        return records;
    }

    /** {@code db2.reset}: every row from its image; a length of 0 or blanks means the text's rstripped length. */
    @Override
    public void replace(Connection db, Iterable<byte[]> records) throws SQLException {
        String columns = keys.stream().map(KeyColumn::name).collect(Collectors.joining(", ")) + ", " + varchar;
        String placeholders = String.join(", ", keys.stream().map(k -> "?").toList()) + ", ?";
        try (Statement delete = db.createStatement();
                PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")")) {
            delete.execute("DELETE FROM " + table);
            for (byte[] record : records) {
                String text = new String(record, ISO_8859_1);
                int at = 0;
                int index = 1;
                for (KeyColumn key : keys) {
                    insert.setString(index++, text.substring(at, at + key.width()));
                    at += key.width();
                }
                String digits = text.substring(at, at + LENGTH_DIGITS);
                String data = text.substring(at + LENGTH_DIGITS);
                int length = digits.replaceAll("[0 ]", "").isEmpty() ? 0 : Integer.parseInt(digits.strip());
                if (length == 0) {
                    length = Python.rstrip(data).length();
                }
                insert.setString(index, data.substring(0, Math.min(length, data.length())));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
