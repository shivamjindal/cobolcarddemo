package com.carddemo.mq;

import java.util.List;

/**
 * A CICS program that an MQ trigger starts (COPAUA0C, COACCT01, CODATE01). It gets each request from its input
 * queue and returns the messages it puts. One request is one unit of work, like the legacy SYNCPOINT per message:
 * {@link #process} runs in a database transaction, and the get and the puts commit with it or roll back with it.
 */
public interface MessageProgram {

    String program();

    /** The input queue; on the mainframe the trigger message names it at run time. */
    String requestQueue();

    List<MqMessage.Put> process(MqMessage request);
}
