# Legacy defects

The Java port reproduces these CardDemo defects so that it behaves as the COBOL programs do. Each one lives in a single annotated method. Fixing a defect means changing that method and re-recording the goldens that pin it.

`LegacyDefectRegisterTest` writes this file from the `@LegacyDefect` annotations during `mvn verify`. Edit the annotations, not this file.

## `COACTUPC-any-other-key-is-enter`

A key 0000-MAIN does not accept at this point, such as PF12 before an account is fetched or PF5 with no change waiting for confirmation, is taken as ENTER and processes the screen.

- Lives in [`AccountUpdateScreen.anyOtherKeyIsEnter`](../src/main/java/com/carddemo/account/AccountUpdateScreen.java).
- Comes from the program logic.
- Pinned by `account-update-pf12-before-fetch`, `account-update-pf5-before-confirmation`.
- Fix: redisplay the screen with the invalid key message, as the menus do.

## `COACTUPC-blank-account-message`

A blank or '*' account sets 'Account number not provided', then 1200 overwrites it with 'No input received'.

- Lives in [`AccountUpdateScreen.Task.noSearchCriteriaReceived`](../src/main/java/com/carddemo/account/AccountUpdateScreen.java).
- Comes from the program logic.
- Pinned by `account-update-blank-account`, `account-update-pf12-before-fetch`.
- Fix: keep 'Account number not provided'.

## `COACTUPC-blank-never-unchanged`

1100 turns a blank field into LOW-VALUES and 1205 compares that with the stored SPACES, so a record with any blank field, such as an empty group id, counts as changed on every ENTER.

- Lives in [`FetchedDetails.blankMatches`](../src/main/java/com/carddemo/account/FetchedDetails.java).
- Comes from the program logic.
- Pinned by `account-update-blank-group-always-changed`.
- Fix: compare a blank field as SPACES.

## `COACTUPC-cursor-on-protected-account`

With the details open for changes and nothing to flag, the cursor goes to the account number, which is protected by then, through the WHEN OTHER of 3300's cursor EVALUATE.

- Lives in [`AccountUpdateScreen.Task.cursorOtherwise`](../src/main/java/com/carddemo/account/AccountUpdateScreen.java).
- Comes from the program logic.
- Pinned by `account-update-fetch`, `account-update-pf12-discards-edits`, `account-update-confirm-pf12-cancels`.
- Fix: put the cursor on the account status while the details are open for changes.

## `COACTUPC-customer-lock-failure-committed`

When the account locks but the customer READ UPDATE fails, 2000-DECIDE-ACTION has no WHEN for 'Could not lock customer record for update', so the screen says 'Changes committed to database' although nothing was written.

- Lives in [`AccountUpdateScreen.Task.customerNotLocked`](../src/main/java/com/carddemo/account/AccountUpdateScreen.java).
- Comes from the program logic.
- No golden pins it yet. It is read from the source.
- Fix: treat it as a lock error, as for the account.

## `COACTUPC-customer-without-account`

An account missing from ACCTDAT, or whose READ fails, still reads its customer and opens the details for changes, because 9000-READ-ACCT tests a message 88 that nothing sets; COACTUPC also shows and compares account fields from a record it never read, which this port leaves blank.

- Lives in [`AccountUpdateScreen.Task.readAccountAndCustomer`](../src/main/java/com/carddemo/account/AccountUpdateScreen.java).
- Comes from the program logic.
- No golden pins it yet. It is read from the source.
- Fix: stop after the failed account READ.

## `COACTUPC-edits-unchanged-fields`

Once anything changed, every field is edited, changed or not, so stored data that breaks an edit blocks every save; seed account 1 fails on its FICO score 274, its NC zip 12546 and its phone 2 area code 373.

- Lives in [`AccountEdits.run`](../src/main/java/com/carddemo/account/AccountEdits.java).
- Comes from the program logic.
- Pinned by `account-update-seed-customer-unchanged-fails`.
- Fix: edit only the fields that changed, or clean the seed data.

## `COACTUPC-group-id-into-zip`

ACCT-UPDATE-RECORD has no ACCT-ADDR-ZIP, so the group id is written over the zip, and ACCT-GROUP-ID and the FILLER get the update record's FILLER, which nothing initializes (LOW-VALUES under the harness); a blank group id writes LOW-VALUES.

- Lives in [`AccountUpdateScreen.Task.groupIdIntoZip`](../src/main/java/com/carddemo/account/AccountUpdateScreen.java).
- Comes from the program logic.
- Pinned by `account-update-save`, `account-update-save-group-and-customer`.
- Fix: lay ACCT-UPDATE-RECORD out like CVACT01Y and keep the stored zip.

## `COACTUPC-ssn-parts-skipped`

A missing END-IF in 1265-EDIT-US-SSN nests the edits of the 4th-5th and last four SSN characters under the first part's IF, so they are skipped when the first three are blank or not numeric.

- Lives in [`AccountEdits.Run.editsRestOfSsn`](../src/main/java/com/carddemo/account/AccountEdits.java).
- Comes from the program logic.
- No golden pins it yet. It is read from the source.
- Fix: close the IF after the 000/666/9xx check of the first part.

## `COACTVWC-any-key-looks-up`

A key other than ENTER and PF3 is taken as ENTER and looks the typed account up.

- Lives in [`AccountViewScreen.anyKeyLooksUp`](../src/main/java/com/carddemo/account/AccountViewScreen.java).
- Comes from the program logic.
- Pinned by `account-view-invalid-key-acts-as-enter`.
- Fix: redisplay the screen with the invalid key message, as the menus do.

## `COACTVWC-blank-account-message`

A blank or '*' account sets 'Account number not provided', then the cross-field edit overwrites it with 'No input received'.

- Lives in [`AccountViewScreen.blankAccountMessage`](../src/main/java/com/carddemo/account/AccountViewScreen.java).
- Comes from the program logic.
- Pinned by `account-view-blank-account`, `account-view-star-account`.
- Fix: keep 'Account number not provided'.

## `COACTVWC-customer-without-account`

An account missing from ACCTDAT, or whose READ fails, still reads and shows its customer, because 9000-READ-ACCT tests a message 88 that nothing sets; COACTVWC also shows account fields from a record it never read, which this port leaves blank.

- Lives in [`AccountViewScreen.readAccountAndCustomer`](../src/main/java/com/carddemo/account/AccountViewScreen.java).
- Comes from the program logic.
- Pinned by `account-view-not-in-acctdat`.
- Fix: stop after the failed account READ.

## `COACTVWC-filter-message-double-space`

The message for an account id that is not 11 digits has two spaces in 'must  be'.

- Lives in [`AccountViewScreen.notAnAccountNumber`](../src/main/java/com/carddemo/account/AccountViewScreen.java).
- Comes from the program logic.
- Pinned by `account-view-non-numeric`, `account-view-zero-account`, `account-view-short-account`.
- Fix: one space.

## `COACTVWC-info-line-never-informs`

The info line keeps asking for an account id after a lookup that found it; 9000-READ-ACCT clears WS-INFO-MSG and nothing sets 'Displaying details of given Account'.

- Lives in [`AccountViewScreen.infoLine`](../src/main/java/com/carddemo/account/AccountViewScreen.java).
- Comes from the program logic.
- Pinned by `account-view-success`.
- Fix: show 'Displaying details of given Account' when the account was found.

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
