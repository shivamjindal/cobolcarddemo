package com.carddemo.users;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;

/**
 * The case rule: user IDs, passwords and user types are upper-cased wherever they are typed, the way
 * COSGN00C upper-cases sign-on input, so a user added in lower case can still sign on.
 */
public final class Credentials {

    private static final PasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private Credentials() {
    }

    public static String normalize(String typed) {
        return typed.toUpperCase(Locale.ROOT);
    }

    public static String hash(String typedPassword) {
        return BCRYPT.encode(normalize(typedPassword));
    }

    public static boolean matches(String typedPassword, String hash) {
        return BCRYPT.matches(normalize(typedPassword), hash);
    }
}
