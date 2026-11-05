package com.carddemo.vsam;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListcatParserTest {

    private final ListcatParser parser = new ListcatParser();

    @Test
    void readsDataComponentAttributesAndIgnoresTheSummary() {
        Catalog catalog = parser.parse("""
                1IDCAMS  SYSTEM SERVICES
                0CLUSTER ------- AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS
                      ASSOCIATIONS
                        DATA-----AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS.DAT
                0DATA ---------- AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS.DAT
                      ATTRIBUTES
                        KEYLEN-----------------8     AVGLRECL--------------80
                0       RKP--------------------0     MAXLRECL--------------80
                0AIX ----------- AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX
                        DATA-----AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.DATA
                        CLUSTER--AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS
                0DATA ---------- AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.DATA
                        KEYLEN----------------11
                        AXRKP-----------------16
                        SPANNED       NONUNIQKEY
                                    CLUSTER --------------10
                                    AIX -------------------3
                """.stripIndent());

        assertEquals(1, catalog.clusters().size());
        Cluster users = catalog.clusters().get(0);
        assertEquals("AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS", users.dsn());
        assertEquals(0, users.key().offset());
        assertEquals(8, users.key().length());
        assertEquals(80, users.recordLength());
        assertEquals(1, catalog.indexes().size());
        AlternateIndex cards = catalog.indexes().get(0);
        assertEquals(16, cards.key().offset());
        assertEquals(11, cards.key().length());
        assertFalse(cards.unique());
        assertEquals("AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS", cards.clusterDsn());
    }

    @Test
    void readsTheCardDemoCatalog() throws Exception {
        String text = Files.readString(Fixtures.appRoot().resolve("catlg").resolve("LISTCAT.txt"),
                StandardCharsets.ISO_8859_1);
        Catalog catalog = parser.parse(text);
        assertEquals(10, catalog.clusters().size());
        assertEquals(3, catalog.indexes().size());

        Map<String, int[]> expected = Map.of(
                "ACCTDATA", new int[] {0, 11, 300},
                "CARDDATA", new int[] {0, 16, 150},
                "CARDXREF", new int[] {0, 16, 50},
                "CUSTDATA", new int[] {0, 9, 500},
                "DISCGRP", new int[] {0, 16, 50},
                "TCATBALF", new int[] {0, 17, 50},
                "TRANCATG", new int[] {0, 6, 60},
                "TRANSACT", new int[] {0, 16, 350},
                "TRANTYPE", new int[] {0, 2, 60},
                "USRSEC", new int[] {0, 8, 80});
        for (var entry : expected.entrySet()) {
            Cluster cluster = catalog.clusters().stream()
                    .filter(item -> item.dsn().endsWith("." + entry.getKey() + ".VSAM.KSDS"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(entry.getValue()[0], cluster.key().offset(), entry.getKey());
            assertEquals(entry.getValue()[1], cluster.key().length(), entry.getKey());
            assertEquals(entry.getValue()[2], cluster.recordLength(), entry.getKey());
        }

        assertAix(catalog, "CARDDATA", 16, 11);
        assertAix(catalog, "CARDXREF", 25, 11);
        assertAix(catalog, "TRANSACT", 304, 26);
    }

    private static void assertAix(Catalog catalog, String suffix, int offset, int length) {
        AlternateIndex index = catalog.indexes().stream()
                .filter(item -> item.dsn().contains("." + suffix + ".VSAM.AIX"))
                .findFirst()
                .orElseThrow();
        assertEquals(offset, index.key().offset(), suffix);
        assertEquals(length, index.key().length(), suffix);
        assertFalse(index.unique(), suffix);
        assertTrue(index.clusterDsn().endsWith("." + suffix + ".VSAM.KSDS"), suffix);
    }
}
