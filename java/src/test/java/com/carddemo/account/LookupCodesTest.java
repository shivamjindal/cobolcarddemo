package com.carddemo.account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.carddemo.vsam.Fixtures;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LookupCodesTest {

    private static final Pattern LEVEL = Pattern.compile("\\b(?:88|0[12])\\s+([A-Z0-9-]+)");
    private static final Pattern LITERAL = Pattern.compile("'([^']*)'");

    @Test
    void generalPurposeAreaCodesAreCslkpcdy() throws IOException {
        assertEquals(values("VALID-GENERAL-PURP-CODE"), LookupCodes.GENERAL_PURPOSE_AREA_CODES);
    }

    @Test
    void stateCodesAreCslkpcdy() throws IOException {
        assertEquals(values("VALID-US-STATE-CODE"), LookupCodes.STATE_CODES);
    }

    @Test
    void stateZipPrefixesAreCslkpcdy() throws IOException {
        assertEquals(values("VALID-US-STATE-ZIP-CD2-COMBO"), LookupCodes.STATE_ZIP_PREFIXES);
    }

    /** The VALUE literals of one 88 level, read from the code area of each non-comment line, tabs at every 8th column. */
    private static Set<String> values(String condition) throws IOException {
        StringBuilder code = new StringBuilder();
        for (String line : Files.readAllLines(Fixtures.appRoot().resolve("cpy").resolve("CSLKPCDY.cpy"),
                StandardCharsets.ISO_8859_1)) {
            String expanded = expandTabs(line);
            if (expanded.length() > 6 && expanded.charAt(6) != '*') {
                code.append(expanded, 7, Math.min(expanded.length(), 72)).append(' ');
            }
        }
        List<Integer> starts = new ArrayList<>();
        int start = -1;
        Matcher level = LEVEL.matcher(code);
        while (level.find()) {
            starts.add(level.start());
            if (level.group(1).equals(condition)) {
                start = level.end();
            }
        }
        int from = start;
        int end = starts.stream().filter(at -> at > from).findFirst().orElse(code.length());
        Set<String> values = new HashSet<>();
        Matcher literal = LITERAL.matcher(code.subSequence(start, end));
        while (literal.find()) {
            values.add(literal.group(1));
        }
        return values;
    }

    private static String expandTabs(String line) {
        StringBuilder expanded = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '\t') {
                expanded.append(" ".repeat(8 - expanded.length() % 8));
            } else {
                expanded.append(c);
            }
        }
        return expanded.toString();
    }
}
