package com.carddemo.users;

import java.io.Serializable;

import com.carddemo.LegacyDefect;

/**
 * SEC-USR-TYPE PIC X(01) as stored. The programs only test {@code = 'A'} (CDEMO-USRTYP-ADMIN), so any
 * other character behaves as a regular user.
 */
public record UserTypeCode(char code) implements Serializable {

    public boolean admin() {
        return code == 'A';
    }

    static UserTypeCode stored(String column) {
        return new UserTypeCode(column.isEmpty() ? ' ' : column.charAt(0));
    }

    @LegacyDefect(id = "USRSEC-any-user-type",
            goldens = {"user-add-type-not-validated"},
            behavior = "COUSR01C and COUSR02C store any non-blank character as the user type",
            fix = "accept only A or U here and let both screens show a type error")
    public static UserTypeCode asTyped(String typed) {
        return new UserTypeCode(typed.charAt(0));
    }

    @Override
    public String toString() {
        return String.valueOf(code);
    }
}
