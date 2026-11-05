package com.carddemo.db2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Db2Test {

    @Test
    void sqlstatesMapToTheSqlcodesTheHarnessRaises() {
        assertEquals(-803, Db2.sqlcode("23505", Db2.Verb.INSERT));
        assertEquals(-532, Db2.sqlcode("23503", Db2.Verb.DELETE));
        assertEquals(-530, Db2.sqlcode("23503", Db2.Verb.INSERT));
        assertEquals(-530, Db2.sqlcode("23503", Db2.Verb.UPDATE));
        assertEquals(-404, Db2.sqlcode("22001", Db2.Verb.UPDATE));
        assertEquals(-407, Db2.sqlcode("23502", Db2.Verb.INSERT));
        assertEquals(-811, Db2.sqlcode("21000", Db2.Verb.SELECT));
        assertEquals(-501, Db2.sqlcode("34000", Db2.Verb.FETCH));
        assertEquals(-502, Db2.sqlcode("42P03", Db2.Verb.OPEN));
        assertEquals(-911, Db2.sqlcode("40P01", Db2.Verb.UPDATE));
        assertEquals(-911, Db2.sqlcode("55P03", Db2.Verb.UPDATE));
        assertEquals(-420, Db2.sqlcode("22P02", Db2.Verb.SELECT));
        assertEquals(Db2.UNMAPPED, Db2.sqlcode("42601", Db2.Verb.SELECT));
    }

    @Test
    void sqlcodeEditsAsPicMinusMinusMinusMinusNine() {
        assertEquals(" -803", code(-803).sqlcodeEdited());
        assertEquals("  100", code(100).sqlcodeEdited());
        assertEquals("    0", code(0).sqlcodeEdited());
        assertEquals("-9999", code(-9999).sqlcodeEdited());
    }

    @Test
    void sqlerrmcHoldsSeventyCharacters() {
        Sqlca ca = new Sqlca(-803, "23505", "x".repeat(90), 0);
        assertEquals(70, ca.sqlerrml());
    }

    /** {@code dsntiac-lrecl72.txt} is what the harness's DSNTIAC writes for a 720-byte area of 72-byte lines. */
    @Test
    void dsntiacFormatsAsTheHarnessDoes() throws IOException {
        List<String> golden;
        try (InputStream in = Db2Test.class.getResourceAsStream("/db2/dsntiac-lrecl72.txt")) {
            golden = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).lines().toList();
        }
        for (int at = 0; at < golden.size(); at += 11) {
            int sqlcode = Integer.parseInt(golden.get(at));
            List<String> expected = golden.subList(at + 1, at + 11).stream()
                    .map(line -> line.substring(1, line.length() - 1)).toList();
            String area = Dsntiac.format(code(sqlcode), 720, 72);
            List<String> actual = new ArrayList<>();
            for (int line = 0; line < 10; line++) {
                actual.add(area.substring(line * 72, line * 72 + 72));
            }
            assertEquals(expected, actual, "SQLCODE " + sqlcode);
        }
    }

    private static Sqlca code(int sqlcode) {
        return new Sqlca(sqlcode, "00000", "", 0);
    }
}
