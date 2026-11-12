package com.carddemo.online;

import java.util.Map;
import java.util.Optional;

/** COACTVWC and COACTUPC COMMAREA fields. */
class AccountCommarea implements CommareaMapping {

    @Override
    public void load(String program, Map<String, Object> commarea, Conversation conversation) {
    }

    @Override
    public Optional<String> read(String name, Conversation conversation) {
        throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
    }
}
