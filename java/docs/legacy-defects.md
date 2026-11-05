# Legacy defects

The Java port reproduces these CardDemo defects so that it behaves as the COBOL programs do. Each one lives in a single annotated method. Fixing a defect means changing that method and re-recording the goldens that pin it.

`LegacyDefectRegisterTest` writes this file from the `@LegacyDefect` annotations during `mvn verify`. Edit the annotations, not this file.

## `COCRDLIC-bad-filter-keeps-page`

A non-numeric filter skips the browse, so the previous page stays on screen and every selection field stays protected.

- Lives in [`CardListScreen.Task.filtersAcceptable`](../src/main/java/com/carddemo/card/CardListScreen.java).
- Comes from the program logic.
- Pinned by `card-list-account-not-numeric`, `card-list-card-not-numeric`.
- Fix: drop the typed filter and leave the selection fields unprotected.

## `COCRDLIC-invalid-key-acts-as-enter`

A key other than ENTER, PF3, PF7 or PF8 is forced to ENTER and the typed filter is applied.

- Lives in [`CardListScreen.invalidKeyActsAsEnter`](../src/main/java/com/carddemo/card/CardListScreen.java).
- Comes from the program logic.
- Pinned by `card-list-invalid-key-acts-as-enter`.
- Fix: reject the key and leave the page as it is.

## `COCRDLIC-no-user-filter`

Every signed-on user sees every card; nothing limits the browse to an account on the commarea.

- Lives in [`CardListScreen.Task.included`](../src/main/java/com/carddemo/card/CardListScreen.java).
- Comes from the program logic.
- Pinned by `card-list-first-page`.
- Fix: browse CARDAIX for the signed-on user's account.

## `COCRDLIC-page-up-from-page-1`

PF7 subtracts from the page number before the message is chosen, so paging up onto page 1 also says NO PREVIOUS PAGES TO DISPLAY and drops the info line.

- Lives in [`CardListScreen.Task.infoLine`](../src/main/java/com/carddemo/card/CardListScreen.java).
- Comes from the program logic.
- Pinned by `card-list-page-up-from-page-2`.
- Fix: choose the message from the page number before subtracting.

## `COCRDLIC-row1-no-cursor`

An invalid action on row 1 colors the row but never sets its cursor length, unlike rows 2-7, so the cursor stays on the map's IC field ACCTSID.

- Lives in [`CardListScreen.Task.markSelection`](../src/main/java/com/carddemo/card/CardListScreen.java).
- Comes from the program logic.
- Pinned by `card-list-invalid-action-row1`.
- Fix: set CRDSEL1L to -1 the same way as the other rows.

## `COCRDSLC-account-not-checked`

The card is read by card number only, so a mismatched account still shows that card.

- Lives in [`CardDetailScreen.readByCardNumber`](../src/main/java/com/carddemo/card/CardDetailScreen.java).
- Comes from the program logic.
- Pinned by `card-detail-account-not-checked`.
- Fix: read the card and reject it when CARD-ACCT-ID is not the account that was asked for.

## `COCRDSLC-both-blank-hides-account-message`

The account edit's message is replaced when both fields are blank, and a bad card does not replace a message the account edit already set.

- Lives in [`CardDetailScreen.search`](../src/main/java/com/carddemo/card/CardDetailScreen.java).
- Comes from the program logic.
- Pinned by `card-detail-both-blank`, `card-detail-both-invalid`.
- Fix: keep the first failing field's message only when it is the one that should win.

## `COCRDSLC-invalid-key-acts-as-enter`

A key other than ENTER or PF3 is forced to ENTER and the search runs.

- Lives in [`CardDetailScreen.invalidKeyActsAsEnter`](../src/main/java/com/carddemo/card/CardDetailScreen.java).
- Comes from the program logic.
- Pinned by `card-detail-invalid-key-acts-as-enter`.
- Fix: reject the key and leave the screen as it is.

## `COCRDSLC-invalid-key-erased`

A field that fails IS NUMERIC is stored as zero and 1200-SETUP-SCREEN-VARS sends LOW-VALUES, so the typed value disappears.

- Lives in [`CardDetailScreen.paintKey`](../src/main/java/com/carddemo/card/CardDetailScreen.java).
- Comes from the program logic.
- Pinned by `card-detail-account-not-numeric`, `card-detail-account-short`, `card-detail-card-not-numeric`, `card-detail-both-invalid`.
- Fix: echo the typed value on the field that failed.

## `COCRDSLC-short-field-not-numeric`

IS NUMERIC fails when trailing blanks pad a short account out to 11 characters, so '1' is rejected.

- Lives in [`FixedDigits.read`](../src/main/java/com/carddemo/card/FixedDigits.java).
- Comes from the program logic.
- Pinned by `card-detail-account-short`.
- Fix: accept a numeric account that is shorter than the field.

## `COCRDUPC-both-blank-hides-account-message`

'No input received' replaces 'Account number not provided' when both search fields are blank.

- Lives in [`CardUpdateScreen.Task.editKeys`](../src/main/java/com/carddemo/card/CardUpdateScreen.java).
- Comes from the program logic.
- Pinned by `card-update-search-both-blank`.
- Fix: keep the account message when both fields are blank.

