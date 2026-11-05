package com.carddemo.useradmin;

import static com.carddemo.online.maps.COUSR2A.ERRMSG;
import static com.carddemo.online.maps.COUSR2A.FNAME;
import static com.carddemo.online.maps.COUSR2A.LNAME;
import static com.carddemo.online.maps.COUSR2A.PASSWD;
import static com.carddemo.online.maps.COUSR2A.USRIDIN;
import static com.carddemo.online.maps.COUSR2A.USRTYPE;

import java.util.Map;
import java.util.Optional;

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
import com.carddemo.online.maps.COUSR2A;
import com.carddemo.users.UserId;
import com.carddemo.users.UserRecord;
import com.carddemo.users.UserStore;
import com.carddemo.users.UserTypeCode;

/**
 * COUSR02C. PF3 saves exactly as PF5 does, valid or not, and then leaves, so the caller's screen
 * replaces whatever the save showed.
 */
@Component
public final class UserUpdateScreen extends Screen<COUSR2A, UserUpdateScreen.Action, UserUpdateScreen.State, UserSelected> {

    public static final ScreenRef<UserSelected> REF = ScreenRef.of("COUSR02C", "CU02", "user-update", UserSelected.class);

    enum Action implements ScreenAction {
        FETCH(Key.ENTER), SAVE_AND_EXIT(Key.PF3), CLEAR(Key.PF4), SAVE(Key.PF5), CANCEL(Key.PF12);

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

    private static final Map<COUSR2A, String> EMPTY = Map.of(
            USRIDIN, "User ID can NOT be empty...",
            FNAME, "First Name can NOT be empty...",
            LNAME, "Last Name can NOT be empty...",
            PASSWD, "Password can NOT be empty...",
            USRTYPE, "User Type can NOT be empty...");

    private final UserStore users;

    UserUpdateScreen(UserStore users) {
        super(REF, COUSR2A.MAP, Action.class, State.class, Access.ADMIN);
        this.users = users;
    }

    @Override
    protected Outcome enter(Arrival<UserSelected> arrival) {
        State state = new State(arrival.from().orElse(AdminMenuScreen.REF.program()));
        SymbolicMap<COUSR2A> map = SymbolicMap.lowValues(COUSR2A.MAP);
        return arrival.handoff()
                .map(selected -> processEnterKey(map.set(USRIDIN, selected.user().value()), state))
                .orElseGet(() -> sendUsrupdScreen(map.cursor(USRIDIN), "", state));
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COUSR2A> map, State state) {
        return switch (action) {
            case FETCH -> processEnterKey(map, state);
            case SAVE_AND_EXIT -> {
                updateUserInfo(map, state);
                yield transfer(state.returnTo());
            }
            case CLEAR -> sendUsrupdScreen(map.cursor(USRIDIN).blank(USRIDIN, FNAME, LNAME, PASSWD, USRTYPE), "", state);
            case SAVE -> updateUserInfo(map, state);
            case CANCEL -> transfer(AdminMenuScreen.REF.program());
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<COUSR2A> map, State state) {
        return sendUsrupdScreen(map, CommonMessages.INVALID_KEY, state);
    }

    private Outcome processEnterKey(SymbolicMap<COUSR2A> map, State state) {
        map.cursor(USRIDIN);
        if (map.isBlank(USRIDIN)) {
            return sendUsrupdScreen(map, EMPTY.get(USRIDIN), state);
        }
        map.blank(FNAME, LNAME, PASSWD, USRTYPE);
        return switch (users.findForUpdate(UserId.of(map.text(USRIDIN)))) {
            case UserStore.Held held -> sendUsrupdScreen(showUser(map, held.user()).color(ERRMSG, Color.NEUTR),
                    "Press PF5 key to save your updates ...", state);
            case UserStore.Missing missing -> sendUsrupdScreen(map, "User ID NOT found...", state);
            case UserStore.Failed failed -> sendUsrupdScreen(map, "Unable to lookup User...", state);
        };
    }

    @LegacyDefect(id = "COUSR02C-password-shown",
            goldens = {"user-update-fetch", "user-update-from-list"},
            behavior = "a fetch sends the stored plaintext password to the terminal; PASSWD is dark, "
                    + "but the password is in the data stream",
            fix = "leave PASSWD low-value on fetch and keep the stored password unless a new one is typed")
    private static SymbolicMap<COUSR2A> showUser(SymbolicMap<COUSR2A> map, UserRecord user) {
        return map.set(FNAME, user.firstName())
                .set(LNAME, user.lastName())
                .set(PASSWD, user.password())
                .set(USRTYPE, user.type().toString());
    }

    private Outcome updateUserInfo(SymbolicMap<COUSR2A> map, State state) {
        Optional<COUSR2A> empty = map.firstBlank(USRIDIN, FNAME, LNAME, PASSWD, USRTYPE);
        if (empty.isPresent()) {
            return sendUsrupdScreen(map.cursor(empty.get()), EMPTY.get(empty.get()), state);
        }
        map.cursor(FNAME);
        UserRecord edited = new UserRecord(UserId.of(map.text(USRIDIN)), map.text(FNAME), map.text(LNAME),
                map.text(PASSWD), UserTypeCode.asTyped(map.text(USRTYPE)));
        return switch (users.findForUpdate(edited.id())) {
            case UserStore.Held held when edited.equals(held.user()) ->
                    sendUsrupdScreen(map.color(ERRMSG, Color.RED), "Please modify to update ...", state);
            case UserStore.Held held -> switch (users.rewrite(held, edited)) {
                case DONE -> sendUsrupdScreen(map.color(ERRMSG, Color.GREEN),
                        "User " + edited.id().delimitedBySpace() + " has been updated ...", state);
                case FAILED -> sendUsrupdScreen(map.color(ERRMSG, Color.NEUTR), "Unable to Update User...", state);
            };
            case UserStore.Missing missing -> rewriteWithoutLock(map.cursor(USRIDIN), state);
            case UserStore.Failed failed -> rewriteWithoutLock(map, state);
        };
    }

    @LegacyDefect(id = "COUSR02C-missing-user-on-save",
            behavior = "a save of an id that is not on file, or whose READ UPDATE fails, still runs REWRITE, "
                    + "and its INVREQ replaces 'User ID NOT found...' with 'Unable to Update User...'",
            fix = "stop after the failed READ and keep its message")
    private Outcome rewriteWithoutLock(SymbolicMap<COUSR2A> map, State state) {
        return sendUsrupdScreen(map, "Unable to Update User...", state);
    }

    private Outcome sendUsrupdScreen(SymbolicMap<COUSR2A> map, String message, State state) {
        return show(map.set(ERRMSG, message), state);
    }
}
