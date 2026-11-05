package com.carddemo.batch.programs;

import com.carddemo.batch.step.Step;

/**
 * 9999-ABEND of the IMS programs, which is no abend: it DISPLAYs the program's line, moves 16 to RETURN-CODE and
 * GOBACKs from whatever paragraph performed it. The program's {@code run} catches it and returns {@link #RETURN_CODE},
 * so the region commits what the program changed.
 */
final class ImsGoback extends RuntimeException {

    static final int RETURN_CODE = 16;

    private ImsGoback() {
        super(null, null, false, false);
    }

    /** DISPLAYs {@code line}; the caller throws the result. */
    static ImsGoback of(Step step, String line) {
        step.display(line);
        return new ImsGoback();
    }
}
