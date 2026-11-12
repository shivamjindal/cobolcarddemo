package com.carddemo.accounts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LookupsTest {

    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    @Test
    void generalPurposeAreaCodesMatchTheCopybook() throws IOException {
        assertThat(Lookups.GENERAL_PURPOSE_AREA_CODES).isEqualTo(values("VALID-GENERAL-PURP-CODE"));
    }

    @Test
    void stateCodesMatchTheCopybook() throws IOException {
        assertThat(Lookups.STATE_CODES).isEqualTo(values("VALID-US-STATE-CODE"));
    }

    @Test
    void stateZipPrefixesMatchTheCopybook() throws IOException {
        assertThat(Lookups.STATE_ZIP_PREFIXES).isEqualTo(values("VALID-US-STATE-ZIP-CD2-COMBO"));
    }

    /** The VALUES of one 88 level, skipping comment lines, up to the period that ends it. */
    private static Set<String> values(String condition) throws IOException {
        List<String> lines = Files.readAllLines(Path.of(System.getProperty("carddemo.legacy-app"), "cpy", "CSLKPCDY.cpy"));
        Set<String> values = new LinkedHashSet<>();
        boolean inside = false;
        for (String line : lines) {
            if (line.length() > 6 && line.charAt(6) == '*') {
                continue;
            }
            inside |= line.matches("\\s*88\\s+" + condition + "\\s+VALUES.*");
            if (!inside) {
                continue;
            }
            Matcher quoted = QUOTED.matcher(line);
            while (quoted.find()) {
                values.add(quoted.group(1));
            }
            if (line.strip().endsWith(".")) {
                break;
            }
        }
        assertThat(values).as(condition).isNotEmpty();
        return values;
    }
}
