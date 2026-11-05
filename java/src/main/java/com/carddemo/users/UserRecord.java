package com.carddemo.users;

/**
 * The data of CSUSR01Y SEC-USER-DATA, values without trailing blanks. The password is plaintext, as
 * USRSEC stores it.
 */
public record UserRecord(UserId id, String firstName, String lastName, String password, UserTypeCode type) {

    public static final int NAME_LENGTH = 20;
    public static final int PASSWORD_LENGTH = 8;

    public UserRecord {
        if (firstName.length() > NAME_LENGTH || lastName.length() > NAME_LENGTH || password.length() > PASSWORD_LENGTH) {
            throw new IllegalArgumentException("user record wider than CSUSR01Y: " + id);
        }
    }
}
