package com.carddemo.mq;

import java.util.List;

/**
 * The MQI calls the programs make, on one queue manager. An adapter decides where messages live; the calls and
 * their reason codes stay those of IBM MQ, so a program ported against this port runs on any adapter.
 * <ul>
 *   <li>A syncpointed GET or PUT is part of the caller's unit of work: it takes effect when the task commits and
 *       is undone when the task rolls back. Without syncpoint it takes effect at once, whatever the task does.</li>
 *   <li>A GET takes the oldest message that matches, waiting up to the wait interval for one to arrive.</li>
 * </ul>
 */
public interface MessageQueues {

    /** {@code MQOPEN} of {@code queue} for input or output. */
    Opened open(String queue);

    /** {@code MQGET}: destructive, the first message in put order that carries the ids the options select. */
    Got get(QueueHandle handle, GetOptions options);

    /** {@code MQPUT}. */
    Reason put(QueueHandle handle, Message message, boolean syncpoint);

    /** {@code MQPUT1}: open, put and close in one call. */
    Reason put1(String queue, Message message, boolean syncpoint);

    /** {@code MQCLOSE}. */
    Reason close(QueueHandle handle);

    /** {@code MQGET} with BROWSE-FIRST then BROWSE-NEXT to the end: every committed message in put order, none taken. */
    List<Message> browse(String queue);
}
