package com.carddemo.batch.chains;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import com.carddemo.batch.runtime.Completion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainRunTest {

    @Test
    void aMemberEndsOkAtRcFourOrBelowOrBypassed() {
        assertTrue(member(new Completion.Rc(4)).endedOk());
        assertTrue(member(new Completion.Bypassed()).endedOk());
        assertFalse(member(new Completion.Rc(8)).endedOk());
        assertFalse(member(new Completion.Abended("U0999")).endedOk());
        assertFalse(member(new Completion.JclError("no data set")).endedOk());
        assertFalse(new ChainRun.MemberRun("WAITSTEP", Optional.empty(), OptionalLong.empty()).endedOk());
    }

    @Test
    void aRunCompletesOnlyWhenEveryMemberEndedOk() {
        assertTrue(run(member(new Completion.Rc(4)), member(new Completion.Bypassed())).completed());
        assertFalse(run(member(new Completion.Rc(0)), member(new Completion.Rc(8))).completed());
    }

    private static ChainRun.MemberRun member(Completion completion) {
        return new ChainRun.MemberRun("POSTTRAN", Optional.of(completion), OptionalLong.of(1));
    }

    private static ChainRun run(ChainRun.MemberRun... members) {
        return new ChainRun("TEST", LocalDate.of(2026, 11, 5), List.of(members));
    }
}
