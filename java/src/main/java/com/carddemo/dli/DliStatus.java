package com.carddemo.dli;

/** DIBSTAT after an EXEC DLI call, the two-character status code the programs test. */
public enum DliStatus {
    /** Blanks: the call succeeded. */
    OK("  "),
    /** Segment not found: GU or ISRT parent qualification unmatched, or GNP past the last child. */
    GE("GE"),
    /** End of database on GN. */
    GB("GB"),
    /** ISRT of a segment whose key is already there. */
    II("II"),
    /** REPL or DLET not immediately preceded by a successful get of that segment type. */
    DJ("DJ"),
    /** REPL or DLET with a key field different from the held segment's. */
    DA("DA"),
    /** GNP with no parentage established. */
    GP("GP"),
    /** An SSA the call cannot use, such as a qualified SSA on the segment an ISRT inserts. */
    AJ("AJ");

    private final String code;

    DliStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
