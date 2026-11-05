package com.carddemo.account;

import static com.carddemo.online.maps.CACTVWA.ACCTSID;
import static com.carddemo.online.maps.CACTVWA.INFOMSG;

import org.springframework.stereotype.Component;

import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.Color;
import com.carddemo.online.FieldAttribute;
import com.carddemo.online.Key;
import com.carddemo.online.NoHandoff;
import com.carddemo.online.NoState;
import com.carddemo.online.Outcome;
import com.carddemo.online.ProgramName;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.CACTVWA;

/**
 * COACTVWC as far as the main menu needs it: the first display and PF3 back. The account slice
 * ports the lookup.
 */
@Component
final class AccountViewScreen extends Screen<CACTVWA, AccountViewScreen.Action, NoState, NoHandoff> {

    static final ScreenRef<NoHandoff> REF = ScreenRef.of("COACTVWC", "CAVW", "account-view", NoHandoff.class);

    /** Entered from the menu, COACTVWC initializes the COMMAREA, so PF3 finds no from-program and goes here. */
    private static final ProgramName MENU = ProgramName.of("COMEN01C");

    enum Action implements ScreenAction {
        LOOKUP(Key.ENTER), BACK(Key.PF3);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    AccountViewScreen() {
        super(REF, CACTVWA.MAP, Action.class, NoState.class, Access.SIGNED_ON);
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return show(SymbolicMap.lowValues(CACTVWA.MAP)
                .set(INFOMSG, "Enter or update id of account to display")
                .color(INFOMSG, Color.NEUTR)
                .attribute(ACCTSID, FieldAttribute.BMFSE)
                .cursor(ACCTSID), NoState.INSTANCE);
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<CACTVWA> map, NoState state) {
        return switch (action) {
            case LOOKUP -> notMigrated();
            case BACK -> transfer(MENU);
        };
    }

    /** COACTVWC treats every other key as ENTER. */
    @Override
    protected Outcome invalidKey(SymbolicMap<CACTVWA> map, NoState state) {
        return notMigrated();
    }
}
