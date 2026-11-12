package com.carddemo.online;

import java.util.Map;
import java.util.Optional;

/** COCRDLIC, COCRDSLC and COCRDUPC COMMAREA fields. */
class CardCommarea implements CommareaMapping {

    @Override
    public void load(String program, Map<String, Object> commarea, Conversation conversation) {
    }

    @Override
    public Optional<String> read(String name, Conversation conversation) {
        throw new IllegalArgumentException("No Java counterpart for COMMAREA field " + name);
    }
}
