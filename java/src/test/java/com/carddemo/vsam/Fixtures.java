package com.carddemo.vsam;

import java.nio.file.Path;

final class Fixtures {

    private Fixtures() {}

    static Path appRoot() {
        String configured = System.getProperty("carddemo.app-root");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("..", "app").toAbsolutePath().normalize();
    }

    static String fixed(String... codeLines) {
        StringBuilder source = new StringBuilder();
        for (String code : codeLines) {
            source.append("       ").append(code).append('\n');
        }
        return source.toString();
    }
}
