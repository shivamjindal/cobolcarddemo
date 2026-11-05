package com.carddemo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the one place a legacy defect is reproduced for parity. Fixing the defect means changing the
 * annotated element and re-recording the named goldens. LegacyDefectRegisterTest derives
 * {@code docs/legacy-defects.md} from these annotations.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface LegacyDefect {

    /** A stable slug such as "COUSR03C-delete-without-fetch". */
    String id();

    /** Golden case ids that pin the behavior. Empty when it is seen in the source only. */
    String[] goldens() default {};

    String behavior();

    String fix();

    Side side() default Side.PROGRAM;

    enum Side {
        /** The COBOL program logic does it. */
        PROGRAM,
        /** BMS or 3270 behavior produces it and the program does not guard against it. */
        TERMINAL
    }
}
