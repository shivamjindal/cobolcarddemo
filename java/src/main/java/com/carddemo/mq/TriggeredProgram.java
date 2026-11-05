package com.carddemo.mq;

/**
 * A program a trigger starts, as a Spring bean: COPAUA0C, COACCT01 and CODATE01 register here. {@link #run} is
 * the whole task inside one transaction; throwing {@link TaskEnd.AbendException} or any other runtime exception
 * abends it, which rolls back its syncpointed work.
 */
public interface TriggeredProgram {

    /** The transid that starts it, such as CP00. */
    String transid();

    /** The program its transaction runs, such as COPAUA0C. */
    String program();

    void run(Trigger trigger);
}
