package com.carddemo.users;

import com.carddemo.online.UserType;

/** A USRSEC record (CSUSR01Y). The password is a bcrypt hash; see {@link Credentials}. */
public record SecUser(String id, String firstName, String lastName, String passwordHash, UserType type) {
}
