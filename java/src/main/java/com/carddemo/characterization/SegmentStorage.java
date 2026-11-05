package com.carddemo.characterization;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.carddemo.dli.PendingAuthDetail;
import com.carddemo.dli.PendingAuthSummary;
import com.carddemo.dli.SegmentTables;
import com.carddemo.dli.SegmentTables.Child;
import com.carddemo.vsam.Field;
import com.carddemo.vsam.Pic;

/**
 * DBPAUTP0's segments as the harness stores them: {@code PAUTSUM0} records are the 100-byte root, and
 * {@code PAUTDTL1} records the root's packed ACCNTID followed by the 200-byte dependent, so the record key is
 * the IMS concatenated key.
 */
enum SegmentStorage implements Storage {
    PAUTSUM0 {
        @Override
        public String table() {
            return SegmentTables.SUMMARIES;
        }

        @Override
        List<Field> layout() {
            return PendingAuthSummary.LAYOUT;
        }

        @Override
        String columns() {
            return SegmentTables.SUMMARY_COLUMNS;
        }

        @Override
        String insert() {
            return SegmentTables.INSERT_SUMMARY;
        }

        @Override
        byte[] record(ResultSet row) throws SQLException {
            return SegmentTables.summary(row).image();
        }

        @Override
        Object[] values(byte[] record) {
            return SegmentTables.values(PendingAuthSummary.of(record));
        }
    },
    PAUTDTL1 {
        private static final Field PARENT = new Field("PAUT-PARENT-ACCT-ID", 0, PARENT_KEY,
                new Pic.Packed(11, 0, true), "S9(11) COMP-3");

        @Override
        public String table() {
            return SegmentTables.DETAILS;
        }

        @Override
        List<Field> layout() {
            List<Field> fields = new ArrayList<>(List.of(PARENT));
            for (Field f : PendingAuthDetail.LAYOUT) {
                fields.add(new Field(f.cobolName(), PARENT_KEY + f.offset(), f.length(), f.pic(), f.picClause()));
            }
            return fields;
        }

        @Override
        String columns() {
            return SegmentTables.DETAIL_COLUMNS;
        }

        @Override
        String insert() {
            return SegmentTables.INSERT_DETAIL;
        }

        @Override
        byte[] record(ResultSet row) throws SQLException {
            Child child = SegmentTables.child(row);
            byte[] record = Arrays.copyOf(PendingAuthSummary.keyImage(child.acctId()), PARENT_KEY + PendingAuthDetail.LENGTH);
            System.arraycopy(child.detail().image(), 0, record, PARENT_KEY, PendingAuthDetail.LENGTH);
            return record;
        }

        @Override
        Object[] values(byte[] record) {
            long acctId = PendingAuthSummary.keyOf(record, 0);
            return SegmentTables.values(new Child(acctId,
                    PendingAuthDetail.of(Arrays.copyOfRange(record, PARENT_KEY, record.length))));
        }
    };

    private static final int PARENT_KEY = 6;

    /** The harness layout of a record, field names as the registry's copybook gives them. */
    abstract List<Field> layout();

    abstract String columns();

    abstract String insert();

    abstract byte[] record(ResultSet row) throws SQLException;

    abstract Object[] values(byte[] record);


    @Override
    public List<byte[]> read(Connection db) throws SQLException {
        List<byte[]> records = new ArrayList<>();
        try (Statement select = db.createStatement();
                ResultSet rows = select.executeQuery("SELECT " + columns() + " FROM " + table())) {
            while (rows.next()) {
                records.add(record(rows));
            }
        }
        return records;
    }

    @Override
    public void replace(Connection db, Iterable<byte[]> records) throws SQLException {
        try (Statement delete = db.createStatement(); PreparedStatement insert = db.prepareStatement(insert())) {
            delete.execute("DELETE FROM " + table());
            for (byte[] record : records) {
                Object[] values;
                try {
                    values = values(record);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(name() + " cannot hold a record a typed segment cannot: a new "
                            + "segment in a case's setup must give every numeric field; " + e.getMessage(), e);
                }
                Storage.bind(insert, values);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
