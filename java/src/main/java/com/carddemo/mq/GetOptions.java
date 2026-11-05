package com.carddemo.mq;

import java.time.Duration;

/**
 * The MQGMO options and MQMD selectors the programs set: MQGMO-WAIT with MQGMO-WAITINTERVAL, MQGMO-SYNCPOINT or
 * NO-SYNCPOINT, and the MQMD-MSGID and MQMD-CORRELID a message must carry, {@link Message#NONE} matching any.
 */
public record GetOptions(Duration waitInterval, boolean syncpoint, byte[] msgId, byte[] correlId) {

    public GetOptions {
        if (waitInterval.isNegative()) {
            throw new IllegalArgumentException("negative wait interval " + waitInterval);
        }
        if (msgId.length != Message.ID_LENGTH || correlId.length != Message.ID_LENGTH) {
            throw new IllegalArgumentException("MQMD-MSGID and MQMD-CORRELID are MQBYTE24");
        }
        msgId = msgId.clone();
        correlId = correlId.clone();
    }

    /** The next message, whatever its ids. */
    public static GetOptions waitMillis(long millis, boolean syncpoint) {
        return new GetOptions(Duration.ofMillis(millis), syncpoint, Message.NONE, Message.NONE);
    }

    @Override
    public byte[] msgId() {
        return msgId.clone();
    }

    @Override
    public byte[] correlId() {
        return correlId.clone();
    }
}
