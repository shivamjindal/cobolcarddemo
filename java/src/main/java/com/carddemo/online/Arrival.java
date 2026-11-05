package com.carddemo.online;

import java.util.Optional;

import com.carddemo.identity.SignedOnUser;

/**
 * A first display: a transaction start on a clear screen, or an XCTL from another program.
 *
 * @param from    CDEMO-FROM-PROGRAM, empty for a transaction start
 * @param aid     the EIBAID of this task, which survives XCTL
 * @param handoff the typed COMMAREA extension the caller sent, if any
 * @param user    present whenever the screen's Access is not ANYONE
 */
public record Arrival<H extends Handoff>(Optional<ProgramName> from, Key aid, Optional<H> handoff,
        Optional<SignedOnUser> user) {

    public SignedOnUser signedOn() {
        return user.orElseThrow(() -> new IllegalStateException("screen declared Access.ANYONE"));
    }
}
