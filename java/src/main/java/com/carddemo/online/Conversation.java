package com.carddemo.online;

import java.io.Serializable;
import java.util.Optional;

import jakarta.servlet.http.HttpSession;

/**
 * The pseudo-conversation of one terminal, in place of RETURN TRANSID COMMAREA. One session
 * attribute with one writer, the dispatcher. Identity lives in the SecurityContext, not here.
 *
 * @param turn      counts replies; a request must quote the turn it answers
 * @param current   the program whose screen is shown
 * @param state     that program's parked state
 * @param terminal  the screen as the user sees it
 * @param lastReply the reply that showed it
 */
record Conversation(long turn, ProgramName current, ScreenState state, Terminal terminal, Reply lastReply)
        implements Serializable {

    static final String ATTRIBUTE = "carddemo.conversation";

    static Optional<Conversation> in(HttpSession session) {
        return Optional.ofNullable((Conversation) session.getAttribute(ATTRIBUTE));
    }

    void storeIn(HttpSession session) {
        session.setAttribute(ATTRIBUTE, this);
    }
}
