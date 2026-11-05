package com.carddemo.menu;

import static com.carddemo.online.maps.COMEN1A.ERRMSG;
import static com.carddemo.online.maps.COMEN1A.OPTION;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.Color;
import com.carddemo.online.CommonMessages;
import com.carddemo.online.Key;
import com.carddemo.online.NoHandoff;
import com.carddemo.online.NoState;
import com.carddemo.online.Outcome;
import com.carddemo.online.ProgramName;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.SendMode;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.COMEN1A;
import com.carddemo.signon.SignonScreen;

/** COMEN01C, the home of a regular user. */
@Component
public final class MainMenuScreen extends Screen<COMEN1A, MainMenuScreen.Action, NoState, NoHandoff> {

    public static final ScreenRef<NoHandoff> REF = ScreenRef.of("COMEN01C", "CM00", "main-menu", NoHandoff.class);

    private static final ProgramName PENDING_AUTHORIZATIONS = ProgramName.of("COPAUS0C");

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

    MainMenuScreen() {
        super(REF, COMEN1A.MAP, Action.class, NoState.class, Access.SIGNED_ON);
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return sendMenuScreen(SymbolicMap.lowValues(COMEN1A.MAP), "");
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COMEN1A> map, NoState state) {
        return switch (action) {
            case SELECT -> processEnterKey(map);
            case SIGN_OFF -> signOff(SignonScreen.REF.program());
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<COMEN1A> map, NoState state) {
        return sendMenuScreen(map, CommonMessages.INVALID_KEY);
    }

    private Outcome processEnterKey(SymbolicMap<COMEN1A> map) {
        TypedOption option = TypedOption.read(map, OPTION);
        map.set(OPTION, option.echo());
        Optional<MenuTables.Entry> chosen = option.in(MenuTables.MAIN);
        if (chosen.isEmpty()) {
            return sendMenuScreen(map, "Please enter a valid option number...");
        }
        MenuTables.Entry entry = chosen.get();
        if (entry.program().equals(PENDING_AUTHORIZATIONS)) {
            return transferIfInstalled(entry.program(), sendMenuScreen(map.color(ERRMSG, Color.RED),
                    "This option " + entry.name() + " is not installed..."));
        }
        return transfer(entry.program());
    }

    /** SEND MAP ERASE without CURSOR, so the cursor goes to the IC field OPTION. */
    private Outcome sendMenuScreen(SymbolicMap<COMEN1A> map, String message) {
        return show(MenuTables.build(map, COMEN1A.MAP, MenuTables.MAIN).set(ERRMSG, message), SendMode.ERASE,
                NoState.INSTANCE);
    }
}
