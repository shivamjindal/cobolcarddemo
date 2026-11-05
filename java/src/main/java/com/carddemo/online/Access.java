package com.carddemo.online;

import java.util.Optional;

import com.carddemo.identity.Role;
import com.carddemo.identity.SignedOnUser;

/** Who may be on a screen. The filter chain and the dispatcher both derive their checks from it. */
public enum Access {
    ANYONE, SIGNED_ON, ADMIN;

    public boolean permits(Optional<SignedOnUser> user) {
        return switch (this) {
            case ANYONE -> true;
            case SIGNED_ON -> user.isPresent();
            case ADMIN -> user.map(u -> u.role() == Role.ADMIN).orElse(false);
        };
    }
}
