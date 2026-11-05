package com.carddemo.batch.runtime;

import java.util.Collection;

/** How a step or a job ended, labelled as the characterization harness reports it. */
public sealed interface Completion {

    String label();

    record Rc(int code) implements Completion {
        public String label() {
            return "RC=" + code;
        }
    }

    record Abended(String code) implements Completion {
        public String label() {
            return code;
        }
    }

    /** The step's DDs could not be allocated, or the deck did not parse; nothing after it runs. */
    record JclError(String reason) implements Completion {
        public String label() {
            return "JCL ERROR";
        }
    }

    /** COND held, so the step did not run. */
    record Bypassed() implements Completion {
        public String label() {
            return "BYPASSED";
        }
    }

    /** An earlier step abended or failed allocation, so the step did not run. */
    record Flushed() implements Completion {
        public String label() {
            return "FLUSH";
        }
    }

    static Completion parse(String label) {
        return switch (label) {
            case "JCL ERROR" -> new JclError("");
            case "BYPASSED" -> new Bypassed();
            case "FLUSH" -> new Flushed();
            default -> label.startsWith("RC=") ? new Rc(Integer.parseInt(label.substring(3))) : new Abended(label);
        };
    }

    /** The job's completion: the first abend or JCL error, otherwise the highest return code. */
    static Completion worst(Collection<Completion> steps) {
        int highest = 0;
        for (Completion step : steps) {
            switch (step) {
                case Abended a -> {
                    return a;
                }
                case JclError e -> {
                    return e;
                }
                case Rc rc -> highest = Math.max(highest, rc.code());
                default -> {
                }
            }
        }
        return new Rc(highest);
    }
}
