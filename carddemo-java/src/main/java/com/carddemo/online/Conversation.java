package com.carddemo.online;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Replaces CARDDEMO-COMMAREA for one signed-on user; lives in the HTTP session. FROM-TRANID is
 * {@code from().transid}. Programs keep their own typed state here, the way COUSR00C appends
 * CDEMO-CU00-INFO to the COMMAREA.
 */
public final class Conversation implements Serializable {

    public static final String KEY = "conversation";

    private final SignedOnUser user;
    private Program from;
    private final Map<Class<?>, Serializable> state = new HashMap<>();

    public Conversation(SignedOnUser user, Program from) {
        this.user = user;
        this.from = from;
    }

    public SignedOnUser user() {
        return user;
    }

    /** CDEMO-FROM-PROGRAM: the program that last transferred here. */
    public Optional<Program> from() {
        return Optional.ofNullable(from);
    }

    void from(Program program) {
        this.from = program;
    }

    /** The program whose map was sent last, and the values it showed in the fields it protected. */
    record Protected(Program program, Map<String, String> values) implements Serializable {
    }

    Optional<Protected> shown() {
        return get(Protected.class);
    }

    public <T extends Serializable> Optional<T> get(Class<T> type) {
        return Optional.ofNullable(type.cast(state.get(type)));
    }

    /** Reads and removes, for hand-offs that must be used once. */
    public <T extends Serializable> Optional<T> take(Class<T> type) {
        return Optional.ofNullable(type.cast(state.remove(type)));
    }

    public void put(Serializable value) {
        state.put(value.getClass(), value);
    }
}
