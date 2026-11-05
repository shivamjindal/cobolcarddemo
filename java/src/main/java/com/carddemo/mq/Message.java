package com.carddemo.mq;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * A message and the MQMD fields the programs use. {@code msgId} and {@code correlId} are MQBYTE24; a put with
 * {@link #NONE} as msgId gets one the queue manager makes up. {@code replyToQ} has no trailing spaces.
 */
public record Message(byte[] data, byte[] msgId, byte[] correlId, String replyToQ) {

    public static final int ID_LENGTH = 24;

    /** MQMI-NONE and MQCI-NONE. */
    public static final byte[] NONE = new byte[ID_LENGTH];

    public Message {
        data = data.clone();
        msgId = id(msgId, "msgId");
        correlId = id(correlId, "correlId");
        if (replyToQ.length() > QueueHandle.NAME_LENGTH || replyToQ.endsWith(" ")) {
            throw new IllegalArgumentException("replyToQ is MQCHAR48 without trailing spaces: '" + replyToQ + "'");
        }
    }

    /** A new message with no ids and no reply queue. */
    public static Message of(byte[] data) {
        return new Message(data, NONE, NONE, "");
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public byte[] msgId() {
        return msgId.clone();
    }

    @Override
    public byte[] correlId() {
        return correlId.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Message m && Arrays.equals(data, m.data) && Arrays.equals(msgId, m.msgId)
                && Arrays.equals(correlId, m.correlId) && replyToQ.equals(m.replyToQ);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * Arrays.hashCode(data) + Arrays.hashCode(msgId)) + Arrays.hashCode(correlId))
                + replyToQ.hashCode();
    }

    @Override
    public String toString() {
        HexFormat hex = HexFormat.of();
        return "Message[data=" + hex.formatHex(data) + ", msgId=" + hex.formatHex(msgId) + ", correlId="
                + hex.formatHex(correlId) + ", replyToQ=" + replyToQ + "]";
    }

    private static byte[] id(byte[] id, String name) {
        if (id.length != ID_LENGTH) {
            throw new IllegalArgumentException(name + " is MQBYTE24, not " + id.length + " bytes");
        }
        return id.clone();
    }
}
