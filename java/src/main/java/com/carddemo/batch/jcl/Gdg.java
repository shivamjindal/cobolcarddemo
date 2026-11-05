package com.carddemo.batch.jcl;

import java.util.Arrays;
import java.util.Optional;

/** The generation data groups of {@code app/catlg/LISTCAT.txt}, all LIMIT 5, NOEMPTY. */
public enum Gdg {
    DALYREJS("AWS.M2.CARDDEMO.DALYREJS", 5, true),
    SYSTRAN("AWS.M2.CARDDEMO.SYSTRAN", 5, true),
    TCATBALF_BKUP("AWS.M2.CARDDEMO.TCATBALF.BKUP", 5, true),
    TRANREPT("AWS.M2.CARDDEMO.TRANREPT", 5, true),
    TRANSACT_BKUP("AWS.M2.CARDDEMO.TRANSACT.BKUP", 5, false),
    TRANSACT_COMBINED("AWS.M2.CARDDEMO.TRANSACT.COMBINED", 5, true),
    TRANSACT_DALY("AWS.M2.CARDDEMO.TRANSACT.DALY", 5, false);

    private final String base;
    private final int limit;
    private final boolean scratch;

    Gdg(String base, int limit, boolean scratch) {
        this.base = base;
        this.limit = limit;
        this.scratch = scratch;
    }

    public String base() {
        return base;
    }

    public int limit() {
        return limit;
    }

    /** SCRATCH deletes a rolled-off generation; NOSCRATCH only uncatalogs it. */
    public boolean scratch() {
        return scratch;
    }

    public static Optional<Gdg> byBase(String base) {
        return Arrays.stream(values()).filter(g -> g.base.equals(base)).findFirst();
    }
}
