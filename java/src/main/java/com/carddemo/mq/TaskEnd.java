package com.carddemo.mq;

/** How a triggered task ended. */
public sealed interface TaskEnd {

    record Normal() implements TaskEnd {}

    /** EXEC CICS ABEND ABCODE(code), or a failure the program did not handle (ASRA). */
    record Abended(String code) implements TaskEnd {}

    /** No installed program answers the transid; CICS would not start the task. */
    record NotDefined(String transid) implements TaskEnd {}

    /** Thrown by a {@link TriggeredProgram} for EXEC CICS ABEND. */
    final class AbendException extends RuntimeException {

        private final String code;

        public AbendException(String code) {
            super("abend " + code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
