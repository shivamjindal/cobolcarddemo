package com.carddemo.online;

import java.util.Map;

/** What a program does at the end of its task. */
public sealed interface Outcome {

    /** SEND MAP and RETURN TRANSID: fields by BMS base name, and the field that gets the cursor. */
    record Show(Map<String, String> fields, Message message, String cursor) implements Outcome {
    }

    /** XCTL. The engine records the current program as {@link Conversation#from()}. */
    record Transfer(Program to) implements Outcome {
    }

    /** Ends the conversation; a null message returns to a fresh sign-on screen. */
    record SignOff(Message message) implements Outcome {
    }
}
