package com.carddemo.online;

import com.carddemo.users.Credentials;

import java.util.Arrays;
import java.util.Optional;

/** SEC-USR-TYPE. */
public enum UserType {
    ADMIN("A"), USER("U");

    public final String code;

    UserType(String code) {
        this.code = code;
    }

    /** COSGN00C sends every stored type other than 'A' to the main menu. */
    public static UserType stored(String code) {
        return ADMIN.code.equals(code) ? ADMIN : USER;
    }

    /** What an admin types on the add and update screens: upper-cased, and only A or U. */
    public static Optional<UserType> typed(String input) {
        String code = Credentials.normalize(input);
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
    }

    public Program menu() {
        return this == ADMIN ? Program.COADM01C : Program.COMEN01C;
    }
}
