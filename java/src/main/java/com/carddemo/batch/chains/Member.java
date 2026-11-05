package com.carddemo.batch.chains;

import com.carddemo.batch.library.LibraryJob;

/** One job of a chain, in the order the scheduler export triggers it. */
public sealed interface Member {

    String name();

    /** A library job, run as its own JCL job. */
    record Job(LibraryJob job) implements Member {
        public String name() {
            return job.name();
        }
    }

    /** A job of an optional module this slice does not install (IMS, Db2, REXX): bypassed, and the chain goes on. */
    record Optional(String name, String module) implements Member {}

    /** A base job this slice has not ported. A chain holding one cannot start. */
    record Unported(String name, String reason) implements Member {}
}
