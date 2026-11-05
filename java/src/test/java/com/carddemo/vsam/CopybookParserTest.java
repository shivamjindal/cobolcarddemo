package com.carddemo.vsam;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopybookParserTest {

    private final CopybookParser parser = new CopybookParser();

    @Test
    void sizesFieldsSkipsFillersEightyEightsAndValues() {
        Layout layout = parser.parse(Fixtures.fixed(
                "01  REC.",
                "    05  A PIC X(4).",
                "    05  B PIC 9(4).",
                "    05  FILLER PIC X(2).",
                "    05  NAMED-FILLER PIC X(3).",
                "    05  C PIC S9(5) COMP-3.",
                "    05  D PIC 9(4) COMP.",
                "    05  E PIC XXX.",
                "    05  F PIC X(2) VALUE 'AB'.",
                "    88  FLAG VALUE 'A.B'."));

        assertEquals("REC", layout.recordName());
        assertEquals(4 + 4 + 2 + 3 + 3 + 2 + 3 + 2, layout.recordLength());
        assertEquals(7, layout.fields().size());
        assertEquals(1, layout.fillers().size());
        assertEquals("FILLER", layout.fillers().get(0).cobolName());
        assertEquals(8, layout.fillers().get(0).offset());
        assertEquals("NAMED-FILLER", layout.fields().get(2).cobolName());
        assertInstanceOf(Pic.Packed.class, layout.fields().get(3).pic());
        assertEquals(3, layout.fields().get(3).length());
        assertInstanceOf(Pic.Binary.class, layout.fields().get(4).pic());
        assertEquals(2, layout.fields().get(4).length());
        assertEquals(3, layout.fields().get(5).length());
        assertEquals("X(2)", layout.fields().get(6).picClause());
    }

    @Test
    void groupsDoNotConsumeBytesAndCompositeKeysAreContiguous() {
        Layout layout = parser.parse(Fixtures.fixed(
                "01  REC.",
                "    05  KEY-GROUP.",
                "        10  PART-A PIC X(2).",
                "        10  PART-B PIC 9(2).",
                "    05  TAIL PIC X(1)."));

        assertEquals(5, layout.recordLength());
        assertEquals(0, layout.fields().get(0).offset());
        assertEquals(2, layout.fields().get(1).offset());
        assertEquals(4, layout.fields().get(2).offset());
        assertEquals(2, Keys.cover(layout, new KeyRange(0, 4)).size());
    }

    @Test
    void keyThatStraddlesAFieldIsRejected() {
        Layout layout = parser.parse(Fixtures.fixed(
                "01  REC.",
                "    05  A PIC X(4).",
                "    05  B PIC X(4)."));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Keys.cover(layout, new KeyRange(2, 4)));
        assertTrue(error.getMessage().contains("straddles"));
    }

    @Test
    void rejectsRedefinesOccursAndPicturesItCannotSize() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(Fixtures.fixed(
                "01  REC.",
                "    05  A PIC X(2) REDEFINES B.")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(Fixtures.fixed(
                "01  REC.",
                "    05  A PIC X(2) OCCURS 3.")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(Fixtures.fixed(
                "01  REC.",
                "    05  A PIC $$$.")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(Fixtures.fixed(
                "01  REC.",
                "    05  A PIC 9(19) COMP.")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(Fixtures.fixed(
                "01  REC.",
                "    05  A PIC S9(4) COMP-1.")));
    }

    @Test
    void joinsLinesAndIgnoresColumnsPast72AndSequenceNumbers() {
        Layout joined = parser.parse(Fixtures.fixed(
                "01  REC.",
                "    05  LONG-NAME",
                "        PIC X(4)."));
        assertEquals(4, joined.recordLength());

        Layout continued = parser.parse("       01  REC.\n       05  A PIC X\n      -    (4).\n");
        assertEquals(4, continued.recordLength());
        assertEquals("X(4)", continued.fields().get(0).picClause());

        String padded = String.format("%-72s%n", "       01  REC.")
                + String.format("%-72s", "       05  A PIC X(2).") + " PIC 9(99).\n";
        assertEquals(2, parser.parse(padded).recordLength());

        Layout sequenced = parser.parse("000100 01  REC.\n000200 05  A PIC X(3).\n");
        assertEquals(3, sequenced.recordLength());
    }

    @Test
    void anonymousPictureIsFiller() {
        Layout layout = parser.parse(Fixtures.fixed(
                "01  REC.",
                "    05  PIC X(3)."));
        assertEquals(1, layout.fillers().size());
        assertEquals(0, layout.fields().size());
    }

    @Test
    void cardDemoCopybooksMatchTheirRecordLengths() throws Exception {
        Map<String, Integer> lengths = Map.of(
                "CVACT01Y", 300,
                "CVACT02Y", 150,
                "CVACT03Y", 50,
                "CVCUS01Y", 500,
                "CVTRA01Y", 50,
                "CVTRA02Y", 50,
                "CVTRA03Y", 60,
                "CVTRA04Y", 60,
                "CVTRA05Y", 350,
                "CSUSR01Y", 80);
        for (var entry : lengths.entrySet()) {
            String text = Files.readString(
                    Fixtures.appRoot().resolve("cpy").resolve(entry.getKey() + ".cpy"),
                    StandardCharsets.ISO_8859_1);
            Layout layout = parser.parse(text);
            assertEquals(entry.getValue(), layout.recordLength(), entry.getKey());
        }
        String accounts = Files.readString(
                Fixtures.appRoot().resolve("cpy").resolve("CVACT01Y.cpy"), StandardCharsets.ISO_8859_1);
        Layout layout = parser.parse(accounts);
        Field expiration = layout.fields().stream()
                .filter(field -> field.cobolName().equals("ACCT-EXPIRAION-DATE"))
                .findFirst()
                .orElseThrow();
        assertEquals(58, expiration.offset());
        assertEquals(10, expiration.length());
        assertEquals("X(10)", expiration.picClause());
        assertEquals(12, layout.fields().size());
        assertEquals(178, layout.fillers().get(0).length());

        String users = Files.readString(
                Fixtures.appRoot().resolve("cpy").resolve("CSUSR01Y.cpy"), StandardCharsets.ISO_8859_1);
        Layout userLayout = parser.parse(users);
        assertEquals(6, userLayout.fields().size());
        assertTrue(userLayout.fillers().isEmpty());
        assertEquals("SEC-USR-FILLER", userLayout.fields().get(5).cobolName());
    }
}
