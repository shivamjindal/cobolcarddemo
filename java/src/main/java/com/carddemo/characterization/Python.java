package com.carddemo.characterization;

import java.util.ArrayList;
import java.util.List;

/** The two Python string operations the harness applies to latin-1 text, which Java's whitespace rules miss. */
final class Python {

    private static final String SPACE = "\t\n\u000b\f\r\u001c\u001d\u001e\u001f \u0085\u00a0";
    private static final String LINE_BREAKS = "\n\r\u000b\f\u001c\u001d\u001e\u0085";

    private Python() {}

    /** {@code str.rstrip()}. */
    static String rstrip(String text) {
        int end = text.length();
        while (end > 0 && SPACE.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(0, end);
    }

    /** {@code str.splitlines()}: no trailing empty line, {@code \r\n} is one break. */
    static List<String> splitlines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (LINE_BREAKS.indexOf(c) >= 0) {
                lines.add(text.substring(start, i));
                i += c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n' ? 2 : 1;
                start = i;
            } else {
                i++;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }
}
