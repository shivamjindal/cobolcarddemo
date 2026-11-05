package com.carddemo.menu;

import static com.carddemo.online.maps.COADM1A.ERRMSG;
import static com.carddemo.online.maps.COADM1A.OPTION;

import org.springframework.stereotype.Component;

import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.Color;
import com.carddemo.online.CommonMessages;
import com.carddemo.online.Key;
import com.carddemo.online.NoHandoff;
import com.carddemo.online.NoState;
import com.carddemo.online.Outcome;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.SendMode;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.COADM1A;
import com.carddemo.signon.SignonScreen;

/** COADM01C, the home of an admin user. */
@Component
public final class AdminMenuScreen extends Screen<COADM1A, AdminMenuScreen.Action, NoState, NoHandoff> {

    public static final ScreenRef<NoHandoff> REF = ScreenRef.of("COADM01C", "CA00", "admin-menu", NoHandoff.class);

    enum Action implements ScreenAction {
        SELECT(Key.ENTER), SIGN_OFF(Key.PF3);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    AdminMenuScreen() {
        super(REF, COADM1A.MAP, Action.class, NoState.class, Access.ADMIN);
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return sendMenuScreen(SymbolicMap.lowValues(COADM1A.MAP), "");
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COADM1A> map, NoState state) {
        return switch (action) {
            case SELECT -> processEnterKey(map);
            case SIGN_OFF -> signOff(SignonScreen.REF.program());
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<COADM1A> map, NoState state) {
        return sendMenuScreen(map, CommonMessages.INVALID_KEY);
    }

    /** Every XCTL here runs under HANDLE CONDITION PGMIDERR(PGMIDERR-ERR-PARA). */
    private Outcome processEnterKey(SymbolicMap<COADM1A> map) {
        TypedOption option = TypedOption.read(map, OPTION);
        map.set(OPTION, option.echo());
        return option.in(MenuTables.ADMIN)
                .map(entry -> transferIfInstalled(entry.program(),
                        sendMenuScreen(map.color(ERRMSG, Color.GREEN), "This option is not installed ...")))
                .orElseGet(() -> sendMenuScreen(map, "Please enter a valid option number..."));
    }

    /** SEND MAP ERASE without CURSOR, so the cursor goes to the IC field OPTION. */
    private Outcome sendMenuScreen(SymbolicMap<COADM1A> map, String message) {
        return show(MenuTables.build(map, COADM1A.MAP, MenuTables.ADMIN).set(ERRMSG, message), SendMode.ERASE,
                NoState.INSTANCE);
    }
}
