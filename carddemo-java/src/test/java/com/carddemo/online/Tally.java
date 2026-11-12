package com.carddemo.online;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Per-program counts of replayed cases; build.gradle.kts echoes the "[spec-replay]" lines to the console. */
final class Tally {

    private final Map<String, Map<SpecCase.Kind, List<String>>> programs = new TreeMap<>();

    synchronized void record(SpecCase c) {
        programs.computeIfAbsent(c.program(), p -> new EnumMap<>(SpecCase.Kind.class))
                .computeIfAbsent(c.kind(), k -> new ArrayList<>()).add(c.id());
    }

    synchronized void print() {
        programs.forEach((program, kinds) -> {
            List<String> passed = kinds.getOrDefault(SpecCase.Kind.AS_SPECIFIED, List.of());
            List<String> overridden = kinds.getOrDefault(SpecCase.Kind.OVERRIDDEN, List.of());
            List<String> skipped = kinds.getOrDefault(SpecCase.Kind.SKIPPED, List.of());
            System.out.printf("[spec-replay] %s: %d passed, %d overridden, %d skipped%n",
                    program, passed.size(), overridden.size(), skipped.size());
            System.out.printf("[spec-replay]   passed: %s%n", passed);
            System.out.printf("[spec-replay]   overridden: %s%n", overridden);
            System.out.printf("[spec-replay]   skipped: %s%n", skipped);
        });
    }
}
