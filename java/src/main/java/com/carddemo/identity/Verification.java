package com.carddemo.identity;

public sealed interface Verification {

    record Verified(SignedOnUser user) implements Verification {}

    /** RESP NOTFND. */
    record UnknownUser() implements Verification {}

    record WrongPassword() implements Verification {}

    /** Any other RESP. */
    record Unavailable() implements Verification {}
}
