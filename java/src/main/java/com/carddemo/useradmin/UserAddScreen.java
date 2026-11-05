package com.carddemo.useradmin;

import static com.carddemo.online.maps.COUSR1A.ERRMSG;
import static com.carddemo.online.maps.COUSR1A.FNAME;
import static com.carddemo.online.maps.COUSR1A.LNAME;
import static com.carddemo.online.maps.COUSR1A.PASSWD;
import static com.carddemo.online.maps.COUSR1A.USERID;
import static com.carddemo.online.maps.COUSR1A.USRTYPE;

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
import com.carddemo.online.NoHandoff;
import com.carddemo.online.NoState;
import com.carddemo.online.Outcome;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.COUSR1A;
import com.carddemo.users.UserId;
import com.carddemo.users.UserRecord;
import com.carddemo.users.UserStore;
import com.carddemo.users.UserTypeCode;

/** COUSR01C. The legend offers F12, but the program has no branch for it, so PF12 is an invalid key. */
@Component
public final class UserAddScreen extends Screen<COUSR1A, UserAddScreen.Action, NoState, NoHandoff> {

    public static final ScreenRef<NoHandoff> REF = ScreenRef.of("COUSR01C", "CU01", "user-add", NoHandoff.class);

    enum Action implements ScreenAction {
        ADD_USER(Key.ENTER), BACK(Key.PF3), CLEAR(Key.PF4);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    private static final Map<COUSR1A, String> EMPTY = Map.of(
            FNAME, "First Name can NOT be empty...",
            LNAME, "Last Name can NOT be empty...",
            USERID, "User ID can NOT be empty...",
            PASSWD, "Password can NOT be empty...",
            USRTYPE, "User Type can NOT be empty...");

    private final UserStore users;

    UserAddScreen(UserStore users) {
        super(REF, COUSR1A.MAP, Action.class, NoState.class, Access.ADMIN);
        this.users = users;
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return sendUsraddScreen(SymbolicMap.lowValues(COUSR1A.MAP).cursor(FNAME), "");
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COUSR1A> map, NoState state) {
        return switch (action) {
            case ADD_USER -> processEnterKey(map);
            case BACK -> transfer(AdminMenuScreen.REF.program());
            case CLEAR -> sendUsraddScreen(initializeAllFields(map), "");
        };
    }

    @Override
    protected Outcome invalidKey(SymbolicMap<COUSR1A> map, NoState state) {
        return sendUsraddScreen(map.cursor(FNAME), CommonMessages.INVALID_KEY);
    }

    private Outcome processEnterKey(SymbolicMap<COUSR1A> map) {
        Optional<COUSR1A> empty = map.firstBlank(FNAME, LNAME, USERID, PASSWD, USRTYPE);
        if (empty.isPresent()) {
            return sendUsraddScreen(map.cursor(empty.get()), EMPTY.get(empty.get()));
        }
        map.cursor(FNAME);
        UserRecord user = newUser(map);
        return switch (users.insert(user)) {
            case INSERTED -> sendUsraddScreen(initializeAllFields(map).color(ERRMSG, Color.GREEN),
                    "User " + user.id().delimitedBySpace() + " has been added ...");
            case DUPLICATE -> sendUsraddScreen(map.cursor(USERID), "User ID already exist...");
            case FAILED -> sendUsraddScreen(map, "Unable to Add User...");
        };
    }

    @LegacyDefect(id = "COUSR01C-id-as-typed",
            goldens = {"user-add-lowercase-id-kept"},
            behavior = "the user id is stored as typed, but COSGN00C uppercases the id it looks up, "
                    + "so a user added in lowercase can never sign on",
            fix = "uppercase the id here, as COSGN00C does")
    private static UserRecord newUser(SymbolicMap<COUSR1A> map) {
        return new UserRecord(UserId.of(map.text(USERID)), map.text(FNAME), map.text(LNAME), map.text(PASSWD),
                UserTypeCode.asTyped(map.text(USRTYPE)));
    }

    private static SymbolicMap<COUSR1A> initializeAllFields(SymbolicMap<COUSR1A> map) {
        return map.cursor(FNAME).blank(USERID, FNAME, LNAME, PASSWD, USRTYPE);
    }

    private Outcome sendUsraddScreen(SymbolicMap<COUSR1A> map, String message) {
        return show(map.set(ERRMSG, message), NoState.INSTANCE);
    }
}
