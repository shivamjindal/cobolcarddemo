package com.carddemo.menu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.carddemo.online.ProgramName;
import com.carddemo.vsam.Fixtures;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MenuTablesTest {

    private static final Pattern VALUE = Pattern.compile("VALUE\\s+(?:'([^']*)'|(\\d+))");

    @Test
    void mainMenuIsComen02y() throws IOException {
        List<String> values = values("COMEN02Y.cpy");
        assertEquals(MenuTables.MAIN.size(), Integer.parseInt(values.get(0)));
        for (int i = 0; i < MenuTables.MAIN.size(); i++) {
            List<String> option = values.subList(1 + 4 * i, 5 + 4 * i);
            assertEquals(entry(option), MenuTables.MAIN.get(i));
            assertEquals("U", option.get(3), "COMEN01C's admin-only check is not ported");
        }
        assertEquals(1 + 4 * MenuTables.MAIN.size(), values.size());
    }

    @Test
    void adminMenuIsCoadm02y() throws IOException {
        List<String> values = values("COADM02Y.cpy");
        assertEquals(MenuTables.ADMIN.size(), Integer.parseInt(values.get(0)));
        for (int i = 0; i < MenuTables.ADMIN.size(); i++) {
            assertEquals(entry(values.subList(1 + 3 * i, 4 + 3 * i)), MenuTables.ADMIN.get(i));
        }
        assertEquals(1 + 3 * MenuTables.ADMIN.size(), values.size());
    }

    @Test
    void noOptionReachesABranchTheMenusDoNotPort() {
        for (MenuTables.Entry entry : Stream.concat(MenuTables.MAIN.stream(), MenuTables.ADMIN.stream()).toList()) {
            assertFalse(entry.program().value().startsWith("DUMMY"), entry.toString());
            assertFalse(entry.name().contains("  "), "COMEN01C cuts a name at two spaces: " + entry);
        }
    }

    private static MenuTables.Entry entry(List<String> values) {
        return new MenuTables.Entry(Integer.parseInt(values.get(0)), values.get(1).stripTrailing(),
                ProgramName.of(values.get(2)));
    }

    /** Every VALUE literal of the copybook in source order, from the code area of each non-comment line. */
    private static List<String> values(String copybook) throws IOException {
        StringBuilder code = new StringBuilder();
        for (String line : Files.readAllLines(Fixtures.appRoot().resolve("cpy").resolve(copybook), StandardCharsets.ISO_8859_1)) {
            if (line.length() > 6 && line.charAt(6) != '*') {
                code.append(line, 7, Math.min(line.length(), 72)).append(' ');
            }
        }
        List<String> values = new ArrayList<>();
        Matcher value = VALUE.matcher(code);
        while (value.find()) {
            values.add(value.group(1) != null ? value.group(1) : value.group(2));
        }
        return values;
    }
}
