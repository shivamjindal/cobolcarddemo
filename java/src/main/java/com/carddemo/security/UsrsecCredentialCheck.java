package com.carddemo.security;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.identity.CredentialCheck;
import com.carddemo.identity.SignedOnUser;
import com.carddemo.identity.Verification;
import com.carddemo.users.UserId;
import com.carddemo.users.UserRecord;
import com.carddemo.users.UserStore;

/**
 * COSGN00C's READ DATASET(USRSEC) and {@code IF SEC-USR-PWD = WS-USER-PWD}. USRSEC is the identity
 * source: user admin writes it and the next sign-on reads it.
 */
@Component
final class UsrsecCredentialCheck implements CredentialCheck {

    private final UserStore users;

    UsrsecCredentialCheck(UserStore users) {
        this.users = users;
    }

    @Override
    public Verification verify(UserId id, String password) {
        return switch (users.find(id)) {
            case UserStore.Found found -> matches(found.user(), password)
                    ? new Verification.Verified(SignedOnUser.of(found.user()))
                    : new Verification.WrongPassword();
            case UserStore.Missing missing -> new Verification.UnknownUser();
            case UserStore.Failed failed -> new Verification.Unavailable();
        };
    }

    @LegacyDefect(id = "USRSEC-plaintext-passwords",
            goldens = {"user-add-success", "user-update-fetch"},
            behavior = "USRSEC stores passwords in plaintext; sign-on compares them directly and user admin "
                    + "writes and displays them",
            fix = "DelegatingPasswordEncoder (bcrypt, {noop} for unmigrated rows) here, hash on write in "
                    + "UserStore, stop sending PASSWD from the update and delete screens; re-record the file diffs")
    private static boolean matches(UserRecord user, String password) {
        return user.password().equals(password);
    }
}
