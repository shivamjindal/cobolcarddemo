package com.carddemo.batch.programs;

import org.springframework.stereotype.Component;

import com.carddemo.batch.step.BatchProgram;
import com.carddemo.batch.step.Step;

/** IEFBR14: the step's DDs are allocated and dispositioned, and the program itself does nothing. */
@Component
public final class Iefbr14 implements BatchProgram {

    public static final String NAME = "IEFBR14";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(Step step) {
        return 0;
    }
}
