package com.carddemo.online;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.carddemo.identity.SignedOnUser;

/** The terminal's signed-on identity. The dispatcher changes it only after the task commits. */
public interface TerminalIdentity {

    Optional<SignedOnUser> current();

    /** Authenticate the session: new session id, rotated CSRF token, context saved. */
    void signIn(SignedOnUser user, HttpServletRequest request, HttpServletResponse response);

    /** Clear the context, replace the session, rotate the CSRF token. */
    void signOff(HttpServletRequest request, HttpServletResponse response);
}
