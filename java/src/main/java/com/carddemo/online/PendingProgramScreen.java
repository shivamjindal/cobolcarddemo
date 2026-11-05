package com.carddemo.online;

import org.springframework.stereotype.Component;

/**
 * Stands in for every program a later slice ports. The catalog resolves an XCTL to an unported
 * program here; PF3 returns to the program that transferred.
 */
@Component
final class PendingProgramScreen extends Screen<NoMap, PendingProgramScreen.Action, PendingProgramScreen.State,
        PendingProgramScreen.Requested> {

    static final ScreenRef<Requested> REF = ScreenRef.withoutTransid("PENDING", "pending", Requested.class);

    private static final ProgramName SIGN_ON = ProgramName.of("COSGN00C");

    enum Action implements ScreenAction {
        BACK(Key.PF3);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    record Requested(ProgramName program) implements Handoff {}

    record State(ProgramName program, ProgramName returnTo) implements ScreenState {}

    PendingProgramScreen() {
        super(REF, NoMap.MAP, Action.class, State.class, Access.SIGNED_ON);
    }

    @Override
    protected Outcome enter(Arrival<Requested> arrival) {
        ProgramName program = arrival.handoff().orElseThrow().program();
        return pending(new State(program, arrival.from().orElse(SIGN_ON)));
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<NoMap> map, State state) {
        return switch (action) {
            case BACK -> transfer(state.returnTo());
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<NoMap> map, State state) {
        return pending(state);
    }

    private Outcome pending(State state) {
        return showText(state.program().value() + " is not migrated yet.", state);
    }
}
