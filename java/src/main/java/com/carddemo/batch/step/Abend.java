package com.carddemo.batch.step;

/** An abnormal end of the step. The step's completion is {@link #code()}. */
public final class Abend extends RuntimeException {

    private final String code;

    private Abend(String code) {
        super(code);
        this.code = code;
    }

    /** CEE3ABD with a code, a user abend: {@code U0999}. */
    public static Abend user(int code) {
        return new Abend("U%04d".formatted(code));
    }

    /** CEE3ABD called without arguments (CBSTM03A), which leaves the code undefined. */
    public static Abend withoutCode() {
        return new Abend("ABEND-NO-CODE");
    }

    /** A system abend such as {@code S806}, program not found. */
    public static Abend system(String code) {
        return new Abend(code);
    }

    public String code() {
        return code;
    }
}