## `COCRDUPC-bright-unprotects`

DFHBMBRY is moved into INFOMSGA, and into the F5 line on confirm, which clears the protected bit.

- Lives in [`CardUpdateScreen.Task.bright`](../src/main/java/com/carddemo/card/CardUpdateScreen.java).
- Comes from the program logic.
- Pinned by `card-update-initial-display`, `card-update-fetch`, `card-update-valid-change-asks-confirm`.
- Fix: set the intensity and leave the field protected.

## `COCRDUPC-compare-uppercased`

Old and new details are compared after UPPER-CASE, so retyping the name or status in lower case is not a change and is never edited.

- Lives in [`CardUpdateScreen.Task.sameIgnoringCase`](../src/main/java/com/carddemo/card/CardUpdateScreen.java).
- Comes from the program logic.
- Pinned by `card-update-lowercase-name-is-no-change`, `card-update-lowercase-status-same-is-no-change`.
- Fix: compare the name and status in the case the user typed.

## `COCRDUPC-confirm-cursor-on-protected-account`

Once the edits pass, the cursor falls through to ACCTSID, which confirmation has just protected.

- Lives in [`CardUpdateScreen.Task.cursor`](../src/main/java/com/carddemo/card/CardUpdateScreen.java).
- Comes from the program logic.
- Pinned by `card-update-valid-change-asks-confirm`, `card-update-save`.
- Fix: leave the cursor on the confirm prompt.

## `COCRDUPC-cvv-blanked-on-save`

CCUP-NEW-CVV-CD is never filled, so the REWRITE stores spaces in CARD-CVV-CD.

- Lives in [`CardUpdateScreen.blankedCvv`](../src/main/java/com/carddemo/card/CardUpdateScreen.java).
- Comes from the program logic.
- Pinned by `card-update-save`, `card-update-from-list-save-returns-to-list`.
- Fix: copy the fetched CVV into the record that is rewritten.

## `COCRDUPC-fetch-account-not-checked`

The card is read by card number only, so a mismatched account still fetches that card.

- Lives in [`CardUpdateScreen.Task.readByCardNumber`](../src/main/java/com/carddemo/card/CardUpdateScreen.java).
- Comes from the program logic.
- Pinned by `card-update-fetch-account-not-checked`.
- Fix: reject the card when CARD-ACCT-ID is not the account that was asked for.

## `COCRDUPC-filler-low-values`

CARD-UPDATE-RECORD's FILLER is never initialized, and INITIALIZE does not touch FILLER, so the REWRITE stores LOW-VALUES.

- Lives in [`CardUpdateScreen.uninitializedFiller`](../src/main/java/com/carddemo/card/CardUpdateScreen.java).
- Comes from the program logic.
- No golden pins it yet. It is read from the source.
- Fix: rewrite the filler the READ UPDATE returned.

## `COCRDUPC-invalid-key-acts-as-enter`

A key other than PF3, or PF5 and PF12 once they apply, is forced to ENTER.

- Lives in [`CardUpdateScreen.invalidKeyActsAsEnter`](../src/main/java/com/carddemo/card/CardUpdateScreen.java).
- Comes from the program logic.
- No golden pins it yet. It is read from the source.
- Fix: reject the key and leave the screen as it is.

## `COCRDUPC-name-uppercased-on-fetch`

9000-READ-DATA upper-cases the embossed name, and a save writes that name back when it was not retyped.

- Lives in [`CardUpdateScreen.fetchedName`](../src/main/java/com/carddemo/card/CardUpdateScreen.java).
- Comes from the program logic.
- Pinned by `card-update-fetch`, `card-update-from-list-save-returns-to-list`.
- Fix: keep the stored name's case on fetch.

## `COCRDUPC-pf12-before-fetch-acts-as-enter`

PF12 is a cancel only after a card has been fetched; before that it is ENTER and fetches.

- Lives in [`CardUpdateScreen.Task.cancel`](../src/main/java/com/carddemo/card/CardUpdateScreen.java).
- Comes from the program logic.
- Pinned by `card-update-pf12-before-fetch-acts-as-enter`.
- Fix: ignore PF12 until a card is on screen.

## `COCRDUPC-pf12-keeps-edit-error`

PF12 re-reads the card after the edit has stored its message, and the re-read leaves that message up.

- Lives in [`CardUpdateScreen.Task.fetch`](../src/main/java/com/carddemo/card/CardUpdateScreen.java).
- Comes from the program logic.
- Pinned by `card-update-pf12-with-bad-edits`.
- Fix: clear the edit message when the original card is read back.

## `COCRDUPC-pf5-before-confirm-acts-as-enter`

PF5 is a real save only while changes are waiting for confirmation; before that it is ENTER.

- Lives in [`CardUpdateScreen.Task.saveKey`](../src/main/java/com/carddemo/card/CardUpdateScreen.java).
- Comes from the program logic.
- Pinned by `card-update-pf5-before-confirm-acts-as-enter`.
- Fix: ignore PF5 until the confirm screen is up.

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
