package com.carddemo.signon;

import com.carddemo.identity.SignedOnUser;
import com.carddemo.online.ProgramName;

/**
 * Where a signed-on user lands: COSGN00C's {@code IF CDEMO-USRTYP-ADMIN XCTL COADM01C ELSE XCTL
 * COMEN01C}. The one post-login routing rule.
 */
public final class HomeScreen {

    public static final ProgramName ADMIN_MENU = ProgramName.of("COADM01C");
    public static final ProgramName MAIN_MENU = ProgramName.of("COMEN01C");

    private HomeScreen() {}

    public static ProgramName of(SignedOnUser user) {
        return user.type().admin() ? ADMIN_MENU : MAIN_MENU;
    }
}
