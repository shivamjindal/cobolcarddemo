package com.carddemo.dli;

import java.util.Optional;

/** A GU, GN or GNP: DIBSTAT, and the segment moved INTO the I/O area when the status is {@link DliStatus#OK}. */
public record Get<S extends Segment>(DliStatus status, Optional<S> segment) {

    static <S extends Segment> Get<S> found(S segment) {
        return new Get<>(DliStatus.OK, Optional.of(segment));
    }

    static <S extends Segment> Get<S> not(DliStatus status) {
        return new Get<>(status, Optional.empty());
    }

    public boolean ok() {
        return status == DliStatus.OK;
    }
}
