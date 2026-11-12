package com.carddemo.online;

import java.util.Map;

/**
 * One migrated CICS program. GET of its screen is {@link #arrive} (XCTL in, PGM-CONTEXT 0); a submit
 * is {@link #receive} (RECEIVE MAP with an AID, PGM-CONTEXT 1). Fields are keyed by BMS base name and
 * right-trimmed; a field the client did not send is absent.
 */
public interface ScreenProgram {

    Program program();

    Outcome arrive(Conversation conversation);

    Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields);
}
