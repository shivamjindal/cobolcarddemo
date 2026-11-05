package com.carddemo.vsammq;

import com.carddemo.LegacyDefect;

/** WS-KEY, the {@code PIC 9(11)} a group MOVE fills with the request's raw bytes, digits or not. */
final class AccountKey {

    static final int LENGTH = 11;

    private AccountKey() {
    }

    /**
     * {@code WS-KEY > ZEROES}. How a display-numeric field holding non-digits compares is compiler-defined; the
     * goldens come from GnuCOBOL 3.1.2, which weighs each byte as {@code byte - '0'} at its decimal place, so a space
     * counts -16 and a letter counts its distance from '0'.
     */
    @LegacyDefect(id = "COACCT01-key-compare-on-raw-bytes",
            behavior = "WS-KEY is tested with > ZEROES while it holds the request's raw bytes, so a key with "
                    + "letters can pass and be read as an ACCTDAT key that cannot exist, and a key with a leading "
                    + "or trailing space fails even when its digits name an account",
            fix = "test WS-KEY IS NUMERIC before comparing it, and reject the request otherwise")
    static boolean positive(String raw) {
        long value = 0;
        for (int i = 0; i < LENGTH; i++) {
            value = value * 10 + (raw.charAt(i) - '0');
        }
        return value > 0;
    }

    /** The ACCTDAT id the 11 key bytes spell, or -1 when a byte is not a digit and no record can carry the key. */
    static long recordId(String raw) {
        long value = 0;
        for (int i = 0; i < LENGTH; i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }
}
