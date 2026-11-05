package com.carddemo.online;

import java.util.Optional;

import com.carddemo.identity.SignedOnUser;

/**
 * How a task ends. Screens build one only through the helpers on {@link Screen}; the dispatcher is
 * the only code that takes one apart.
 */
public sealed interface Outcome permits Show, ShowText, Transfer, End {}

/** SEND MAP, then RETURN TRANSID with the screen's state. */
record Show(SymbolicMap<?> map, SendMode mode, ScreenState state) implements Outcome {}

/** SEND TEXT, then RETURN TRANSID with the screen's state. */
record ShowText(String text, ScreenState state) implements Outcome {}

/**
 * XCTL. The dispatcher runs the target's first display in the same request and transaction. With
 * {@code ifNotInstalled}, a program no slice has ported yet gets that outcome instead of the pending screen.
 */
record Transfer(ProgramName to, Optional<Handoff> handoff, IdentityChange identity, Optional<Outcome> ifNotInstalled)
        implements Outcome {}

/** SEND TEXT, then RETURN without a transaction id: the conversation is over. */
record End(String text) implements Outcome {}

/** What an XCTL does to the terminal's identity. Applied after the task commits. */
sealed interface IdentityChange {

    record Keep() implements IdentityChange {}

    record SignIn(SignedOnUser user) implements IdentityChange {}

    record SignOff() implements IdentityChange {}

    /** The user the rest of this task's XCTL chain runs as. */
    default Optional<SignedOnUser> applyTo(Optional<SignedOnUser> before) {
        return switch (this) {
            case Keep keep -> before;
            case SignIn signIn -> Optional.of(signIn.user());
            case SignOff signOff -> Optional.empty();
        };
    }
}
