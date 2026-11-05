package com.carddemo.batch.jcl;

import java.util.List;
import java.util.Optional;

/** A DD statement and the unnamed DDs concatenated to it. */
public record Dd(String name, List<DdSource> concatenation) {

    public Dd {
        concatenation = List.copyOf(concatenation);
        if (concatenation.isEmpty()) {
            throw new IllegalArgumentException("DD " + name + " has no source");
        }
    }

    public static Dd of(String name, DdSource source) {
        return new Dd(name, List.of(source));
    }

    public static Dd shr(String name, String dsn) {
        return of(name, new DdSource.Dataset(new DsnRef.Named(dsn), Disposition.SHR, Optional.empty()));
    }

    public static Dd sysout(String name) {
        return of(name, new DdSource.Sysout(Optional.empty()));
    }

    public DdSource source() {
        return concatenation.getFirst();
    }
}
