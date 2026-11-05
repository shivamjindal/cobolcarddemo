package com.carddemo.vsam;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ListcatParser {

    private static final Pattern HEADER = Pattern.compile(
            "^(CLUSTER|AIX|DATA|INDEX|NONVSAM|PATH)\\s+-+\\s+(\\S+)$");

    public Catalog parse(String text) {
        List<Entry> entries = entries(text);
        List<Cluster> clusters = new ArrayList<>();
        List<AlternateIndex> indexes = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.type.equals("CLUSTER")) {
                Entry data = find(entries, "DATA", association(entry, "DATA"));
                clusters.add(new Cluster(
                        entry.name,
                        new KeyRange(number(data, "RKP"), number(data, "KEYLEN")),
                        number(data, "MAXLRECL")));
            } else if (entry.type.equals("AIX")) {
                Entry data = find(entries, "DATA", association(entry, "DATA"));
                boolean nonUnique = token(data, "NONUNIQKEY");
                boolean unique = token(data, "UNIQUEKEY");
                if (nonUnique == unique) {
                    throw new IllegalArgumentException(entry.name + " does not say NONUNIQKEY or UNIQUEKEY");
                }
                indexes.add(new AlternateIndex(
                        entry.name,
                        association(entry, "CLUSTER"),
                        new KeyRange(number(data, "AXRKP"), number(data, "KEYLEN")),
                        unique));
            }
        }
        return new Catalog(clusters, indexes);
    }

    private static List<Entry> entries(String text) {
        List<Entry> entries = new ArrayList<>();
        Entry current = null;
        for (String raw : text.split("\\R", -1)) {
            String line = raw;
            if (!line.isEmpty() && (line.charAt(0) == '0' || line.charAt(0) == '1')) {
                line = line.substring(1);
            }
            line = line.stripTrailing();
            Matcher header = HEADER.matcher(line.strip());
            if (header.matches() && header.group(2).contains(".")) {
                current = new Entry(header.group(1), header.group(2));
                entries.add(current);
                continue;
            }
            if (current != null) {
                current.lines.add(line);
            }
        }
        return entries;
    }

    private static Entry find(List<Entry> entries, String type, String name) {
        List<Entry> found = entries.stream()
                .filter(entry -> entry.type.equals(type) && entry.name.equals(name))
                .toList();
        if (found.size() != 1) {
            throw new IllegalArgumentException(type + " " + name + " found " + found.size() + " times");
        }
        return found.get(0);
    }

    private static String association(Entry entry, String kind) {
        Pattern pattern = Pattern.compile("\\b" + kind + "-{2,}(\\S+)");
        String found = null;
        for (String line : entry.lines) {
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (found != null && !found.equals(name)) {
                    throw new IllegalArgumentException(entry.name + " has more than one " + kind);
                }
                found = name;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(entry.name + " has no " + kind + " association");
        }
        return found;
    }

    private static int number(Entry entry, String name) {
        Pattern pattern = Pattern.compile("\\b" + name + "-+\\s*(\\d+)");
        Integer found = null;
        for (String line : entry.lines) {
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                int value = Integer.parseInt(matcher.group(1));
                if (found != null && found != value) {
                    throw new IllegalArgumentException(entry.name + " has conflicting " + name);
                }
                found = value;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(entry.name + " has no " + name);
        }
        return found;
    }

    private static boolean token(Entry entry, String token) {
        for (String line : entry.lines) {
            for (String word : line.trim().split("\\s+")) {
                if (word.equals(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class Entry {
        private final String type;
        private final String name;
        private final List<String> lines = new ArrayList<>();

        private Entry(String type, String name) {
            this.type = type;
            this.name = name;
        }
    }
}
