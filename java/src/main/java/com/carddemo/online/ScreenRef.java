package com.carddemo.online;

import java.util.Optional;

/**
 * What another screen needs to XCTL to this one: its program, its transaction id, its URL route, and
 * the handoff type it accepts on arrival.
 */
public record ScreenRef<H extends Handoff>(ProgramName program, Optional<TransId> transid, String route,
        Class<H> handoff) {

    public static <H extends Handoff> ScreenRef<H> of(String program, String transid, String route, Class<H> handoff) {
        return new ScreenRef<>(ProgramName.of(program), Optional.of(TransId.of(transid)), route, handoff);
    }

    static <H extends Handoff> ScreenRef<H> withoutTransid(String program, String route, Class<H> handoff) {
        return new ScreenRef<>(ProgramName.of(program), Optional.empty(), route, handoff);
    }
}
