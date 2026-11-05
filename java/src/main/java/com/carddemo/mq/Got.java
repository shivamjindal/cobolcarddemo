package com.carddemo.mq;

import java.util.Optional;

/** What MQGET returned: a message with {@link Reason#NONE}, or none with the reason. */
public record Got(Reason reason, Optional<Message> message) {

    public boolean ok() {
        return reason == Reason.NONE;
    }

    static Got ok(Message message) {
        return new Got(Reason.NONE, Optional.of(message));
    }

    static Got failed(Reason reason) {
        return new Got(reason, Optional.empty());
    }
}
