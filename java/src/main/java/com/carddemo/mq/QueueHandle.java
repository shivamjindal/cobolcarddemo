package com.carddemo.mq;

/** The HOBJ an MQOPEN returns, with the queue it opened. */
public record QueueHandle(int hobj, String queue) {

    public static final int NAME_LENGTH = 48;
}
