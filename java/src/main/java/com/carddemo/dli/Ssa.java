package com.carddemo.dli;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * The SEGMENT and WHERE of an EXEC DLI call: a segment type, unqualified or qualified by its key field with
 * {@code =}, as {@code WHERE (ACCNTID = PA-ACCT-ID)} and {@code WHERE (PAUT9CTS = PA-AUTHORIZATION-KEY)} do.
 */
public sealed interface Ssa<S extends Segment> {

    SegmentType type();

    record Summary(OptionalLong acctId) implements Ssa<PendingAuthSummary> {
        @Override
        public SegmentType type() {
            return SegmentType.PAUTSUM0;
        }
    }

    record Detail(Optional<AuthKey> key) implements Ssa<PendingAuthDetail> {
        @Override
        public SegmentType type() {
            return SegmentType.PAUTDTL1;
        }
    }

    /** {@code SEGMENT(PAUTSUM0)}. */
    static Summary summary() {
        return new Summary(OptionalLong.empty());
    }

    /** {@code SEGMENT(PAUTSUM0) WHERE (ACCNTID = acctId)}. */
    static Summary summary(long acctId) {
        return new Summary(OptionalLong.of(acctId));
    }

    /** {@code SEGMENT(PAUTDTL1)}. */
    static Detail detail() {
        return new Detail(Optional.empty());
    }

    /** {@code SEGMENT(PAUTDTL1) WHERE (PAUT9CTS = key)}. */
    static Detail detail(AuthKey key) {
        return new Detail(Optional.of(key));
    }
}
