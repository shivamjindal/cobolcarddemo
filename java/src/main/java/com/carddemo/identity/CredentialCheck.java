package com.carddemo.identity;

import com.carddemo.users.UserId;

/** Used by the sign-on screen. The screen, not the check, decides what each result says. */
public interface CredentialCheck {

    /** Both arguments are already upper-cased, as COSGN00C does. */
    Verification verify(UserId id, String password);
}
