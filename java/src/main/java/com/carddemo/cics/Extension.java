package com.carddemo.cics;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * An optional CSD group that CEDA INSTALL adds to the region. Its programs run only while it is installed;
 * otherwise their transactions are unknown and a program that links to them takes its PGMIDERR path.
 */
public enum Extension {
    /** {@code app/app-transaction-type-db2/csd/CRDDEMOD.csd}: transactions CTLI and CTTU. */
    TRANSACTION_TYPE_DB2("transaction-type-db2", Set.of("COTRTLIC", "COTRTUPC"));

    private final String id;
    private final Set<String> programs;

    Extension(String id, Set<String> programs) {
        this.id = id;
        this.programs = programs;
    }

    /** The name the characterization cases install it by, and the value stored in {@code cics_region}. */
    public String id() {
        return id;
    }

    public static Extension byId(String id) {
        return Arrays.stream(values()).filter(e -> e.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no extension " + id));
    }

    /** The extension that defines {@code program}, empty for a program of the base CSD. */
    public static Optional<Extension> owning(String program) {
        return Arrays.stream(values()).filter(e -> e.programs.contains(program)).findFirst();
    }
}
