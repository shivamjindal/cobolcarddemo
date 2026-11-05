package com.carddemo.card;

import com.carddemo.LegacyDefect;

/**
 * A PIC X field the card programs test with {@code IS NUMERIC}. The map space-pads it, so only a field
 * full of digits passes, and a field of zeros counts as not supplied.
 */
final class FixedDigits {

    enum Kind { BLANK, INVALID, OK }

    /** @param text the characters to show: the digits, the rejected raw value, or "" when blank */
    record Value(Kind kind, String text) {

        static Value blank() {
            return new Value(Kind.BLANK, "");
        }

        static Value invalid(String raw) {
            return new Value(Kind.INVALID, raw);
        }

        static Value ok(String digits) {
            return new Value(Kind.OK, digits);
        }

        boolean ok() {
            return kind == Kind.OK;
        }
    }

    private FixedDigits() {}

    @LegacyDefect(id = "COCRDSLC-short-field-not-numeric",
            goldens = {"card-detail-account-short"},
            behavior = "IS NUMERIC fails when trailing blanks pad a short account out to 11 characters, so '1' is rejected",
            fix = "accept a numeric account that is shorter than the field")
    static Value read(String raw, int length) {
        if (raw.isEmpty()) {
            return Value.blank();
        }
        if (raw.length() != length || raw.chars().anyMatch(c -> c < '0' || c > '9')) {
            return Value.invalid(raw);
        }
        if (raw.chars().allMatch(c -> c == '0')) {
            return Value.blank();
        }
        return Value.ok(raw);
    }
}
