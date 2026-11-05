package com.carddemo.bms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.carddemo.online.Color;

/**
 * Writes one field enum per BMS map into {@code com.carddemo.online.maps}, plus the {@code Maps} registry.
 * Field order follows the {@code xxxL} items of the symbolic map copybook, as the characterization
 * harness orders them, because BMS resolves several {@code -1} cursor lengths by that order.
 */
public final class ScreenMapGenerator {

    public static final Path OUTPUT = Path.of("src/main/java/com/carddemo/online/maps");

    /** The app root, then each optional extension that ships BMS maps. */
    private static final List<String> MODULES = List.of("", "app-authorization-ims-db2-mq", "app-transaction-type-db2");

    private static final Pattern MACRO = Pattern.compile("(\\S*)\\s+(DFHMSD|DFHMDI|DFHMDF)\\s*(.*)");
    private static final Pattern LENGTH_ITEM = Pattern.compile("\\s*02\\s+(\\w+)L\\s+COMP\\b.*");
    private static final Pattern INPUT_ITEM = Pattern.compile("\\s*02\\s+(\\w+)I\\s+PIC(?:TURE)?\\s+(\\S+)\\.\\s*");
    private static final Pattern REPEAT = Pattern.compile("(.)\\((\\d+)\\)");

    private ScreenMapGenerator() {}

    record Dfhmdf(String name, int row, int col, int length, String attributes, String initial, String color,
            String hilight) {}

