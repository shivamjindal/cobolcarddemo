package com.carddemo.mq;

import java.util.Optional;

/** What MQOPEN returned: a handle with {@link Reason#NONE}, or none with the reason. */
public record Opened(Reason reason, Optional<QueueHandle> handle) {

    static Opened ok(QueueHandle handle) {
        return new Opened(Reason.NONE, Optional.of(handle));
    }

    static Opened failed(Reason reason) {
        return new Opened(reason, Optional.empty());
    }
}
