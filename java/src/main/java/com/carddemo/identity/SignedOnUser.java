package com.carddemo.identity;

import java.io.Serializable;

import org.springframework.security.core.AuthenticatedPrincipal;

import com.carddemo.users.UserId;
import com.carddemo.users.UserRecord;
import com.carddemo.users.UserTypeCode;

/**
 * The terminal's identity: CDEMO-USER-ID and CDEMO-USER-TYPE. The only principal type. Today the
 * sign-on screen makes it from USRSEC; an OIDC login can make the same type later.
 */
public record SignedOnUser(UserId id, UserTypeCode type, String firstName, String lastName)
        implements AuthenticatedPrincipal, Serializable {

    public static SignedOnUser of(UserRecord user) {
        return new SignedOnUser(user.id(), user.type(), user.firstName(), user.lastName());
    }

    /** The one mapping from SEC-USR-TYPE to a role: 'A' is admin, anything else a user. */
    public Role role() {
        return type.admin() ? Role.ADMIN : Role.USER;
    }

    @Override
    public String getName() {
        return id.value();
    }
}
