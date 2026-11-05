package com.carddemo.mq;

/** The MQRC values the adapters return, with the completion code each implies. */
public enum Reason {
    NONE(0),
    HOBJ_ERROR(2019),
    NO_MSG_AVAILABLE(2033),
    UNKNOWN_OBJECT_NAME(2085);

    public static final int MQCC_OK = 0;
    public static final int MQCC_FAILED = 2;

    private final int code;

    Reason(int code) {
        this.code = code;
    }

    /** MQRC. */
    public int code() {
        return code;
    }

    /** MQCC. */
    public int completionCode() {
        return this == NONE ? MQCC_OK : MQCC_FAILED;
    }
}
