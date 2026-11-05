package com.carddemo.batch.utilities;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SortSpecTest {

    @Test
    void creastmtLeadsEachTransactionWithItsCardNumberAndSortsByCardThenTransactionId() {
        byte[] second = transaction("T2", "4000000000000002");
        byte[] first = transaction("T9", "4000000000000001");
        byte[] third = transaction("T1", "4000000000000002");

        List<String> sorted = new SortSpec.CreastmtTrxfl().apply(List.of(second, first, third)).stream()
                .map(record -> new String(record, ISO_8859_1)).toList();

        assertEquals(List.of("4000000000000001T9", "4000000000000002T1", "4000000000000002T2"),
                sorted.stream().map(record -> record.substring(0, 18)).toList());
        String moved = sorted.get(0);
        assertEquals(350, moved.length());
        assertEquals(new String(first, 0, 262, ISO_8859_1), moved.substring(16, 278));
        assertEquals(new String(first, 278, 50, ISO_8859_1), moved.substring(278, 328));
        assertEquals(" ".repeat(22), moved.substring(328));
    }

    private static byte[] transaction(String id, String card) {
        byte[] record = new byte[350];
        Arrays.fill(record, (byte) '.');
        System.arraycopy(id.getBytes(ISO_8859_1), 0, record, 0, id.length());
        System.arraycopy(card.getBytes(ISO_8859_1), 0, record, 262, 16);
        return record;
    }
}
