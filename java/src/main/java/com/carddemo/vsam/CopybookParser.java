package com.carddemo.vsam;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CopybookParser {

    private static final Pattern PICTURE = Pattern.compile(
            "\\bPIC(?:TURE)?(?:\\s+IS)?\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEVEL = Pattern.compile("^(\\d{1,2})(?:\\s+(\\S+))?(.*)$");
    private static final Set<String> USAGE_WORDS = Set.of(
            "COMPUTATIONAL-5", "COMP-5",
            "COMPUTATIONAL-4", "COMP-4",
            "COMPUTATIONAL-3", "COMP-3", "PACKED-DECIMAL",
            "COMPUTATIONAL-2", "COMP-2",
            "COMPUTATIONAL-1", "COMP-1",
            "COMPUTATIONAL", "BINARY", "COMP", "DISPLAY");

    public Layout parse(String text) {
        String recordName = null;
        int offset = 0;
        List<Field> fields = new ArrayList<>();
        List<Field> fillers = new ArrayList<>();
        for (String statement : statements(text)) {
            String body = stripValue(statement.substring(0, statement.length() - 1).trim());
            rejectLayoutWords(body);
            Matcher level = LEVEL.matcher(body);
            if (!level.matches()) {
                throw new IllegalArgumentException("cannot read COBOL level: " + body);
            }
            int number = Integer.parseInt(level.group(1));
            if (number == 88) {
                continue;
            }
            if (number == 66 || (number > 49 && number != 77)) {
                throw new IllegalArgumentException("unsupported COBOL level " + number + ": " + body);
            }
            String name = level.group(2);
            if (name == null || name.equalsIgnoreCase("PIC") || name.equalsIgnoreCase("PICTURE")) {
                name = "FILLER";
            }
            String picture = pictureToken(body);
            if (number == 1) {
                if (recordName != null) {
                    throw new IllegalArgumentException("copybook has more than one 01 level");
                }
                recordName = name;
                if (picture == null) {
                    continue;
                }
            }
            if (picture == null) {
                continue;
            }
            Sized sized = size(picture, usage(body));
            Field field = new Field(name, offset, sized.length(), sized.pic(), picture);
            if (name.equalsIgnoreCase("FILLER")) {
                fillers.add(field);
            } else {
                fields.add(field);
            }
            offset += sized.length();
        }
        if (recordName == null) {
            throw new IllegalArgumentException("copybook has no 01 level");
        }
        return new Layout(recordName, offset, fields, fillers);
    }

    private static List<String> statements(String text) {
        List<String> statements = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        boolean inQuote = false;
        char quote = 0;
        for (String raw : text.split("\\R", -1)) {
            if (raw.indexOf('\t') >= 0) {
                throw new IllegalArgumentException("tab in copybook");
            }
            if (raw.length() < 7) {
                continue;
            }
            char indicator = raw.charAt(6);
            if (indicator == '*') {
                continue;
            }
            if (indicator != ' ' && indicator != '-') {
                throw new IllegalArgumentException("unsupported column 7 indicator '" + indicator + "'");
            }
            String area = raw.substring(7, Math.min(raw.length(), 72));
            if (indicator == '-') {
                if (!inQuote) {
                    area = area.stripLeading();
                }
            } else {
                area = area.stripTrailing();
                if (area.isBlank()) {
                    continue;
                }
                if (buffer.length() > 0 && !inQuote) {
                    buffer.append(' ');
                }
            }
            for (int i = 0; i < area.length(); i++) {
                char c = area.charAt(i);
                buffer.append(c);
                if (inQuote) {
                    if (c == quote) {
                        inQuote = false;
                    }
                    continue;
                }
                if (c == '\'' || c == '"') {
                    inQuote = true;
                    quote = c;
                    continue;
                }
                if (c == '.') {
                    statements.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
            }
        }
        if (!buffer.toString().isBlank()) {
            throw new IllegalArgumentException("unterminated COBOL statement: " + buffer);
        }
        return statements;
    }

    private static void rejectLayoutWords(String body) {
        if (containsWord(body, "REDEFINES")) {
            throw new IllegalArgumentException("REDEFINES is not supported: " + body);
        }
        if (containsWord(body, "OCCURS")) {
            throw new IllegalArgumentException("OCCURS is not supported: " + body);
        }
        if (containsWord(body, "SYNCHRONIZED") || containsWord(body, "SYNC")) {
            throw new IllegalArgumentException("SYNCHRONIZED changes offsets and is not supported: " + body);
        }
        if (containsWord(body, "RENAMES")) {
            throw new IllegalArgumentException("RENAMES is not supported: " + body);
        }
    }

    private static String stripValue(String body) {
        boolean inQuote = false;
        char quote = 0;
        String upper = body.toUpperCase(Locale.ROOT);
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inQuote) {
                if (c == quote) {
                    inQuote = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                inQuote = true;
                quote = c;
                continue;
            }
            if (isWordAt(upper, i, "VALUE")) {
                return body.substring(0, i).trim();
            }
        }
        return body.trim();
    }

    private static String pictureToken(String body) {
        Matcher matcher = PICTURE.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        String token = matcher.group(1);
        if (token.endsWith(",")) {
            token = token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static String usage(String body) {
        for (String token : body.split("\\s+")) {
            String cleaned = token.toUpperCase(Locale.ROOT).replaceAll("[,.]$", "");
            if (USAGE_WORDS.contains(cleaned)) {
                return cleaned;
            }
        }
        return null;
    }

    private static Sized size(String picture, String usage) {
        ParsedPic parsed = parsePicture(picture.toUpperCase(Locale.ROOT));
        String use = usage == null ? "" : usage;
        if (parsed.alphanumeric()) {
            if (!use.isEmpty() && !use.equals("DISPLAY")) {
                throw new IllegalArgumentException("cannot size PIC " + picture + " with " + use);
            }
            return new Sized(new Pic.Alnum(parsed.length()), parsed.length());
        }
        int digits = parsed.digitsBefore() + parsed.digitsAfter();
        int scale = parsed.digitsAfter();
        boolean signed = parsed.signed();
        return switch (use) {
            case "", "DISPLAY" -> new Sized(new Pic.Zoned(digits, scale, signed), digits);
            case "COMP-3", "COMPUTATIONAL-3", "PACKED-DECIMAL" ->
                    new Sized(new Pic.Packed(digits, scale, signed), (digits + 2) / 2);
            case "COMP", "COMPUTATIONAL", "BINARY", "COMP-4", "COMPUTATIONAL-4", "COMP-5", "COMPUTATIONAL-5" ->
                    new Sized(new Pic.Binary(digits, scale, signed), binaryLength(digits, picture));
            default -> throw new IllegalArgumentException("cannot size PIC " + picture + " with " + use);
        };
    }

    private static int binaryLength(int digits, String picture) {
        if (digits >= 1 && digits <= 4) {
            return 2;
        }
        if (digits <= 9) {
            return 4;
        }
        if (digits <= 18) {
            return 8;
        }
        throw new IllegalArgumentException("cannot size PIC " + picture);
    }

    private static ParsedPic parsePicture(String token) {
        int i = 0;
        boolean signed = false;
        boolean seenV = false;
        Boolean alphanumeric = null;
        int before = 0;
        int after = 0;
        while (i < token.length()) {
            char c = token.charAt(i);
            if (c == 'S') {
                if (i != 0 || signed) {
                    throw cannotSize(token);
                }
                signed = true;
                i++;
                continue;
            }
            if (c == 'V') {
                if (seenV || Boolean.TRUE.equals(alphanumeric)) {
                    throw cannotSize(token);
                }
                seenV = true;
                alphanumeric = false;
                i++;
                continue;
            }
            if (c != 'X' && c != 'A' && c != '9') {
                throw cannotSize(token);
            }
            int count = 1;
            if (i + 1 < token.length() && token.charAt(i + 1) == '(') {
                int close = token.indexOf(')', i + 2);
                if (close < 0) {
                    throw cannotSize(token);
                }
                try {
                    count = Integer.parseInt(token.substring(i + 2, close));
                } catch (NumberFormatException e) {
                    throw cannotSize(token);
                }
                if (count < 1) {
                    throw cannotSize(token);
                }
                i = close + 1;
            } else {
                i++;
            }
            if (c == 'X' || c == 'A') {
                if (Boolean.FALSE.equals(alphanumeric) || signed || seenV) {
                    throw cannotSize(token);
                }
                alphanumeric = true;
                before += count;
            } else {
                if (Boolean.TRUE.equals(alphanumeric)) {
                    throw cannotSize(token);
                }
                alphanumeric = false;
                if (seenV) {
                    after += count;
                } else {
                    before += count;
                }
            }
        }
        if (alphanumeric == null || (!alphanumeric && before + after == 0)) {
            throw cannotSize(token);
        }
        if (alphanumeric) {
            return new ParsedPic(true, false, before, 0, before);
        }
        return new ParsedPic(false, signed, before, after, before + after);
    }

    private static boolean containsWord(String body, String word) {
        return wordIndex(body.toUpperCase(Locale.ROOT), word) >= 0;
    }

    private static int wordIndex(String upper, String word) {
        int from = 0;
        while (from < upper.length()) {
            int found = upper.indexOf(word, from);
            if (found < 0) {
                return -1;
            }
            if (isWordAt(upper, found, word)) {
                return found;
            }
            from = found + 1;
        }
        return -1;
    }

    private static boolean isWordAt(String upper, int index, String word) {
        if (!upper.startsWith(word, index)) {
            return false;
        }
        boolean left = index == 0 || !isNameChar(upper.charAt(index - 1));
        int end = index + word.length();
        boolean right = end >= upper.length() || !isNameChar(upper.charAt(end));
        return left && right;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    private static IllegalArgumentException cannotSize(String token) {
        return new IllegalArgumentException("cannot size PIC " + token);
    }

    private record Sized(Pic pic, int length) {}

    private record ParsedPic(
            boolean alphanumeric, boolean signed, int digitsBefore, int digitsAfter, int length) {}
}
