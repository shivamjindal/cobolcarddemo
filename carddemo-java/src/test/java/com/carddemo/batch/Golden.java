package com.carddemo.batch;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The batch golden masters and the Java port's departures from them. characterization/batch/golden is what the
 * legacy programs write; golden-fixed replaces each artifact that the legacy programs write differently once the
 * harness's FIXES are applied to their source. batch-overrides.yaml covers what is left: a rewrite applies a
 * regex to every master its glob matches, and an override replaces one block of golden lines with the lines the
 * Java job writes instead. Each must still be needed.
 */
final class Golden {

    static final Path ROOT = Path.of(System.getProperty("carddemo.batch-characterization"));

    record Rewrite(String artifacts, String pattern, String replacement, String reason) {
    }

    record Override(String artifact, String golden, String actual, String reason) {
    }

    record Master(String text, boolean fixed) {
    }

    enum Outcome { MATCHED, FIXED, OVERRIDDEN, DIFFERS, MISSING, NOT_PORTED }

    record Result(String artifact, Outcome outcome, String detail) {
    }

    private final List<Rewrite> rewrites = new ArrayList<>();
    private final List<Override> overrides = new ArrayList<>();
    private final List<Rewrite> unusedRewrites;
    private final List<Override> unusedOverrides;

    @SuppressWarnings("unchecked")
    Golden() {
        try (InputStream in = Golden.class.getResourceAsStream("/batch-overrides.yaml")) {
            Map<String, List<Map<String, String>>> yaml = new Yaml().load(in);
            for (var r : yaml.getOrDefault("rewrites", List.of())) {
                rewrites.add(new Rewrite(r.get("artifacts"), r.get("pattern"), r.get("replacement"), reason(r)));
            }
            for (var o : yaml.getOrDefault("overrides", List.of())) {
                overrides.add(new Override(o.get("artifact"), o.get("golden"), o.getOrDefault("actual", ""), reason(o)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        unusedRewrites = new ArrayList<>(rewrites);
        unusedOverrides = new ArrayList<>(overrides);
    }

    private static String reason(Map<String, String> entry) {
        String reason = entry.get("reason");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("batch-overrides.yaml entry without a reason: " + entry);
        }
        return reason;
    }

    /** Every golden artifact, keyed by job/name without the .txt suffix, with golden-fixed taking precedence. */
    static Map<String, Master> load() {
        Map<String, Master> masters = new TreeMap<>();
        read("golden").forEach((name, text) -> masters.put(name, new Master(text, false)));
        read("golden-fixed").forEach((name, text) -> masters.put(name, new Master(text, true)));
        return masters;
    }

    private static Map<String, String> read(String dirName) {
        Path dir = ROOT.resolve(dirName);
        Map<String, String> texts = new TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = dir.relativize(file).toString().replace('\\', '/');
                texts.put(name.substring(0, name.length() - ".txt".length()), Files.readString(file, StandardCharsets.ISO_8859_1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return texts;
    }

    /** One record per line: printable ASCII except backslash as-is, other bytes as \xNN, trailing spaces dropped. */
    static String render(List<String> records) {
        StringBuilder out = new StringBuilder();
        for (String record : records) {
            int start = out.length();
            for (char c : record.toCharArray()) {
                if (c >= 32 && c < 127 && c != '\\') {
                    out.append(c);
                } else {
                    out.append("\\x%02x".formatted((int) c));
                }
            }
            int end = out.length();
            while (end > start && out.charAt(end - 1) == ' ') {
                end--;
            }
            out.setLength(end);
            out.append('\n');
        }
        return out.toString();
    }

    /** What the Java job should write for {@code artifact}: the golden text with its rewrites and overrides applied. */
    String expected(String artifact, String golden, List<Override> applied) {
        String text = golden;
        for (Rewrite r : rewrites) {
            if (FileSystems.getDefault().getPathMatcher("glob:" + r.artifacts()).matches(Path.of(artifact))) {
                String rewritten = text.replaceAll(r.pattern(), r.replacement());
                if (!rewritten.equals(text)) {
                    unusedRewrites.remove(r);
                }
                text = rewritten;
            }
        }
        if (!artifact.endsWith(".sysout")) {
            text = text.replaceAll("(?m) +$", "");
        }
        for (Override o : overrides) {
            if (!o.artifact().equals(artifact)) {
                continue;
            }
            int at = text.indexOf(o.golden());
            if (at < 0 || text.indexOf(o.golden(), at + 1) >= 0) {
                throw new IllegalStateException("override for " + artifact + " must match exactly one block of golden lines: "
                        + o.reason());
            }
            text = text.substring(0, at) + o.actual() + text.substring(at + o.golden().length());
            unusedOverrides.remove(o);
            applied.add(o);
        }
        return text;
    }

    Result compare(String artifact, Master master, String actual) {
        List<Override> applied = new ArrayList<>();
        String expected = expected(artifact, master.text(), applied);
        if (expected.equals(actual)) {
            Outcome outcome = !applied.isEmpty() ? Outcome.OVERRIDDEN : master.fixed() ? Outcome.FIXED : Outcome.MATCHED;
            return new Result(artifact, outcome, String.join(" | ", applied.stream().map(Override::reason).toList()));
        }
        return new Result(artifact, Outcome.DIFFERS, firstDifferences(expected, actual));
    }

    List<String> unused() {
        List<String> unused = new ArrayList<>();
        unusedRewrites.forEach(r -> unused.add("rewrite " + r.artifacts() + ": " + r.reason()));
        unusedOverrides.forEach(o -> unused.add("override " + o.artifact() + ": " + o.reason()));
        return unused;
    }

    private static String firstDifferences(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        StringBuilder out = new StringBuilder("expected %d lines, got %d".formatted(e.length - 1, a.length - 1));
        int shown = 0;
        for (int i = 0; i < Math.max(e.length, a.length) && shown < 5; i++) {
            String el = i < e.length ? e[i] : "<none>";
            String al = i < a.length ? a[i] : "<none>";
            if (!el.equals(al)) {
                out.append("%n  line %d%n    golden: %s%n    actual: %s".formatted(i + 1, el, al));
                shown++;
            }
        }
        return out.toString();
    }
}
