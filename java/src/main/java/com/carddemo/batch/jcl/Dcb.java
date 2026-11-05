package com.carddemo.batch.jcl;

/** DCB RECFM and LRECL; blocking is not modelled. An LRECL of 0 was not given. */
public record Dcb(Recfm recfm, int lrecl) {

    public enum Recfm {
        F, V
    }
}
