package com.carddemo.copybook;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public sealed interface Pic {

    int storageLength();

    record Alphanumeric(int length) implements Pic {
        @Override
        public int storageLength() {
            return length;
        }
    }

    record Numeric(int digits, int scale, boolean signed, Usage usage) implements Pic {
        @Override
        public int storageLength() {
            return switch (usage) {
                case DISPLAY -> digits;
                case COMP_3 -> digits / 2 + 1;
                case COMP -> digits <= 4 ? 2 : digits <= 9 ? 4 : 8;
            };
        }
    }

    enum Usage { DISPLAY, COMP, COMP_3 }

    static Pic parse(String picture, Usage usage) {
        String expanded = expand(picture.toUpperCase());
        if (expanded.matches("[XA]+")) {
            if (usage != Usage.DISPLAY) {
                throw new IllegalArgumentException("Alphanumeric PIC with binary usage: " + picture);
            }
            return new Alphanumeric(expanded.length());
        }
        if (!expanded.matches("S?9*(V9*)?") || !expanded.contains("9")) {
            throw new IllegalArgumentException("Unsupported PIC clause: " + picture);
        }
        boolean signed = expanded.startsWith("S");
        String unsigned = signed ? expanded.substring(1) : expanded;
        int v = unsigned.indexOf('V');
        int scale = v < 0 ? 0 : unsigned.length() - v - 1;
        int digits = unsigned.replace("V", "").length();
        return new Numeric(digits, scale, signed, usage);
    }

    private static String expand(String picture) {
        Matcher m = Pattern.compile("(.)\\((\\d+)\\)").matcher(picture);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, m.group(1).repeat(Integer.parseInt(m.group(2))));
        }
        return m.appendTail(out).toString();
    }
}
