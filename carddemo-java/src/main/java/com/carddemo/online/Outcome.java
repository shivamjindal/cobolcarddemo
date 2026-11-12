package com.carddemo.online;

import java.util.Map;
import java.util.Set;

/** What a program does at the end of its task. */
public sealed interface Outcome {

    /**
     * SEND MAP and RETURN TRANSID: fields by BMS base name, and the field that gets the cursor. {@code readOnly}
     * names the input fields the map protects this time (DFHBMPRF): they render read-only, and the next submit
     * gets the value shown, whatever the client sends.
     */
    record Show(Map<String, String> fields, Message message, String cursor, Set<String> readOnly) implements Outcome {

        public Show {
            readOnly = Set.copyOf(readOnly);
        }

        public Show(Map<String, String> fields, Message message, String cursor) {
            this(fields, message, cursor, Set.of());
        }

        public Show protecting(Set<String> fields) {
            return new Show(this.fields, message, cursor, fields);
        }
    }

    /** XCTL. The engine records the current program as {@link Conversation#from()}. */
    record Transfer(Program to) implements Outcome {
    }

    /** Ends the conversation; a null message returns to a fresh sign-on screen. */
    record SignOff(Message message) implements Outcome {
    }
}