    record BmsMap(String mapset, String name, List<Dfhmdf> fields) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ScreenMapGenerator <appRoot>");
            System.exit(2);
        }
        Map<String, String> files = generate(Path.of(args[0]));
        Files.createDirectories(OUTPUT);
        try (Stream<Path> old = Files.list(OUTPUT)) {
            for (Path path : old.toList()) {
                Files.delete(path);
            }
        }
        for (var file : files.entrySet()) {
            Files.writeString(OUTPUT.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** File name to Java source for every map under {@code bms} of the app root and of each optional extension. */
    public static Map<String, String> generate(Path appRoot) throws IOException {
        Map<String, String> files = new TreeMap<>();
        List<String> mapNames = new ArrayList<>();
        for (String module : MODULES) {
            Path root = appRoot.resolve(module);
            List<Path> sources;
            try (Stream<Path> bms = Files.list(root.resolve("bms"))) {
                sources = bms.filter(p -> p.getFileName().toString().endsWith(".bms")).sorted().toList();
            }
            for (Path source : sources) {
                String mapset = source.getFileName().toString().replaceFirst("\\.bms$", "");
                Path copybook = root.resolve("cpy-bms").resolve(mapset + ".CPY");
                if (!Files.exists(copybook)) {
                    copybook = root.resolve("cpy-bms").resolve(mapset + ".cpy");
                }
                BmsMap map = parse(Files.readString(source, StandardCharsets.ISO_8859_1));
                Map<String, Integer> order = inputLengths(Files.readString(copybook, StandardCharsets.ISO_8859_1), map.name());
                files.put(map.name() + ".java", render(map, order, appPath(appRoot, source), appPath(appRoot, copybook)));
                mapNames.add(map.name());
            }
        }
        files.put("Maps.java", registry(mapNames));
        return files;
    }

    static BmsMap parse(String source) {
        String mapset = null;
        String mapName = null;
        List<Dfhmdf> fields = new ArrayList<>();
        for (String statement : statements(source)) {
            Matcher macro = MACRO.matcher(statement);
            if (!macro.matches()) {
                continue;
            }
            String name = macro.group(1);
            switch (macro.group(2)) {
                case "DFHMSD" -> {
                    if (mapset == null) {
                        mapset = name;
                    }
                }
                case "DFHMDI" -> {
                    if (mapName != null) {
                        throw new IllegalArgumentException("more than one DFHMDI in mapset " + mapset);
                    }
                    mapName = name;
                }
                default -> {
                    if (mapName != null) {
                        fields.add(field(name, operands(macro.group(3).strip())));
                    }
                }
            }
        }
        return new BmsMap(mapset, mapName, fields);
    }

    private static Dfhmdf field(String name, String operands) {
        int row = 0;
        int col = 0;
        int length = 0;
        String attributes = "";
        String initial = "";
        String color = null;
        String hilight = null;
        for (String param : splitParams(operands)) {
            String p = param.strip();
            int eq = p.indexOf('=');
            String key = eq < 0 ? p : p.substring(0, eq);
            String value = eq < 0 ? "" : p.substring(eq + 1);
            switch (key) {
                case "ATTRB" -> attributes = value.replaceAll("^\\(|\\)$", "");
                case "INITIAL" -> {
                    String quoted = value.strip();
                    initial = quoted.substring(1, quoted.length() - 1).replace("''", "'").replace("&&", "&");
                }
                case "COLOR" -> color = value;
                case "LENGTH" -> length = Integer.parseInt(value);
                case "HILIGHT" -> hilight = value;
                case "POS" -> {
                    String[] rc = value.replaceAll("[()]", "").split(",");
                    row = Integer.parseInt(rc[0].strip());
                    col = Integer.parseInt(rc[1].strip());
                }
                default -> {
                }
            }
        }
        return new Dfhmdf(name.isEmpty() ? null : name, row, col, length, attributes, initial, color, hilight);
    }

    /** Assembler statements: continuation when column 72 is not blank, resuming at column 16. */
    static List<String> statements(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder statement = new StringBuilder();
        boolean continuing = false;
        for (String line : text.split("\\R")) {
            if (line.startsWith("*") && !continuing) {
                continue;
            }
            String body = continuing ? slice(line, 15, 71) : slice(line, 0, 71);
            continuing = line.length() > 71 && line.charAt(71) != ' ';
            boolean inLiteral = (statement + body).chars().filter(c -> c == '\'').count() % 2 == 1;
            statement.append(continuing && inLiteral ? body : body.stripTrailing());
            if (!continuing) {
                if (!statement.toString().isBlank()) {
                    out.add(statement.toString());
                }
                statement.setLength(0);
            }
        }
        return out;
    }

    private static String slice(String line, int from, int to) {
        return from >= line.length() ? "" : line.substring(from, Math.min(to, line.length()));
    }

    /** The operand field: everything up to the first blank outside quotes. */
    private static String operands(String s) {
        boolean quote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                quote = !quote;
            } else if (c == ' ' && !quote) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    private static List<String> splitParams(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote) {
                current.append(c);
                if (c == '\'') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++;
                    } else {
                        quote = false;
                    }
                }
            } else if (c == '\'') {
                quote = true;
                current.append(c);
            } else if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** Field name to {@code xxxI} length, in the order of the {@code xxxL} items of record {@code <map>I}. */
    static Map<String, Integer> inputLengths(String copybook, String mapName) {
        List<String> order = new ArrayList<>();
        Map<String, Integer> lengths = new LinkedHashMap<>();
        boolean inRecord = false;
        for (String raw : copybook.split("\\R")) {
            if (raw.length() > 6 && raw.charAt(6) == '*') {
                continue;
            }
            String line = raw.length() > 7 ? raw.substring(7, Math.min(72, raw.length())) : "";
            String trimmed = line.strip();
            if (trimmed.startsWith("01 ")) {
                inRecord = trimmed.matches("01\\s+" + mapName + "I\\.");
                continue;
            }
            if (!inRecord) {
                continue;
            }
            Matcher l = LENGTH_ITEM.matcher(line);
            if (l.matches()) {
                order.add(l.group(1));
                continue;
            }
            Matcher i = INPUT_ITEM.matcher(line);
            if (i.matches()) {
                lengths.put(i.group(1), pictureLength(i.group(2)));
            }
        }
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String name : order) {
            if (lengths.containsKey(name)) {
                ordered.put(name, lengths.get(name));
            }
        }
        return ordered;
    }

    private static int pictureLength(String picture) {
        String expanded = REPEAT.matcher(picture.toUpperCase()).replaceAll(m -> m.group(1).repeat(Integer.parseInt(m.group(2))));
        return expanded.replaceFirst("^S", "").replace("V", "").length();
    }

    private static String appPath(Path appRoot, Path file) {
        return "app/" + appRoot.relativize(file).toString().replace('\\', '/');
    }

    private static String render(BmsMap map, Map<String, Integer> order, String source, String copybook) {
        Map<String, Dfhmdf> named = new LinkedHashMap<>();
        List<Dfhmdf> literals = new ArrayList<>();
        for (Dfhmdf field : map.fields()) {
            if (field.name() == null) {
                literals.add(field);
            } else {
                named.put(field.name(), field);
            }
        }
        if (!named.keySet().equals(order.keySet())) {
            throw new IllegalStateException(map.name() + ": BMS fields " + named.keySet()
                    + " differ from copybook fields " + order.keySet());
        }
        StringBuilder out = new StringBuilder();
        out.append("// Generated by com.carddemo.bms.ScreenMapGenerator from ").append(source)
                .append(" and ").append(copybook).append(". Do not edit.\n");
        out.append("package com.carddemo.online.maps;\n\n");
        out.append("import java.util.List;\n\n");
        out.append("import com.carddemo.online.BmsField;\n");
        out.append("import com.carddemo.online.Color;\n");
        out.append("import com.carddemo.online.MapField;\n");
        out.append("import com.carddemo.online.MapId;\n\n");
        out.append("public enum ").append(map.name()).append(" implements MapField {\n");
        List<String> constants = new ArrayList<>();
        for (var entry : order.entrySet()) {
            Dfhmdf f = named.get(entry.getKey());
            if (f.length() != entry.getValue()) {
                throw new IllegalStateException(map.name() + "." + f.name() + ": BMS LENGTH " + f.length()
                        + " but copybook length " + entry.getValue());
            }
            constants.add("    " + f.name() + "(" + arguments(f) + ")");
        }
        out.append(String.join(",\n", constants)).append(";\n\n");
        out.append("    public static final MapId<").append(map.name()).append("> MAP = new MapId<>(\"")
                .append(map.mapset()).append("\", \"").append(map.name()).append("\", ").append(map.name())
                .append(".class, List.of(");
        List<String> literalLines = new ArrayList<>();
        for (Dfhmdf literal : literals) {
            literalLines.add("\n            BmsField.of(" + arguments(literal) + ")");
        }
        out.append(String.join(",", literalLines)).append("));\n\n");
        out.append("    private final BmsField bms;\n\n");
        out.append("    ").append(map.name())
                .append("(int row, int col, int length, String attributes, String initial, Color color, String hilight) {\n");
        out.append("        bms = BmsField.of(row, col, length, attributes, initial, color, hilight);\n");
        out.append("    }\n\n");
        out.append("    @Override\n");
        out.append("    public BmsField bms() {\n");
        out.append("        return bms;\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }

    private static String arguments(Dfhmdf f) {
        String color = f.color() == null ? "null" : "Color." + Color.ofBms(f.color()).name();
        String hilight = f.hilight() == null ? "null" : javaString(f.hilight());
        return f.row() + ", " + f.col() + ", " + f.length() + ", " + javaString(f.attributes()) + ", "
                + javaString(f.initial()) + ", " + color + ", " + hilight;
    }

    private static String javaString(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7e) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String registry(List<String> mapNames) {
        StringBuilder out = new StringBuilder();
        out.append("// Generated by com.carddemo.bms.ScreenMapGenerator. Do not edit.\n");
        out.append("package com.carddemo.online.maps;\n\n");
        out.append("import java.util.List;\n\n");
        out.append("import com.carddemo.online.MapId;\n\n");
        out.append("public final class Maps {\n\n");
        out.append("    public static final List<MapId<?>> ALL = List.of(");
        List<String> entries = mapNames.stream().map(n -> "\n            " + n + ".MAP").toList();
        out.append(String.join(",", entries)).append(");\n\n");
        out.append("    private Maps() {}\n");
        out.append("}\n");
        return out.toString();
    }
}
