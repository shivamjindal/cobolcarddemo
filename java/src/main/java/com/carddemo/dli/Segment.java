package com.carddemo.dli;

/** A segment's I/O area: the typed fields and the byte image a COBOL program moves. */
public sealed interface Segment permits PendingAuthSummary, PendingAuthDetail {

    SegmentType type();

    /** The segment as GnuCOBOL holds it: latin-1 text, COMP-3 and big-endian COMP. */
    byte[] image();
}
