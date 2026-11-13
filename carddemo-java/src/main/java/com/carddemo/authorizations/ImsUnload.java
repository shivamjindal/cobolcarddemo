package com.carddemo.authorizations;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * An unload of DBPAUTP0 in the module's sample data (AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0): variable-length records,
 * each a summary followed by its details, in IMS order. A data record names its segment at offset 6 of the record
 * and holds the segment from offset 35; the header and statistics records name none.
 */
final class ImsUnload {

    record Contents(List<AuthorizationSummary> summaries, List<Authorization> details, int skipped) {
    }

    private static final Charset CP037 = Charset.forName("IBM037");
    private static final int NAME = 6;
    private static final int SEGMENT = 35;

    private ImsUnload() {
    }

    /** A segment whose packed fields do not decode is skipped with the details under it. */
    static Contents read(byte[] file) {
        List<AuthorizationSummary> summaries = new ArrayList<>();
        List<Authorization> details = new ArrayList<>();
        int skipped = 0;
        AuthorizationSummary parent = null;
        for (int at = 0; at + 4 <= file.length; ) {
            int length = (file[at] & 0xff) << 8 | file[at + 1] & 0xff;
            int record = at + 4;
            at += length;
            if (length < 4 + SEGMENT) {
                continue;
            }
            String name = new String(file, record + NAME, 8, CP037);
            try {
                if (name.equals("PAUTSUM0")) {
                    parent = summary(new Segment(file, record + SEGMENT));
                    summaries.add(parent);
                } else if (name.equals("PAUTDTL1")) {
                    if (parent == null) {
                        skipped++;
                    } else {
                        details.add(detail(parent.accountId(), new Segment(file, record + SEGMENT)));
                    }
                }
            } catch (NumberFormatException e) {
                skipped++;
                if (name.equals("PAUTSUM0")) {
                    parent = null;
                }
            }
        }
        return new Contents(summaries, details, skipped);
    }

    private static AuthorizationSummary summary(Segment s) {
        return new AuthorizationSummary(s.packed(6, 0).longValueExact(), s.zoned(9), s.text(1), s.text(10),
                s.packed(6, 2), s.packed(6, 2), s.packed(6, 2), s.packed(6, 2), s.binary(2), s.binary(2),
                s.packed(6, 2), s.packed(6, 2));
    }

    private static Authorization detail(long accountId, Segment s) {
        AuthorizationKey key = new AuthorizationKey(s.packed(3, 0).intValueExact(), s.packed(5, 0).intValueExact());
        String date = s.text(6);
        String time = s.text(6);
        String card = s.text(16);
        String authType = s.text(4);
        String expiry = s.text(4);
        String messageType = s.text(6);
        String messageSource = s.text(6);
        String authIdCode = s.text(6);
        String responseCode = s.text(2);
        String responseReason = s.text(4);
        int processingCode = s.zoned(6);
        BigDecimal amount = s.packed(7, 2);
        BigDecimal approved = s.packed(7, 2);
        AuthorizationRequest request = new AuthorizationRequest(date, time, card, authType, expiry, messageType,
                messageSource, processingCode, amount, s.text(4), s.text(3), s.zoned(2), s.text(15), s.text(22),
                s.text(13), s.text(2), s.text(9), s.text(15));
        return new Authorization(accountId, key, request, authIdCode, responseCode, responseReason, approved,
                s.text(1), s.text(1), s.text(8));
    }

    /** Reads one segment's fields in copybook order. */
    private static final class Segment {

        private final byte[] bytes;
        private int at;

        Segment(byte[] bytes, int at) {
            this.bytes = bytes;
            this.at = at;
        }

        /** LOW-VALUES read as spaces: Postgres text cannot hold NUL. */
        String text(int length) {
            String value = new String(bytes, take(length), length, CP037).replace('\u0000', ' ');
            return value.stripTrailing();
        }

        /** Unsigned DISPLAY numeric. */
        int zoned(int length) {
            return Integer.parseInt(text(length));
        }

        /** COMP: big-endian two's complement. */
        int binary(int length) {
            int start = take(length);
            int value = bytes[start];
            for (int i = 1; i < length; i++) {
                value = value << 8 | bytes[start + i] & 0xff;
            }
            return value;
        }

        /** COMP-3: two digits a byte, the last nibble the sign (D or B negative). */
        BigDecimal packed(int length, int scale) {
            int start = take(length);
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < length; i++) {
                int b = bytes[start + i] & 0xff;
                digits.append(digit(b >> 4));
                if (i < length - 1) {
                    digits.append(digit(b & 0x0f));
                }
            }
            int sign = bytes[start + length - 1] & 0x0f;
            if (sign < 0x0a) {
                throw new NumberFormatException("packed field without a sign nibble at " + start);
            }
            BigDecimal value = new BigDecimal(new BigInteger(digits.toString()), scale);
            return sign == 0x0d || sign == 0x0b ? value.negate() : value;
        }

        private static char digit(int nibble) {
            if (nibble > 9) {
                throw new NumberFormatException("packed digit " + nibble);
            }
            return (char) ('0' + nibble);
        }

        private int take(int length) {
            int start = at;
            at += length;
            return start;
        }
    }
}
