package com.carddemo.batch.chains;

import java.util.List;
import java.util.Optional;

/**
 * A scheduler chain: its members in trigger order and the calendar its export gives it, as text. Nothing schedules
 * a chain unless {@code carddemo.batch.calendar.<name>} names a cron for it; {@code defaultCron} is what an
 * operator would set, and is never installed by itself.
 *
 * @param scheduleId the CA-7 SCHID or the Control-M folder the chain comes from
 */
public record Chain(String name, Scheduler scheduler, String scheduleId, List<Member> members, String exportCalendar,
        Optional<String> defaultCron) {

    public enum Scheduler { CA7, CONTROL_M }

    public Chain {
        members = List.copyOf(members);
        if (members.isEmpty()) {
            throw new IllegalArgumentException(name + " has no members");
        }
    }

    /** The members that keep the chain from starting. */
    public List<Member.Unported> unported() {
        return members.stream().filter(m -> m instanceof Member.Unported).map(m -> (Member.Unported) m).toList();
    }

    public boolean runnable() {
        return unported().isEmpty();
    }
}
