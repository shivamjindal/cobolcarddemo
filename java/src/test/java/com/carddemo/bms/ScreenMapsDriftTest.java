package com.carddemo.bms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.carddemo.online.Attribute;
import com.carddemo.online.Color;
import com.carddemo.online.maps.COSGN0A;
import com.carddemo.online.maps.COUSR0A;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenMapsDriftTest {

    @Test
    void checkedInMapsMatchTheBmsSource() throws IOException {
        Map<String, String> generated = ScreenMapGenerator.generate(appRoot());
        Set<String> checkedIn;
        try (Stream<Path> files = Files.list(ScreenMapGenerator.OUTPUT)) {
            checkedIn = files.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
        assertEquals(generated.keySet(), checkedIn,
                "regenerate with: mvn -q exec:java -Dexec.mainClass=com.carddemo.bms.ScreenMapGenerator -Dexec.args=../app");
        for (var file : generated.entrySet()) {
            assertEquals(file.getValue(),
                    Files.readString(ScreenMapGenerator.OUTPUT.resolve(file.getKey()), StandardCharsets.UTF_8),
                    file.getKey() + " is stale; regenerate it");
        }
    }

    @Test
    void readsContinuedLiteralsAndAttributes() {
        assertEquals("________", COSGN0A.PASSWD.bms().initial());
        assertTrue(COSGN0A.PASSWD.bms().has(Attribute.DRK));
        assertTrue(COSGN0A.USERID.bms().has(Attribute.IC));
        assertEquals(Color.RED, COSGN0A.ERRMSG.bms().color());
        assertTrue(COSGN0A.MAP.literals().stream().anyMatch(l -> l.initial()
                .equals("This is a Credit Card Demo Application for Mainframe Modernization")));
        assertEquals("USRID01", COUSR0A.values()[COUSR0A.SEL0001.ordinal() + 1].name());
    }

    private static Path appRoot() {
        String configured = System.getProperty("carddemo.app-root");
        return configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of("..", "app").toAbsolutePath().normalize();
    }
}
