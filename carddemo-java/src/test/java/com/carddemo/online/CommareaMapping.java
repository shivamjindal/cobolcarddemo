package com.carddemo.online;

import java.util.Map;
import java.util.Optional;

/**
 * How one family of programs keeps its COMMAREA fields in the {@link Conversation}. SpecCase handles the
 * CDEMO-GENERAL-INFO fields every program shares and hands every other field to the program's mapping.
 */
interface CommareaMapping {

    /** Puts the given.commarea fields this family models into the conversation as typed state. */
    void load(String program, Map<String, Object> commarea, Conversation conversation);

    /** The Java value of a then.commarea field; empty when the field is implicit. Throws for unknown names. */
    Optional<String> read(String name, Conversation conversation);

    static CommareaMapping of(String program) {
        if (program.startsWith("COUSR")) {
            return new UserCommarea();
        }
        if (program.startsWith("COACT")) {
            return new AccountCommarea();
        }
        if (program.startsWith("COCRD")) {
            return new CardCommarea();
        }
        if (program.equals("COTRN00C") || program.equals("COTRN01C")) {
            return new TransactionCommarea();
        }
        if (program.equals("COTRTLIC") || program.equals("COTRTUPC")) {
            return new TranTypeCommarea();
        }
        return new CommareaMapping() {
            @Override
            public void load(String p, Map<String, Object> commarea, Conversation conversation) {
            }

            @Override
            public Optional<String> read(String name, Conversation conversation) {
                throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
            }
        };
    }
}
