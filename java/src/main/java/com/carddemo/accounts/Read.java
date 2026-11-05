package com.carddemo.accounts;

/**
 * RESP of a keyed READ, so a screen's switch is as exhaustive as its {@code EVALUATE WS-RESP-CD}.
 * {@link Failed} carries the codes the programs print in their file-error messages.
 */
public sealed interface Read<T> permits Read.Found, Read.Missing, Read.Failed {

    /** DFHRESP(DUPKEY): a READ through a non-unique alternate index found more than one record. */
    int DUPKEY = 15;

    /** DFHRESP(IOERR), which stands for any database failure. */
    int IOERR = 17;

    /** DFHRESP(NOTOPEN): CEMT has closed the file. */
    int NOTOPEN = 19;

    record Found<T>(T record) implements Read<T> {}

    /** NOTFND. */
    record Missing<T>() implements Read<T>, ForUpdate<T> {}

    /** Any other RESP. */
    record Failed<T>(int resp, int resp2) implements Read<T>, ForUpdate<T> {}
}
