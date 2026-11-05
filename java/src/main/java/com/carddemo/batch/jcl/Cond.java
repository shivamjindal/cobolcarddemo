package com.carddemo.batch.jcl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * EXEC COND: the step is bypassed when any test {@code code op RC} holds for an earlier step that ran to a
 * return code, or for the named step only. Steps that were bypassed, abended or flushed are not tested.
 */
public record Cond(List<Test> tests) {

    public static final Cond NONE = new Cond(List.of());

    public Cond {
        tests = List.copyOf(tests);
    }

    public enum Operator {
        GT, GE, EQ, LT, LE, NE
    }

    public record Test(int code, Operator operator, Optional<String> step) {

        boolean holds(int rc) {
            return switch (operator) {
                case GT -> code > rc;
                case GE -> code >= rc;
                case EQ -> code == rc;
                case LT -> code < rc;
                case LE -> code <= rc;
                case NE -> code != rc;
            };
        }

        boolean names(String path) {
            return step.map(name -> path.equals(name) || path.endsWith("." + name)).orElse(true);
        }
    }

    /** {@code earlier} maps each earlier step path to its return code, empty when it ran to none. */
    public boolean bypass(Map<String, OptionalInt> earlier) {
        return tests.stream().anyMatch(test -> earlier.entrySet().stream()
                .anyMatch(step -> test.names(step.getKey()) && step.getValue().isPresent()
                        && test.holds(step.getValue().getAsInt())));
    }
}
