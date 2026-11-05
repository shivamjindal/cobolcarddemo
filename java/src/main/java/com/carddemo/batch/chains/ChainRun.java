package com.carddemo.batch.chains;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.carddemo.batch.runtime.Completion;

/** A chain's run for one order date: each member's completion, in chain order. */
public record ChainRun(String chain, LocalDate orderDate, List<MemberRun> members) {

    /**
     * @param completion empty while the member has not run to an end, or was never reached
     * @param jobExecutionId the member's JCL job, which keys its spool; empty when the member ran no job
     */
    public record MemberRun(String member, Optional<Completion> completion, OptionalLong jobExecutionId) {

        /** RC 4 or less, or an optional member bypassed. An abend, a JCL error or a higher RC stops the chain. */
        public boolean endedOk() {
            return completion.filter(ChainRun::endedOk).isPresent();
        }
    }

    public ChainRun {
        members = List.copyOf(members);
    }

    public boolean completed() {
        return members.stream().allMatch(MemberRun::endedOk);
    }

    static boolean endedOk(Completion completion) {
        return switch (completion) {
            case Completion.Rc rc -> rc.code() <= 4;
            case Completion.Bypassed bypassed -> true;
            default -> false;
        };
    }
}
