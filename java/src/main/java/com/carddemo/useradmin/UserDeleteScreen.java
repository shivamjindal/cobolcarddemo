package com.carddemo.useradmin;

import static com.carddemo.online.maps.COUSR3A.ERRMSG;
import static com.carddemo.online.maps.COUSR3A.FNAME;
import static com.carddemo.online.maps.COUSR3A.LNAME;
import static com.carddemo.online.maps.COUSR3A.USRIDIN;
import static com.carddemo.online.maps.COUSR3A.USRTYPE;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.menu.AdminMenuScreen;
import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.Color;
import com.carddemo.online.CommonMessages;
import com.carddemo.online.Key;
import com.carddemo.online.Outcome;
import com.carddemo.online.ProgramName;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.ScreenState;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.COUSR3A;
import com.carddemo.users.UserId;
import com.carddemo.users.UserStore;

/** COUSR03C. */
@Component
public final class UserDeleteScreen extends Screen<COUSR3A, UserDeleteScreen.Action, UserDeleteScreen.State, UserSelected> {

    public static final ScreenRef<UserSelected> REF = ScreenRef.of("COUSR03C", "CU03", "user-delete", UserSelected.class);

    enum Action implements ScreenAction {
        FETCH(Key.ENTER), BACK(Key.PF3), CLEAR(Key.PF4), DELETE(Key.PF5), CANCEL(Key.PF12);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    /** @param returnTo CDEMO-FROM-PROGRAM, where PF3 goes; the COMMAREA keeps it across turns */
    record State(ProgramName returnTo) implements ScreenState {}

    private final UserStore users;

    UserDeleteScreen(UserStore users) {
        super(REF, COUSR3A.MAP, Action.class, State.class, Access.ADMIN);
        this.users = users;
    }

    @Override
    protected Outcome enter(Arrival<UserSelected> arrival) {
        State state = new State(arrival.from().orElse(AdminMenuScreen.REF.program()));
        SymbolicMap<COUSR3A> map = SymbolicMap.lowValues(COUSR3A.MAP);
        return arrival.handoff()
                .map(selected -> processEnterKey(map.set(USRIDIN, selected.user().value()), state))
                .orElseGet(() -> sendUsrdelScreen(map.cursor(USRIDIN), "", state));
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COUSR3A> map, State state) {
        return switch (action) {
            case FETCH -> processEnterKey(map, state);
            case BACK -> transfer(state.returnTo());
            case CLEAR -> sendUsrdelScreen(initializeAllFields(map), "", state);
            case DELETE -> deleteUserInfo(map, state);
            case CANCEL -> transfer(AdminMenuScreen.REF.program());
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<COUSR3A> map, State state) {
        return sendUsrdelScreen(map, CommonMessages.INVALID_KEY, state);
    }

    private Outcome processEnterKey(SymbolicMap<COUSR3A> map, State state) {
        map.cursor(USRIDIN);
        if (map.isBlank(USRIDIN)) {
            return sendUsrdelScreen(map, "User ID can NOT be empty...", state);
        }
        map.blank(FNAME, LNAME, USRTYPE);
        return switch (users.findForUpdate(UserId.of(map.text(USRIDIN)))) {
            case UserStore.Held held -> sendUsrdelScreen(map.set(FNAME, held.user().firstName())
                            .set(LNAME, held.user().lastName())
                            .set(USRTYPE, held.user().type().toString())
                            .color(ERRMSG, Color.NEUTR),
                    "Press PF5 key to delete this user ...", state);
            case UserStore.Missing missing -> sendUsrdelScreen(map, "User ID NOT found...", state);
            case UserStore.Failed failed -> sendUsrdelScreen(map, "Unable to lookup User...", state);
        };
    }

    @LegacyDefect(id = "COUSR03C-delete-without-fetch",
            goldens = {"user-delete-pf5-without-fetch"},
            behavior = "PF5 deletes whatever id is typed, so a user is deleted without ever being shown",
            fix = "keep the fetched id in State and delete only that id")
    private Outcome deleteUserInfo(SymbolicMap<COUSR3A> map, State state) {
        map.cursor(USRIDIN);
        if (map.isBlank(USRIDIN)) {
            return sendUsrdelScreen(map, "User ID can NOT be empty...", state);
        }
        UserId id = UserId.of(map.text(USRIDIN));
        return switch (users.findForUpdate(id)) {
            case UserStore.Held held -> switch (users.delete(held)) {
                case DONE -> sendUsrdelScreen(initializeAllFields(map).color(ERRMSG, Color.GREEN),
                        "User " + id.delimitedBySpace() + " has been deleted ...", state);
                case FAILED -> sendUsrdelScreen(map.color(ERRMSG, Color.NEUTR), "Unable to Update User...", state);
            };
            case UserStore.Missing missing -> deleteWithoutLock(map, state);
            case UserStore.Failed failed -> deleteWithoutLock(map, state);
        };
    }

    @LegacyDefect(id = "COUSR03C-missing-user-on-delete",
            behavior = "PF5 on an id that is not on file, or whose READ UPDATE fails, still runs DELETE, "
                    + "and its INVREQ replaces 'User ID NOT found...' with 'Unable to Update User...'",
            fix = "stop after the failed READ and keep its message")
    private Outcome deleteWithoutLock(SymbolicMap<COUSR3A> map, State state) {
        return sendUsrdelScreen(map, "Unable to Update User...", state);
    }

    private static SymbolicMap<COUSR3A> initializeAllFields(SymbolicMap<COUSR3A> map) {
        return map.cursor(USRIDIN).blank(USRIDIN, FNAME, LNAME, USRTYPE);
    }

    private Outcome sendUsrdelScreen(SymbolicMap<COUSR3A> map, String message, State state) {
        return show(map.set(ERRMSG, message), state);
    }
}
