package com.carddemo.records;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.carddemo.vsam.Column;
import com.carddemo.vsam.Field;
import com.carddemo.vsam.Pic;
import com.carddemo.vsam.SqlType;
import com.carddemo.vsam.SqlTypes;

/**
 * A V1 table row as the record of its KSDS, and back. Bytes no column stores are spaces. NULL is LOW-VALUES in
 * a text column (V2) and SPACES in a numeric or date column (V2, V4); text is stored without trailing spaces.
 */
public final class RecordImage {

    private RecordImage() {}

    public static byte[] of(KeyedTable table, ResultSet row) throws SQLException {
        Object[] values = new Object[table.columns().size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = row.getObject(table.columns().get(i).name());
        }
        return of(table, values);
    }

    /** The record for column values in {@link KeyedTable#columns()} order. */
    public static byte[] of(KeyedTable table, Object[] values) {
        byte[] record = Fields.spaces(table.recordLength());
        for (int i = 0; i < values.length; i++) {
            put(record, table.columns().get(i), values[i]);
        }
        return record;
    }

    /** Column values in {@link KeyedTable#columns()} order. */
    public static Object[] columns(KeyedTable table, byte[] record) {
        if (record.length != table.recordLength()) {
            throw new IllegalArgumentException(table + " record of " + record.length + " bytes, not "
                    + table.recordLength());
        }
        return table.columns().stream().map(column -> value(record, column)).toArray();
    }

    /** The primary-key values a key image stands for, empty when no row can hold that key. */
    public static Optional<Object[]> key(KeyedTable table, byte[] key) {
        return values(table, table.primaryKey(), key, table.key().offset());
    }

    /** The alternate-key value a key image stands for, empty when no row can hold it. */
    public static Optional<Object> alternate(KeyedTable table, byte[] key) {
        Column column = table.alternateKey().orElseThrow(() -> new IllegalArgumentException(table + " has no alternate key"));
        return values(table, List.of(column), key, column.field().offset()).map(values -> values[0]);
    }

    private static Optional<Object[]> values(KeyedTable table, List<Column> columns, byte[] key, int offset) {
        byte[] record = Fields.spaces(table.recordLength());
        System.arraycopy(key, 0, record, offset, Math.min(key.length, record.length - offset));
        Object[] values = new Object[columns.size()];
        byte[] again = Fields.spaces(table.recordLength());
        try {
            for (int i = 0; i < values.length; i++) {
                values[i] = value(record, columns.get(i));
                put(again, columns.get(i), values[i]);
            }
        } catch (IllegalArgumentException | java.time.DateTimeException notStorable) {
            return Optional.empty();
        }
        int end = offset + Math.min(key.length, record.length - offset);
        return Arrays.equals(record, offset, end, again, offset, end) && Arrays.stream(values).allMatch(v -> v != null)
                ? Optional.of(values)
                : Optional.empty();
    }

    static Object value(byte[] record, Column column) {
        Field field = column.field();
        String text = Fields.text(record, field.offset(), field.length());
        return switch (column.type()) {
            case SqlType.Text ignored -> text.chars().allMatch(c -> c == 0) ? null : stripSpaces(text);
            case SqlType.Date ignored -> text.isBlank() ? null : LocalDate.parse(text, SqlTypes.DATE);
            case SqlType.Timestamp ignored -> throw new IllegalStateException(column.name() + " is not stored as text");
            case SqlType.Int ignored -> text.isBlank() ? null : number(record, field).intValueExact();
            case SqlType.BigInt ignored -> text.isBlank() ? null : number(record, field).longValueExact();
            case SqlType.Numeric ignored -> text.isBlank() ? null : number(record, field);
        };
    }

    static void put(byte[] record, Column column, Object value) {
        Field field = column.field();
        if (value == null) {
            byte fill = column.type() instanceof SqlType.Text ? 0 : (byte) ' ';
            Arrays.fill(record, field.offset(), field.offset() + field.length(), fill);
            return;
        }
        switch (column.type()) {
            case SqlType.Text ignored -> Fields.text(record, field.offset(), field.length(), (String) value);
            case SqlType.Date ignored -> Fields.text(record, field.offset(), field.length(),
                    (value instanceof java.sql.Date date ? date.toLocalDate() : (LocalDate) value).format(SqlTypes.DATE));
            default -> {
                Pic.Zoned zoned = zoned(field);
                Fields.zoned(record, field.offset(), field.length(), zoned.scale(), zoned.signed(),
                        new BigDecimal(value.toString()));
            }
        }
    }

    private static BigDecimal number(byte[] record, Field field) {
        return Fields.zoned(record, field.offset(), field.length(), zoned(field).scale());
    }

    private static Pic.Zoned zoned(Field field) {
        if (field.pic() instanceof Pic.Zoned zoned) {
            return zoned;
        }
        throw new IllegalStateException(field.cobolName() + " is not zoned decimal");
    }

    private static String stripSpaces(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(0, end);
    }
}
