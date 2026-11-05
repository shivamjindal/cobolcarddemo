package com.carddemo.signon;

import static com.carddemo.online.maps.COSGN0A.ERRMSG;
import static com.carddemo.online.maps.COSGN0A.PASSWD;
import static com.carddemo.online.maps.COSGN0A.USERID;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.identity.CredentialCheck;
import com.carddemo.identity.Verification;
import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.CommonMessages;
import com.carddemo.online.Key;
import com.carddemo.online.NoHandoff;
import com.carddemo.online.NoState;
import com.carddemo.online.Outcome;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.COSGN0A;
import com.carddemo.users.UserId;

/** COSGN00C. Transaction CC00 starts here. */
@Component
public final class SignonScreen extends Screen<COSGN0A, SignonScreen.Action, NoState, NoHandoff> {

    public static final ScreenRef<NoHandoff> REF = ScreenRef.of("COSGN00C", "CC00", "signon", NoHandoff.class);

    enum Action implements ScreenAction {
        SIGN_ON(Key.ENTER), EXIT(Key.PF3);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    private final CredentialCheck credentials;

    SignonScreen(CredentialCheck credentials) {
        super(REF, COSGN0A.MAP, Action.class, NoState.class, Access.ANYONE);
        this.credentials = credentials;
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return sendSignonScreen(SymbolicMap.lowValues(COSGN0A.MAP).cursor(USERID), "");
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<COSGN0A> map, NoState state) {
        return switch (action) {
            case SIGN_ON -> processEnterKey(map);
            case EXIT -> end(CommonMessages.THANK_YOU);
        };
    }

    /** WHEN OTHER sends without a RECEIVE, so the program's map is untouched working storage. */
    @Override
    protected Outcome invalidKey(SymbolicMap<COSGN0A> received, NoState state) {
        return sendSignonScreen(SymbolicMap.lowValues(COSGN0A.MAP), CommonMessages.INVALID_KEY);
    }

    private Outcome processEnterKey(SymbolicMap<COSGN0A> map) {
        if (map.isBlank(USERID)) {
            return sendSignonScreen(map.cursor(USERID), "Please enter User ID ...");
        }
        if (passwordBlank(map)) {
            return sendSignonScreen(map.cursor(PASSWD), "Please enter Password ...");
        }
        UserId id = UserId.of(upperCase(map.text(USERID)));
        Verification result = credentials.verify(id, upperCase(map.text(PASSWD)));
        if (result instanceof Verification.Verified verified) {
            return signIn(verified.user(), HomeScreen.of(verified.user()));
        }
        return rejection(result, map);
    }

    @LegacyDefect(id = "COSGN00C-untouched-password",
            goldens = {"signon-untouched-password"},
            side = LegacyDefect.Side.TERMINAL,
            behavior = "PASSWD has BMS INITIAL '________' and FSET, so an untouched field transmits the "
                    + "underscores; the program does not treat them as blank and answers Wrong Password",
            fix = "also treat the all-underscore initial as blank here, or drop INITIAL in COSGN00.bms")
    private static boolean passwordBlank(SymbolicMap<COSGN0A> map) {
        return map.isBlank(PASSWD);
    }

    @LegacyDefect(id = "COSGN00C-user-enumeration",
            goldens = {"signon-unknown-user", "signon-wrong-password"},
            behavior = "distinct messages tell an attacker whether a user id exists",
            fix = "one message and one cursor position for UnknownUser and WrongPassword")
    private Outcome rejection(Verification result, SymbolicMap<COSGN0A> map) {
        return switch (result) {
            case Verification.WrongPassword wrong -> sendSignonScreen(map.cursor(PASSWD), "Wrong Password. Try again ...");
            case Verification.UnknownUser unknown -> sendSignonScreen(map.cursor(USERID), "User not found. Try again ...");
            case Verification.Unavailable unavailable -> sendSignonScreen(map.cursor(USERID), "Unable to verify the User ...");
            case Verification.Verified verified -> throw new IllegalArgumentException("not a rejection");
        };
    }

    private Outcome sendSignonScreen(SymbolicMap<COSGN0A> map, String message) {
        return show(map.set(ERRMSG, message), NoState.INSTANCE);
    }

    /** FUNCTION UPPER-CASE as GnuCOBOL applies it to these single-byte fields: a to z only. */
    private static String upperCase(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            out.append(c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c);
        }
        return out.toString();
    }
}
