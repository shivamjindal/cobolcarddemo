package com.carddemo.trantypes;

/** The description edit both transaction-type screens share. */
final class Descriptions {

    private Descriptions() {
    }

    /** Null when the text is letters, digits and spaces. The blank and punctuation messages are the legacy ones. */
    static String problem(String value) {
        String text = value == null ? "" : value;
        if (text.isBlank()) {
            return "Transaction Desc must be supplied.";
        }
        if (!text.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == ' ')) {
            return "Transaction Desc can have numbers or alphabets only.";
        }
        return null;
    }

    /** Upper-case trim, and the trimmed lengths, which is how the programs decide nothing changed. */
    static boolean unchanged(String typed, String stored) {
        String left = typed == null ? "" : typed.trim();
        String right = stored == null ? "" : stored.trim();
        return left.equalsIgnoreCase(right) && left.length() == right.length();
    }

    static String stored(String value) {
        return value == null ? "" : value.trim();
    }
}
