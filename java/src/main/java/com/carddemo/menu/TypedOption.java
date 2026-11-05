package com.carddemo.menu;

import java.util.List;
import java.util.Optional;

import com.carddemo.online.MapField;
import com.carddemo.online.SymbolicMap;

/**
 * OPTION as both menus' PROCESS-ENTER-KEY read it: trailing spaces dropped, right-justified into
 * {@code WS-OPTION-X PIC X(02) JUST RIGHT}, spaces turned to zeros, then moved to
 * {@code WS-OPTION PIC 9(02)}.
 */
record TypedOption(int number) {

    private static final int DIGITS = 2;

    static <F extends Enum<F> & MapField> TypedOption read(SymbolicMap<F> map, F option) {
        String raw = map.isLowValues(option)
                ? "\0".repeat(option.length())
                : String.format("%-" + option.length() + "s", map.text(option));
        int last = raw.length();
        while (last > 1 && raw.charAt(last - 1) == ' ') {
            last--;
        }
        String justified = " ".repeat(DIGITS - last) + raw.substring(0, last);
        return new TypedOption(moveToUnsignedNumeric(justified.replace(' ', '0')));
    }

    /** {@code MOVE WS-OPTION TO OPTIONO}. */
    String echo() {
        return "%02d".formatted(number);
    }

    /** {@code WS-OPTION} between 1 and the option count. */
    Optional<MenuTables.Entry> in(List<MenuTables.Entry> table) {
        return number >= 1 && number <= table.size() ? Optional.of(table.get(number - 1)) : Optional.empty();
    }

    /**
     * GnuCOBOL 3.1's MOVE of the two-character, space-free {@code WS-OPTION-X} to {@code PIC 9(02)}:
     * a leading sign is skipped, the digits before the first period are right-aligned, commas are
     * skipped, and any other character ends the move. So "1A", "-1" and ",1" all read as 1.
     */
    private static int moveToUnsignedNumeric(String source) {
        int start = source.charAt(0) == '+' || source.charAt(0) == '-' ? 1 : 0;
        int count = 0;
        for (int p = start; p < source.length() && source.charAt(p) != '.'; p++) {
            if (isDigit(source.charAt(p))) {
                count++;
            }
        }
        char[] digits = "0".repeat(DIGITS).toCharArray();
        int at = DIGITS - count;
        boolean point = false;
        for (int p = start; p < source.length() && at < DIGITS; p++) {
            char c = source.charAt(p);
            if (isDigit(c)) {
                digits[at++] = c;
            } else if (c == '.' && !point) {
                point = true;
            } else if (c != ',') {
                break;
            }
        }
        return Integer.parseInt(new String(digits));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
