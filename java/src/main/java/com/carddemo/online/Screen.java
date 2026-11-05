package com.carddemo.online;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.carddemo.identity.SignedOnUser;

/**
 * One CICS program that owns one BMS map. A subclass is a Spring bean that writes three methods, one
 * per way a pseudo-conversational program is entered:
 * <ul>
 *   <li>{@link #enter}: first display, after a transaction start or an XCTL;</li>
 *   <li>{@link #act}: a key bound to one of its actions, on its own map;</li>
 *   <li>{@link #invalidKey}: the {@code WHEN OTHER} branch of {@code EVALUATE EIBAID}.</li>
 * </ul>
 * Each returns an {@link Outcome} built by the protected helpers. A subclass never sees HTTP, the
 * session, transactions, security, the terminal, or the header fields.
 *
 * @param <F> the generated map enum
 * @param <A> the screen's actions, each bound to one key
 * @param <S> what the program keeps in its COMMAREA between turns
 * @param <H> what it accepts on arrival from an XCTL
 */
public abstract class Screen<F extends Enum<F> & MapField, A extends Enum<A> & ScreenAction,
        S extends ScreenState, H extends Handoff> {

    private final ScreenRef<H> ref;
    private final MapId<F> map;
    private final Class<A> actions;
    private final Class<S> stateType;
    private final Access access;

    protected Screen(ScreenRef<H> ref, MapId<F> map, Class<A> actions, Class<S> stateType, Access access) {
        this.ref = ref;
        this.map = map;
        this.actions = actions;
        this.stateType = stateType;
        this.access = access;
    }

    protected abstract Outcome enter(Arrival<H> arrival);

    protected abstract Outcome act(A action, SymbolicMap<F> map, S state);

    protected abstract Outcome invalidKey(SymbolicMap<F> map, S state);

    /** SEND MAP ERASE CURSOR, then RETURN TRANSID. */
    protected final Outcome show(SymbolicMap<F> map, S state) {
        return new Show(map, SendMode.ERASE_CURSOR, state);
    }

    protected final Outcome show(SymbolicMap<F> map, SendMode mode, S state) {
        return new Show(map, mode, state);
    }

    /** SEND TEXT, then RETURN TRANSID. */
    protected final Outcome showText(String text, S state) {
        return new ShowText(text, state);
    }

    /** XCTL with a typed COMMAREA extension. */
    protected final <T extends Handoff> Outcome transfer(ScreenRef<T> to, T handoff) {
        return new Transfer(to.program(), Optional.of(handoff), new IdentityChange.Keep(), Optional.empty());
    }

    /** XCTL with the base COMMAREA only. */
    protected final Outcome transfer(ProgramName to) {
        return new Transfer(to, Optional.empty(), new IdentityChange.Keep(), Optional.empty());
    }

    /**
     * XCTL under {@code HANDLE CONDITION PGMIDERR}, or after {@code INQUIRE PROGRAM}: a program that
     * is not installed gets {@code otherwise}.
     */
    protected final Outcome transferIfInstalled(ProgramName to, Outcome otherwise) {
        return new Transfer(to, Optional.empty(), new IdentityChange.Keep(), Optional.of(otherwise));
    }

    /** A successful sign-on: the identity becomes this user, then XCTL to the user's home menu. */
    protected final Outcome signIn(SignedOnUser user, ProgramName home) {
        return new Transfer(home, Optional.empty(), new IdentityChange.SignIn(user), Optional.empty());
    }

    /** XCTL to the sign-on program without a COMMAREA, so the identity is gone. */
    protected final Outcome signOff(ProgramName signOn) {
        return new Transfer(signOn, Optional.empty(), new IdentityChange.SignOff(), Optional.empty());
    }

    /** SEND TEXT, then RETURN without a transaction id. */
    protected final Outcome end(String text) {
        return new End(text);
    }

    /** This path of the program belongs to a later slice. */
    protected final Outcome notMigrated() {
        return new Transfer(PendingProgramScreen.REF.program(),
                Optional.of(new PendingProgramScreen.Requested(ref.program())), new IdentityChange.Keep(), Optional.empty());
    }

    final ScreenRef<H> ref() {
        return ref;
    }

    final MapId<F> map() {
        return map;
    }

    final Access access() {
        return access;
    }

    final List<A> actionList() {
        return List.of(actions.getEnumConstants());
    }

    final Optional<A> actionNamed(String route) {
        return actionList().stream().filter(a -> a.route().equals(route)).findFirst();
    }

    final boolean binds(Key key) {
        return actionList().stream().anyMatch(a -> a.key() == key);
    }

    final Outcome enterFrom(Optional<ProgramName> from, Key aid, Optional<Handoff> handoff,
            Optional<SignedOnUser> user) {
        return enter(new Arrival<>(from, aid, handoff.map(ref.handoff()::cast), user));
    }

    /** The user typed and pressed {@code aid} on this screen's map. */
    final Outcome press(Terminal terminal, Key aid, Map<String, String> typed, ScreenState state) {
        SymbolicMap<F> received = terminal.typeAndSend(map, aid, typed).orElseGet(() -> SymbolicMap.lowValues(map));
        S own = stateType.cast(state);
        Optional<A> action = actionList().stream().filter(a -> a.key() == aid).findFirst();
        return action.isPresent() ? act(action.get(), received, own) : invalidKey(received, own);
    }
}
