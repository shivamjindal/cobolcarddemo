package com.carddemo.copybook;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Flat layout of a single-record copybook: elementary fields with byte offsets.
 * REDEFINES and OCCURS are rejected because no VSAM record copybook the schema needs uses them.
 */
public record Copybook(String recordName, List<Field> fields) {

    private static final Set<String> COMP = Set.of("COMP", "COMP-4", "COMPUTATIONAL", "COMPUTATIONAL-4", "BINARY");
    private static final Set<String> COMP_3 = Set.of("COMP-3", "COMPUTATIONAL-3", "PACKED-DECIMAL");

    public int length() {
        return fields.isEmpty() ? 0 : fields.getLast().end();
    }

    public static Copybook parse(String source) {
        String recordName = null;
        List<Field> fields = new ArrayList<>();
        int offset = 0;
        for (String statement : codeArea(source).split("\\.(?=\\s|$)")) {
            String[] tokens = statement.trim().split("\\s+");
            if (tokens.length < 2) {
                continue;
            }
            int level = Integer.parseInt(tokens[0]);
            String name = tokens[1];
            if (level == 88 || level == 66) {
                continue;
            }
            if (level == 1) {
                if (recordName != null) {
                    throw new IllegalArgumentException("Multiple 01 records: " + recordName + ", " + name);
                }
                recordName = name;
            }
            String picture = null;
            Pic.Usage usage = Pic.Usage.DISPLAY;
            for (int i = 2; i < tokens.length; i++) {
                String token = tokens[i].toUpperCase();
                if (token.equals("REDEFINES") || token.equals("OCCURS")) {
                    throw new UnsupportedOperationException(token + " in " + name);
                }
                if (token.equals("VALUE")) {
                    break;
                }
                if (token.equals("PIC") || token.equals("PICTURE")) {
                    i++;
                    if (tokens[i].equalsIgnoreCase("IS")) {
                        i++;
                    }
                    picture = tokens[i];
                } else if (COMP.contains(token)) {
                    usage = Pic.Usage.COMP;
                } else if (COMP_3.contains(token)) {
                    usage = Pic.Usage.COMP_3;
                }
            }
            if (picture != null) {
                Field field = new Field(name, offset, Pic.parse(picture, usage));
                fields.add(field);
                offset = field.end();
            }
        }
        if (recordName == null) {
            throw new IllegalArgumentException("No 01 record in copybook");
        }
        return new Copybook(recordName, List.copyOf(fields));
    }

    public Field field(String name) {
        return fields.stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name + " not in " + recordName));
    }

    private static String codeArea(String source) {
        StringBuilder code = new StringBuilder();
        for (String line : source.split("\\R")) {
            if (line.length() < 8 || line.charAt(6) == '*' || line.charAt(6) == '/') {
                continue;
            }
            code.append(line, 7, Math.min(line.length(), 72)).append(' ');
        }
        return code.toString();
    }
}
