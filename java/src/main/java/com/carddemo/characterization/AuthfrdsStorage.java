package com.carddemo.characterization;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.carddemo.records.Fields;
import com.carddemo.vsam.Field;
import com.carddemo.vsam.Pic;

/**
 * Db2 table AUTHFRDS as the harness's text record: one column per copybook field, named after it. AUTH-TS
 * and FRAUD-RPT-DATE hold Db2's external timestamp and date; DISPLAY numbers hold the numeric columns. A
 * blank timestamp, date or number is a NULL column.
 */
record AuthfrdsStorage(int recordLength, List<Field> fields) implements Storage {

    private static final DateTimeFormatter DB2_TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd-HH.mm.ss.SSSSSS");
    private static final DateTimeFormatter DB2_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override
    public String table() {
        return "authfrds";
    }

    @Override
    public List<byte[]> read(Connection db) throws SQLException {
        List<byte[]> records = new ArrayList<>();
        try (Statement select = db.createStatement();
                ResultSet rows = select.executeQuery("SELECT " + columns() + " FROM " + table())) {
            while (rows.next()) {
                byte[] record = Fields.spaces(recordLength);
                for (Field field : fields) {
                    put(record, field, rows);
                }
                records.add(record);
            }
        }
        return records;
    }

    @Override
    public void replace(Connection db, Iterable<byte[]> records) throws SQLException {
        String placeholders = String.join(", ", Collections.nCopies(fields.size(), "?"));
        try (Statement delete = db.createStatement();
                PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO " + table() + " (" + columns() + ") VALUES (" + placeholders + ")")) {
            delete.execute("DELETE FROM " + table());
            for (byte[] record : records) {
                Storage.bind(insert, fields.stream().map(f -> value(record, f)).toArray());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private String columns() {
        return String.join(", ", fields.stream().map(AuthfrdsStorage::column).toList());
    }

    private static String column(Field field) {
        return field.cobolName().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static Object value(byte[] record, Field field) {
        String text = Fields.text(record, field.offset(), field.length());
        boolean blank = text.isBlank();
        return switch (column(field)) {
            case "auth_ts" -> blank ? null : Timestamp.valueOf(LocalDateTime.parse(text, DB2_TIMESTAMP));
            case "fraud_rpt_date" -> blank ? null : Date.valueOf(LocalDate.parse(text, DB2_DATE));
            default -> {
                if (field.pic() instanceof Pic.Zoned zoned) {
                    yield blank ? null : Fields.zoned(record, field.offset(), field.length(), zoned.scale());
                }
                yield text.replaceAll(" +$", "");
            }
        };
    }

    private static void put(byte[] record, Field field, ResultSet row) throws SQLException {
        String column = column(field);
        String text = switch (column) {
            case "auth_ts" -> {
                Timestamp ts = row.getTimestamp(column);
                yield ts == null ? "" : ts.toLocalDateTime().format(DB2_TIMESTAMP);
            }
            case "fraud_rpt_date" -> {
                Date date = row.getDate(column);
                yield date == null ? "" : date.toLocalDate().format(DB2_DATE);
            }
            default -> {
                if (field.pic() instanceof Pic.Zoned zoned) {
                    BigDecimal number = row.getBigDecimal(column);
                    if (number != null) {
                        Fields.zoned(record, field.offset(), field.length(), zoned.scale(), zoned.signed(), number);
                    }
                    yield null;
                }
                String value = row.getString(column);
                yield value == null ? "" : value;
            }
        };
        if (text != null) {
            Fields.text(record, field.offset(), field.length(), text);
        }
    }
}
