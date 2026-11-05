# Legacy defects

The Java port reproduces these CardDemo defects so that it behaves as the COBOL programs do. Each one lives in a single annotated method. Fixing a defect means changing that method and re-recording the goldens that pin it.

`LegacyDefectRegisterTest` writes this file from the `@LegacyDefect` annotations during `mvn verify`. Edit the annotations, not this file.

## `COSGN00C-untouched-password`

PASSWD has BMS INITIAL '________' and FSET, so an untouched field transmits the underscores; the program does not treat them as blank and answers Wrong Password.

- Lives in [`SignonScreen.passwordBlank`](../src/main/java/com/carddemo/signon/SignonScreen.java).
- Comes from BMS or the 3270, and the program does not guard against it.
- Pinned by `signon-untouched-password`.
- Fix: also treat the all-underscore initial as blank here, or drop INITIAL in COSGN00.bms.

## `COSGN00C-user-enumeration`

Distinct messages tell an attacker whether a user id exists.

- Lives in [`SignonScreen.rejection`](../src/main/java/com/carddemo/signon/SignonScreen.java).
- Comes from the program logic.
- Pinned by `signon-unknown-user`, `signon-wrong-password`.
- Fix: one message and one cursor position for UnknownUser and WrongPassword.

## `COUSR00C-endbr-after-notfnd`

A search id after the last user, which includes every lowercase id, makes STARTBR NOTFND; the program still runs ENDBR without RESP and the task abends AEIP.

- Lives in [`UserListScreen.Task.endbrWithoutBrowse`](../src/main/java/com/carddemo/useradmin/UserListScreen.java).
- Comes from the program logic.
- No golden pins it yet. It is read from the source.
- Fix: run ENDBR only after a successful STARTBR and show 'You are at the top of the page...'.

## `COUSR00C-selection-message-lost`

READNEXT's ENDFILE branch overwrites WS-MESSAGE, so 'Invalid selection' is lost whenever the page reaches the last user; the mid-list golden keeps it.

- Lives in [`UserListScreen.Task.reachedBottom`](../src/main/java/com/carddemo/useradmin/UserListScreen.java).
- Comes from the program logic.
- Pinned by `user-list-select-invalid`, `user-list-select-invalid-mid-list`.
- Fix: set the bottom-of-page message only when WS-MESSAGE is blank.

## `COUSR01C-id-as-typed`

The user id is stored as typed, but COSGN00C uppercases the id it looks up, so a user added in lowercase can never sign on.

- Lives in [`UserAddScreen.newUser`](../src/main/java/com/carddemo/useradmin/UserAddScreen.java).
- Comes from the program logic.
- Pinned by `user-add-lowercase-id-kept`.
- Fix: uppercase the id here, as COSGN00C does.

## `COUSR02C-missing-user-on-save`

A save of an id that is not on file, or whose READ UPDATE fails, still runs REWRITE, and its INVREQ replaces 'User ID NOT found...' with 'Unable to Update User...'.

- Lives in [`UserUpdateScreen.rewriteWithoutLock`](../src/main/java/com/carddemo/useradmin/UserUpdateScreen.java).
- Comes from the program logic.
- No golden pins it yet. It is read from the source.
- Fix: stop after the failed READ and keep its message.

## `COUSR02C-password-shown`

A fetch sends the stored plaintext password to the terminal; PASSWD is dark, but the password is in the data stream.

- Lives in [`UserUpdateScreen.showUser`](../src/main/java/com/carddemo/useradmin/UserUpdateScreen.java).
- Comes from the program logic.
- Pinned by `user-update-fetch`, `user-update-from-list`.
- Fix: leave PASSWD low-value on fetch and keep the stored password unless a new one is typed.

## `COUSR03C-delete-without-fetch`

PF5 deletes whatever id is typed, so a user is deleted without ever being shown.

- Lives in [`UserDeleteScreen.deleteUserInfo`](../src/main/java/com/carddemo/useradmin/UserDeleteScreen.java).
- Comes from the program logic.
- Pinned by `user-delete-pf5-without-fetch`.
- Fix: keep the fetched id in State and delete only that id.

## `COUSR03C-missing-user-on-delete`

PF5 on an id that is not on file, or whose READ UPDATE fails, still runs DELETE, and its INVREQ replaces 'User ID NOT found...' with 'Unable to Update User...'.

- Lives in [`UserDeleteScreen.deleteWithoutLock`](../src/main/java/com/carddemo/useradmin/UserDeleteScreen.java).
- Comes from the program logic.
- No golden pins it yet. It is read from the source.
- Fix: stop after the failed READ and keep its message.

## `USRSEC-any-user-type`

COUSR01C and COUSR02C store any non-blank character as the user type.

- Lives in [`UserTypeCode.asTyped`](../src/main/java/com/carddemo/users/UserTypeCode.java).
- Comes from the program logic.
- Pinned by `user-add-type-not-validated`.
- Fix: accept only A or U here and let both screens show a type error.

## `USRSEC-plaintext-passwords`

USRSEC stores passwords in plaintext; sign-on compares them directly and user admin writes and displays them.

- Lives in [`UsrsecCredentialCheck.matches`](../src/main/java/com/carddemo/security/UsrsecCredentialCheck.java).
- Comes from the program logic.
- Pinned by `user-add-success`, `user-update-fetch`.
- Fix: DelegatingPasswordEncoder (bcrypt, {noop} for unmigrated rows) here and hash on write in UserStore, with COUSR02C-password-shown fixed first; re-record the file diffs.
