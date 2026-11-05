package com.carddemo.batch.jcl;

/** DISP=(status, normal end, abnormal end) with the JCL defaults filled in. */
public record Disposition(Status status, End normal, End abnormal) {

    public static final Disposition SHR = of(Status.SHR, null, null);

    public enum Status {
        NEW, OLD, SHR, MOD
    }

    public enum End {
        KEEP, CATLG, DELETE, PASS, UNCATLG
    }

    /**
     * A NEW data set is deleted at step end unless told otherwise, an existing one kept; the abnormal end
     * defaults to the normal one.
     */
    public static Disposition of(Status status, End normal, End abnormal) {
        Status s = status == null ? Status.NEW : status;
        End n = normal != null ? normal : s == Status.NEW ? End.DELETE : End.KEEP;
        return new Disposition(s, n, abnormal != null ? abnormal : n);
    }
}
