package com.carddemo.dli;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.carddemo.vsam.Fixtures;

class SegmentImageTest {

    @Test
    void theSampleUnloadHoldsTwentyTwoAccountsAndTheirAuthorizationsInTwinOrder() throws Exception {
        Dbpautp0Unload.Contents unload = Dbpautp0Unload.read(Fixtures.appRoot().resolve(Dbpautp0Unload.DATA_FILE));

        assertEquals(22, unload.roots().size());
        assertEquals(202, unload.children().size());
        for (int i = 1; i < unload.children().size(); i++) {
            SegmentTables.Child before = unload.children().get(i - 1);
            SegmentTables.Child after = unload.children().get(i);
            assertEquals(true, before.acctId() < after.acctId()
                    || before.acctId() == after.acctId() && before.detail().key().compareTo(after.detail().key()) < 0,
                    "hierarchical sequence at child " + i);
        }
    }

    @Test
    void everySampleSegmentSurvivesItsImage() throws Exception {
        Dbpautp0Unload.Contents unload = Dbpautp0Unload.read(Fixtures.appRoot().resolve(Dbpautp0Unload.DATA_FILE));

        for (PendingAuthSummary root : unload.roots()) {
            assertEquals(root, PendingAuthSummary.of(root.image()));
        }
        for (SegmentTables.Child child : unload.children()) {
            assertEquals(child.detail(), PendingAuthDetail.of(child.detail().image()));
        }
    }

    @Test
    void packedAndBinaryFieldsAreBytesAndTextIsLatin1() {
        PendingAuthSummary summary = new PendingAuthSummary(12345678901L, 42, "A", List.of("01", "", "", "", "Z"),
                new BigDecimal("1500.00"), new BigDecimal("-2.5"), BigDecimal.ZERO, BigDecimal.ZERO, 300, -2,
                new BigDecimal("99.99"), BigDecimal.ZERO, "");

        byte[] image = summary.image();

        assertEquals(PendingAuthSummary.LENGTH, image.length);
        assertEquals("12345678901c", HexFormat.of().formatHex(image, 0, 6));
        assertEquals("000000042A01", new String(image, 6, 12, ISO_8859_1));
        assertEquals("00000000250d", HexFormat.of().formatHex(image, 32, 38));
        assertEquals("012cfffe", HexFormat.of().formatHex(image, 50, 54));
        assertArrayEquals(PendingAuthSummary.keyImage(12345678901L), java.util.Arrays.copyOf(image, 6));
        assertEquals(summary, PendingAuthSummary.of(image));
    }

    @Test
    void aValueThePictureCannotHoldIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PendingAuthSummary.initialized(1, 1_000_000_000));
        assertThrows(IllegalArgumentException.class, () -> new AuthKey(100000, 0));
        assertThrows(IllegalArgumentException.class, () -> new PendingAuthSummary(1, 1, "AB",
                List.of("", "", "", "", ""), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, ""));
    }
}
