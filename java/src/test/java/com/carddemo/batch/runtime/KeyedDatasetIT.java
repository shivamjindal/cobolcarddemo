package com.carddemo.batch.runtime;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.batch.step.FileStatus;
import com.carddemo.batch.step.KeyCondition;
import com.carddemo.batch.step.KeyedDataset;
import com.carddemo.batch.step.OpenMode;
import com.carddemo.batch.step.Read;
import com.carddemo.records.KeyedTable;
import com.carddemo.records.RecordImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The file statuses a program sees on a KSDS held in a table. Test keys sort after every seeded key. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KeyedDatasetIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void removeTestRows() {
        jdbc.sql("delete from transaction_categories where tran_type_cd like 'Z%'").update();
        jdbc.sql("delete from transactions where tran_id like 'ZZZZ%'").update();
    }

    @Test
    void readsWritesAndBrowsesByTheCompositeKey() {
        KeyedDataset categories = new TableDataset(jdbc, KeyedTable.TRANCATG);
        assertEquals(FileStatus.OK, categories.open(OpenMode.I_O));
        assertEquals(FileStatus.OK, categories.write(category("Z8", 2, "second")));
        assertEquals(FileStatus.OK, categories.write(category("Z8", 1, "first")));
        assertEquals(FileStatus.OK, categories.write(category("Z9", 1, "last")));
        assertEquals(FileStatus.DUPLICATE_KEY, categories.write(category("Z8", 1, "again")));

        assertRecord(category("Z8", 2, "second"), FileStatus.OK, categories.read(key("Z80002")));
        assertRecord(category("Z9", 1, "last"), FileStatus.OK, categories.readNext());
        assertEquals(FileStatus.END_OF_FILE, categories.readNext().status());
        assertEquals(FileStatus.NO_NEXT_RECORD, categories.readNext().status());
        assertEquals(FileStatus.NOT_FOUND, categories.read(key("Z80003")).status());
        assertEquals(FileStatus.NO_NEXT_RECORD, categories.readNext().status());

        assertEquals(FileStatus.OK, categories.start(key("Z8"), KeyCondition.EQUAL));
        assertRecord(category("Z8", 1, "first"), FileStatus.OK, categories.readNext());
        assertRecord(category("Z8", 2, "second"), FileStatus.OK, categories.readNext());
        assertEquals(FileStatus.OK, categories.start(key("Z8"), KeyCondition.GREATER));
        assertRecord(category("Z9", 1, "last"), FileStatus.OK, categories.readNext());
        assertEquals(FileStatus.OK, categories.start(key("Z80001"), KeyCondition.GREATER));
        assertRecord(category("Z8", 2, "second"), FileStatus.OK, categories.readNext());
        assertEquals(FileStatus.OK, categories.start(key("Z80003"), KeyCondition.NOT_LESS));
        assertRecord(category("Z9", 1, "last"), FileStatus.OK, categories.readNext());
        assertEquals(FileStatus.NOT_FOUND, categories.start(key("Z7"), KeyCondition.EQUAL));
        assertEquals(FileStatus.NO_NEXT_RECORD, categories.readNext().status());
        assertEquals(FileStatus.NOT_FOUND, categories.start(key("Z9"), KeyCondition.GREATER));

        assertEquals(FileStatus.OK, categories.rewrite(category("Z8", 1, "rewritten")));
        assertEquals(FileStatus.NOT_FOUND, categories.rewrite(category("Z8", 3, "absent")));
        assertRecord(category("Z8", 1, "rewritten"), FileStatus.OK, categories.read(key("Z80001")));
        assertEquals(FileStatus.OK, categories.delete(key("Z90001")));
        assertEquals(FileStatus.NOT_FOUND, categories.delete(key("Z90001")));
        assertEquals(FileStatus.NOT_FOUND, categories.read(key("Z90001")).status());
        assertEquals(FileStatus.OK, categories.close());
    }

    @Test
    void openModesGateEachStatement() {
        KeyedDataset categories = new TableDataset(jdbc, KeyedTable.TRANCATG);
        assertEquals(FileStatus.NOT_OPEN_FOR_READ, categories.read(key("Z80001")).status());
        assertEquals(FileStatus.NOT_OPEN, categories.close());
        assertEquals(FileStatus.OK, categories.open(OpenMode.INPUT));
        assertEquals(FileStatus.ALREADY_OPEN, categories.open(OpenMode.INPUT));
        assertEquals(FileStatus.NOT_OPEN_FOR_WRITE, categories.write(category("Z8", 1, "first")));
        assertEquals(FileStatus.NOT_OPEN_FOR_REWRITE, categories.rewrite(category("Z8", 1, "first")));
        assertEquals(FileStatus.NOT_OPEN_FOR_REWRITE, categories.delete(key("Z80001")));
        assertEquals(FileStatus.OK, categories.close());
        assertEquals(FileStatus.OK, categories.open(OpenMode.OUTPUT));
        assertEquals(FileStatus.OK, categories.write(category("Z8", 1, "first")));
        assertEquals(FileStatus.NOT_OPEN_FOR_READ, categories.read(key("Z80001")).status());
        assertEquals(FileStatus.RECORD_LENGTH, categories.write(Arrays.copyOf(category("Z8", 2, "short"), 59)));
        assertEquals(FileStatus.OK, categories.close());
    }

    @Test
    void outputOpenReplacesTheFile() {
        KeyedDataset transactions = new TableDataset(jdbc, KeyedTable.TRANSACT);
        assertEquals(FileStatus.OK, transactions.open(OpenMode.I_O));
        assertEquals(FileStatus.OK, transactions.write(transaction("ZZZZ000000000009", "2099-01-01-00.00.00.000000")));
        assertEquals(FileStatus.OK, transactions.close());

        assertEquals(FileStatus.OK, transactions.open(OpenMode.OUTPUT));
        assertEquals(FileStatus.OK, transactions.close());
        assertEquals(FileStatus.OK, transactions.open(OpenMode.INPUT));
        assertEquals(FileStatus.END_OF_FILE, transactions.readNext().status());
        assertEquals(FileStatus.OK, transactions.close());
    }

    @Test
    void readByTheAlternateKeyReportsDuplicates() {
        KeyedDataset transactions = new TableDataset(jdbc, KeyedTable.TRANSACT);
        assertEquals(FileStatus.OK, transactions.open(OpenMode.OUTPUT));
        assertEquals(FileStatus.OK, transactions.write(transaction("ZZZZ000000000002", "2099-01-01-00.00.00.000000")));
        assertEquals(FileStatus.OK, transactions.write(transaction("ZZZZ000000000001", "2099-01-01-00.00.00.000000")));
        assertEquals(FileStatus.OK, transactions.write(transaction("ZZZZ000000000003", "2099-01-02-00.00.00.000000")));
        assertEquals(FileStatus.OK, transactions.close());

        assertEquals(FileStatus.OK, transactions.open(OpenMode.INPUT));
        assertRecord(transaction("ZZZZ000000000001", "2099-01-01-00.00.00.000000"), FileStatus.DUPLICATE_ALTERNATE_KEY,
                transactions.readAlternate(key("2099-01-01-00.00.00.000000")));
        assertRecord(transaction("ZZZZ000000000003", "2099-01-02-00.00.00.000000"), FileStatus.OK,
                transactions.readAlternate(key("2099-01-02-00.00.00.000000")));
        assertEquals(FileStatus.NOT_FOUND, transactions.readAlternate(key("2099-01-03-00.00.00.000000")).status());
        assertEquals(FileStatus.OK, transactions.close());
    }

    private static byte[] category(String type, int category, String description) {
        return RecordImage.of(KeyedTable.TRANCATG, new Object[] {type, category, description});
    }

    private static byte[] transaction(String id, String processed) {
        return RecordImage.of(KeyedTable.TRANSACT, new Object[] {id, "01", 1, "POS TERM", "test", new BigDecimal("1.00"),
                1, "merchant", "city", "zip", "4111111111111111", processed, processed});
    }

    private static byte[] key(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void assertRecord(byte[] expected, FileStatus status, Read read) {
        Read.Found found = (Read.Found) read;
        assertEquals(status, found.status());
        assertArrayEquals(expected, found.record());
    }
}
