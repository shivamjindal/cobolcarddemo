package com.carddemo;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks every {@link LegacyDefect} against the recorded goldens, then writes {@code docs/legacy-defects.md}. */
class LegacyDefectRegisterTest {

    private static final Path CASES = Path.of("..", "characterization", "cases");
    private static final Path REGISTER = Path.of("docs", "legacy-defects.md");

    private record Entry(LegacyDefect defect, Class<?> owner, String location) {}

    @Test
    void everyDefectHasOneHomeAndRecordedGoldens() throws Exception {
        List<Entry> entries = annotated();
        Set<String> goldens = goldenIds();
        assertFalse(entries.isEmpty(), "no @LegacyDefect found under " + classesRoot());

        Map<String, List<String>> homes = entries.stream()
                .collect(Collectors.groupingBy(e -> e.defect().id(), Collectors.mapping(Entry::location, Collectors.toList())));
        homes.forEach((id, locations) -> assertEquals(1, locations.size(), id + " is annotated in " + locations));
        for (Entry entry : entries) {
            for (String golden : entry.defect().goldens()) {
                assertTrue(goldens.contains(golden),
                        entry.defect().id() + " names golden " + golden + ", which is not in characterization/cases");
            }
        }

        Files.writeString(REGISTER, render(entries), StandardCharsets.UTF_8);
    }

    private static List<Entry> annotated() throws Exception {
        Path root = classesRoot();
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = root.relativize(file).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", "");
                Class<?> type = Class.forName(name, false, LegacyDefectRegisterTest.class.getClassLoader());
                LegacyDefect onType = type.getAnnotation(LegacyDefect.class);
                if (onType != null) {
                    entries.add(new Entry(onType, type, simpleName(type)));
                }
                for (Method method : type.getDeclaredMethods()) {
                    LegacyDefect onMethod = method.getAnnotation(LegacyDefect.class);
                    if (onMethod != null) {
                        entries.add(new Entry(onMethod, type, simpleName(type) + "." + method.getName()));
                    }
                }
            }
        }
        entries.sort(Comparator.comparing(e -> e.defect().id()));
        return entries;
    }

    private static Path classesRoot() throws Exception {
        return Path.of(LegacyDefect.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static String simpleName(Class<?> type) {
        return type.getName().substring(type.getPackageName().length() + 1).replace('$', '.');
    }

    private static Set<String> goldenIds() throws IOException {
        ObjectMapper json = new ObjectMapper();
        Set<String> ids = new HashSet<>();
        try (Stream<Path> files = Files.walk(CASES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                for (JsonNode testCase : json.readTree(file.toFile()).path("cases")) {
                    ids.add(testCase.path("id").asText());
                }
            }
        }
        assertFalse(ids.isEmpty(), "no golden cases under " + CASES.toAbsolutePath().normalize());
        return ids;
    }

    private static String render(List<Entry> entries) {
        StringBuilder md = new StringBuilder("""
                # Legacy defects

                The Java port reproduces these CardDemo defects so that it behaves as the COBOL programs do. \
                Each one lives in a single annotated method. Fixing a defect means changing that method and \
                re-recording the goldens that pin it.

                `LegacyDefectRegisterTest` writes this file from the `@LegacyDefect` annotations during \
                `mvn verify`. Edit the annotations, not this file.
                """);
        for (Entry entry : entries) {
            LegacyDefect defect = entry.defect();
            Class<?> topLevel = entry.owner();
            while (topLevel.getEnclosingClass() != null) {
                topLevel = topLevel.getEnclosingClass();
            }
            String source = "../src/main/java/" + topLevel.getName().replace('.', '/') + ".java";
            md.append("\n## `").append(defect.id()).append("`\n\n")
                    .append(sentence(defect.behavior())).append("\n\n")
                    .append("- Lives in [`").append(entry.location()).append("`](").append(source).append(").\n")
                    .append(defect.side() == LegacyDefect.Side.TERMINAL
                            ? "- Comes from BMS or the 3270, and the program does not guard against it.\n"
                            : "- Comes from the program logic.\n")
                    .append(defect.goldens().length == 0
                            ? "- No golden pins it yet. It is read from the source.\n"
                            : "- Pinned by " + Stream.of(defect.goldens()).map(g -> "`" + g + "`")
                                    .collect(Collectors.joining(", ")) + ".\n")
                    .append("- Fix: ").append(defect.fix()).append(".\n");
        }
        return md.toString();
    }

    private static String sentence(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
    }
}
