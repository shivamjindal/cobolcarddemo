package com.carddemo.mq;

/** The MQTM a triggered task RETRIEVEs: the queue that fired, the transid to start and the queue's TRIGDATA. */
public record Trigger(String queue, String transid, String triggerData) {}
