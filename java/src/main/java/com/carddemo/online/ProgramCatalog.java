package com.carddemo.online;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.carddemo.cics.CicsRegion;
import com.carddemo.cics.Extension;

/**
 * Every ported program, derived from the Screen beans. Installed means ported, and for a program of an optional
 * {@link Extension}, that extension installed in the region: a later slice ports a program by adding its Screen
 * bean. A program of an extension the region lacks has no transaction and lands on the pending screen.
 */
@Component
public final class ProgramCatalog {

    private final Map<ProgramName, Screen<?, ?, ?, ?>> byProgram = new LinkedHashMap<>();
    private final Map<TransId, Screen<?, ?, ?, ?>> byTransid = new LinkedHashMap<>();
    private final Map<String, Screen<?, ?, ?, ?>> byRoute = new LinkedHashMap<>();
    private final PendingProgramScreen pending;
    private final CicsRegion region;

    ProgramCatalog(List<Screen<?, ?, ?, ?>> screens, PendingProgramScreen pending, CicsRegion region) {
        this.pending = pending;
        this.region = region;
        for (Screen<?, ?, ?, ?> screen : screens) {
            ScreenRef<?> ref = screen.ref();
            unique(byProgram.put(ref.program(), screen), "program " + ref.program());
            ref.transid().ifPresent(t -> unique(byTransid.put(t, screen), "transaction " + t.value()));
            unique(byRoute.put(ref.route(), screen), "route " + ref.route());
            Set<Key> keys = new HashSet<>();
            for (var action : screen.actionList()) {
                if (!keys.add(action.key()) || action.route().equals(ScreenAction.INVALID_KEY)) {
                    throw new IllegalStateException(ref.program() + " binds " + action.key() + " twice or names an action invalid-key");
                }
            }
        }
    }

    private static void unique(Screen<?, ?, ?, ?> previous, String what) {
        if (previous != null) {
            throw new IllegalStateException("two screens claim " + what);
        }
    }

    /** COMEN01C's INQUIRE PROGRAM and COADM01C's HANDLE CONDITION PGMIDERR both ask this. */
    boolean installed(ProgramName program) {
        Screen<?, ?, ?, ?> screen = byProgram.get(program);
        return screen != null && screen != pending && inRegion(program);
    }

    /** {@code /api/screens/<route>/**} for every screen with exactly this access. */
    public List<String> actionPatterns(Access access) {
        return byRoute.values().stream()
                .filter(s -> s.access() == access)
                .map(s -> "/api/screens/" + s.ref().route() + "/**")
                .toList();
    }

    Optional<Screen<?, ?, ?, ?>> byProgram(ProgramName program) {
        return Optional.ofNullable(byProgram.get(program));
    }

    Optional<Screen<?, ?, ?, ?>> byTransid(TransId transid) {
        return Optional.<Screen<?, ?, ?, ?>>ofNullable(byTransid.get(transid)).filter(screen -> inRegion(screen.ref().program()));
    }

    /** The screen an XCTL lands on. An unported program lands on the pending screen, which is told its name. */
    Resolved resolve(ProgramName program, Optional<Handoff> handoff) {
        Screen<?, ?, ?, ?> screen = byProgram.get(program);
        return screen != null && inRegion(program)
                ? new Resolved(screen, handoff)
                : new Resolved(pending, Optional.of(new PendingProgramScreen.Requested(program)));
    }

    private boolean inRegion(ProgramName program) {
        return Extension.owning(program.value()).map(extension -> region.installed().contains(extension)).orElse(true);
    }

    record Resolved(Screen<?, ?, ?, ?> screen, Optional<Handoff> handoff) {}
}
