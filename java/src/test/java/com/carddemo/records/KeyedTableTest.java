package com.carddemo.records;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class KeyedTableTest {

    private static final Map<KeyedTable, String> SEEDS = Map.of(
            KeyedTable.ACCTDATA, "acctdata.txt", KeyedTable.CUSTDATA, "custdata.txt",
            KeyedTable.CARDDATA, "carddata.txt", KeyedTable.CARDXREF, "cardxref.txt",
            KeyedTable.TRANTYPE, "trantype.txt", KeyedTable.TRANCATG, "trancatg.txt",
            KeyedTable.DISCGRP, "discgrp.txt", KeyedTable.TCATBALF, "tcatbal.txt",
            KeyedTable.TRANSACT, "dailytran.txt");

    @Test
    void checkedInTableMatchesTheCopybooks() throws IOException {
        assertEquals(KeyedTableGenerator.generate(appRoot()),
                Files.readString(KeyedTableGenerator.OUTPUT, StandardCharsets.UTF_8),
                "KeyedTable.java is stale; regenerate with: "
                        + "mvn -q exec:java -Dexec.mainClass=com.carddemo.records.KeyedTableGenerator -Dexec.args=../app");
    }

    @Test
    void everySeedRecordSurvivesItsColumns() throws IOException {
        for (var seed : SEEDS.entrySet()) {
            KeyedTable table = seed.getKey();
            for (String line : Files.readString(appRoot().resolve("data/ASCII").resolve(seed.getValue()), ISO_8859_1)
                    .split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                byte[] record = Fields.spaces(table.recordLength());
                Fields.text(record, 0, record.length, line.stripTrailing());
                assertArrayEquals(stored(table, record), RecordImage.of(table, RecordImage.columns(table, record)),
                        table + " " + line.substring(0, 20));
            }
        }
    }

    @Test
    void nullIsLowValuesInTextAndSpacesElsewhere() {
        KeyedTable table = KeyedTable.ACCTDATA;
        Object[] values = RecordImage.columns(table, RecordImage.of(table, new Object[table.columns().size()]));
        byte[] record = RecordImage.of(table, values);
        assertEquals(" ".repeat(11), Fields.text(record, 0, 11));
        assertEquals("\0".repeat(10), Fields.text(record, 102, 10));
        assertEquals(" ".repeat(10), Fields.text(record, 68, 10));
        assertTrue(Arrays.stream(values).allMatch(v -> v == null));
    }

    @Test
    void keysDecodeOnlyWhenARowCouldHoldThem() {
        Object[] tcatbal = RecordImage.key(KeyedTable.TCATBALF, "00000000001010005".getBytes(ISO_8859_1)).orElseThrow();
        assertArrayEquals(new Object[] {1L, "01", 5}, tcatbal);
        assertEquals(Optional.empty(), RecordImage.key(KeyedTable.ACCTDATA, "0000000000A".getBytes(ISO_8859_1)));
        assertEquals(Optional.empty(), RecordImage.key(KeyedTable.ACCTDATA, "0000000001{".getBytes(ISO_8859_1)));
        assertEquals(Optional.of(42L), RecordImage.alternate(KeyedTable.CARDXREF, "00000000042".getBytes(ISO_8859_1)));
        assertEquals(LocalDate.of(2025, 5, 20), RecordImage.columns(KeyedTable.ACCTDATA,
                withText(KeyedTable.ACCTDATA, 68, "2025-05-20"))[7]);
        assertEquals(new BigDecimal("-58.25"), RecordImage.columns(KeyedTable.ACCTDATA,
                withText(KeyedTable.ACCTDATA, 12, "00000000582N"))[2]);
    }

    private static byte[] withText(KeyedTable table, int offset, String text) {
        byte[] record = RecordImage.of(table, new Object[table.columns().size()]);
        Fields.text(record, 0, 11, "00000000001");
        Fields.text(record, offset, text.length(), text);
        return record;
    }

    private static byte[] stored(KeyedTable table, byte[] record) {
        byte[] expected = Fields.spaces(table.recordLength());
        table.columns().forEach(c -> System.arraycopy(record, c.field().offset(), expected, c.field().offset(),
                c.field().length()));
        return expected;
    }

    private static Path appRoot() {
        String configured = System.getProperty("carddemo.app-root");
        return configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of("..", "app").toAbsolutePath().normalize();
    }
}
