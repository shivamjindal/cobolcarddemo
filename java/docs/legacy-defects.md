# Legacy defects

The Java port reproduces these CardDemo defects so that it behaves as the COBOL programs do. Each one lives in a single annotated method. Fixing a defect means changing that method and re-recording the goldens that pin it.

`LegacyDefectRegisterTest` writes this file from the `@LegacyDefect` annotations during `mvn verify`. Edit the annotations, not this file.

## `CBACT01C-reissue-keeps-null-padding`

COBDATFT writes eight bytes of YYYYMMDD into a twenty-byte area that starts as low-values, and the MOVE into OUT-ACCT-REISSUE-DATE keeps the two nulls after them.

- Lives in [`Cbact01c.reissueDate`](../src/main/java/com/carddemo/batch/programs/Cbact01c.java).
- Comes from the program logic.
- Pinned by `acct-extract-two-accounts`.
- Fix: space-fill OUT-ACCT-REISSUE-DATE and move only the eight converted characters.

## `CBACT01C-zero-cycle-debit-becomes-2525`

1300-POPUL-ACCT-RECORD moves 2525.00 into the packed cycle debit only when the account debit is zero, so a later non-zero debit keeps the previous record's value.

- Lives in [`Cbact01c.carryCycleDebit`](../src/main/java/com/carddemo/batch/programs/Cbact01c.java).
- Comes from the program logic.
- Pinned by `acct-extract-two-accounts`.
- Fix: move ACCT-CURR-CYC-DEBIT into OUT-ACCT-CURR-CYC-DEBIT on every account.

## `CBACT03C-displays-each-record-twice`

1000-XREFFILE-GET-NEXT DISPLAYs each cross-reference record it reads and the main loop DISPLAYs it again, so SYSOUT lists every record twice.

- Lives in [`Cbact03c.displayAsRead`](../src/main/java/com/carddemo/batch/programs/Cbact03c.java).
- Comes from the program logic.
- Pinned by `xref-list-full-seed`.
- Fix: drop the DISPLAY in 1000-XREFFILE-GET-NEXT, as CBACT02C comments out its own.

## `CBACT04C-skips-last-account`

PERFORM UNTIL tests END-OF-FILE before the body, so the ELSE that rewrites the last account is dead. That account's interest is written and its balance and cycle totals are left as they were.

- Lives in [`Cbact04c.charge`](../src/main/java/com/carddemo/batch/programs/Cbact04c.java).
- Comes from the program logic.
- Pinned by `interest-default-group`, `interest-group-rate-truncates`, `interest-negative-balance`, `interest-zero-rate-no-transaction`, `interest-unknown-group-uses-default`, `interest-two-accounts`.
- Fix: rewrite the last account after the category file is exhausted.

## `CBCUS01C-displays-each-record-twice`

1000-CUSTFILE-GET-NEXT DISPLAYs each customer record it reads and the main loop DISPLAYs it again, so SYSOUT lists every record twice.

- Lives in [`Cbcus01c.displayAsRead`](../src/main/java/com/carddemo/batch/programs/Cbcus01c.java).
- Comes from the program logic.
- Pinned by `customer-list-full-seed`.
- Fix: drop the DISPLAY in 1000-CUSTFILE-GET-NEXT, as CBACT02C comments out its own.

## `CBSTM03A-city-cut-at-first-space`

The address line STRINGs city, state, country and zip DELIMITED BY one space, so a city with an embedded space such as West Bernita is printed as its first word.

- Lives in [`Cbstm03a.firstWord`](../src/main/java/com/carddemo/batch/programs/Cbstm03a.java).
- Comes from the program logic.
- Pinned by `statement-two-accounts`.
- Fix: move the city, state, country and zip fields whole, the way address lines 1 and 2 are moved.

## `CBSTM03A-eleventh-transaction-overlaps`

The transaction table holds ten rows per card and does not check the subscript. The eleventh row is written into the next card's slot, and storing that card overwrites it, so the statement prints the next card number as the transaction id, a shifted description and a zero amount, and the total leaves out the real eleventh amount.

- Lives in [`Cbstm03a.TransactionTable.store`](../src/main/java/com/carddemo/batch/programs/Cbstm03a.java).
- Comes from the program logic.
- No golden pins it yet. It is read from the source.
- Fix: keep every transaction for the card.

## `CBTRN02C-expired-overwrites-overlimit`

The expiration check is not an ELSE of the limit check, so a transaction that is both over the credit limit and past the account expiration date is rejected as 103 instead of 102.

- Lives in [`Cbtrn02c.rejectIfExpired`](../src/main/java/com/carddemo/batch/programs/Cbtrn02c.java).
- Comes from the program logic.
- Pinned by `posting-overlimit-and-expired-reports-103`.
- Fix: keep 102 when the limit already failed.

## `CBTRN03C-drops-last-account-total`

1120-WRITE-ACCOUNT-TOTALS runs only when the card changes, so the last card's account total is never written.

- Lives in [`Cbtrn03c.endOfFile`](../src/main/java/com/carddemo/batch/programs/Cbtrn03c.java).
- Comes from the program logic.
- Pinned by `tranrept-two-cards`.
- Fix: write the account total for the current card before the page and grand totals at end of file.

## `CBTRN03C-eof-adds-stale-amount`

At end of file TRAN-RECORD still holds the last transaction, and that amount is added to the page and account totals a second time.

- Lives in [`Cbtrn03c.addStaleAmount`](../src/main/java/com/carddemo/batch/programs/Cbtrn03c.java).
- Comes from the program logic.
- Pinned by `tranrept-two-cards`.
- Fix: do not add TRAN-AMT on the end-of-file branch.

## `CBTRN03C-next-sentence-ends-run`

NEXT SENTENCE on a process date outside DATEPARM leaves the PERFORM, so the run ends RC=0 without reading later records or writing totals.

- Lives in [`Cbtrn03c.stopAtOutOfRangeDate`](../src/main/java/com/carddemo/batch/programs/Cbtrn03c.java).
- Comes from the program logic.
- Pinned by `tranrept-out-of-range-stops-run`.
- Fix: skip that record and keep reading.

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

## `COBIL00C-pays-without-xref`

CXACAIX NOTFND is ignored, the payment is written with a LOW-VALUES card number, and the success message replaces the account-not-found error.

- Lives in [`BillPayScreen.pay`](../src/main/java/com/carddemo/billpay/BillPayScreen.java).
- Comes from the program logic.
- Pinned by `bill-pay-no-xref-still-pays`.
- Fix: stop when the account has no card cross-reference and leave the balance unchanged.

## `COBIL00C-short-account-unpadded`

MOVE of a short account id into ACCT-ID PIC 9(11) leaves it unpadded, so 1 is not account 00000000001.

- Lives in [`BillPayScreen.accountId`](../src/main/java/com/carddemo/billpay/BillPayScreen.java).
- Comes from the program logic.
- Pinned by `bill-pay-short-account-not-found`.
- Fix: zero-pad a short numeric account id to 11 digits before the read.

## `COCRDLIC-bad-filter-keeps-page`

A non-numeric filter skips the browse, so the previous page stays on screen and every selection field stays protected.

- Lives in [`CardListScreen.Task.filtersAcceptable`](../src/main/java/com/carddemo/card/CardListScreen.java).
- Comes from the program logic.
- Pinned by `card-list-account-not-numeric`, `card-list-card-not-numeric`.
- Fix: drop the typed filter and leave the selection fields unprotected.

## `COCRDLIC-endbr-without-browse`

A STARTBR that does not start a browse still runs ENDBR with no RESP, so the task abends AEIP and the file-error message is never sent.

- Lives in [`CardListScreen.Task.close`](../src/main/java/com/carddemo/card/CardListScreen.java).
- Comes from the program logic.
- No golden pins it yet. It is read from the source.
- Fix: run ENDBR only after a successful STARTBR and show the file-error message.

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

## `CORPT00C-non-numeric-date-part-is-zero`

NUMVAL-C of a date part with letters answers 0 under GnuCOBOL, so 20X6 becomes year 0000, which CSUTLDTC flags as message 2513 and the job is submitted.

- Lives in [`ReportSubmitScreen.numericPart`](../src/main/java/com/carddemo/report/ReportSubmitScreen.java).
- Comes from the program logic.
- No golden pins it yet. It is read from the source.
- Fix: reject a date part that fails TEST-NUMVAL-C before converting it.

## `CORPT00C-pre-1582-accepted`

CSUTLDTC message 2513, a date before 15 October 1582, is not treated as a date error.

- Lives in [`ReportSubmitScreen.unacceptable`](../src/main/java/com/carddemo/report/ReportSubmitScreen.java).
- Comes from the program logic.
- Pinned by `report-submit-custom-pre-1582-accepted`.
- Fix: reject every severity other than 0000, including message 2513.

## `CORPT00C-reversed-range-accepted`

A custom end date before the start date is submitted; the two dates are never compared.

- Lives in [`ReportSubmitScreen.submitCustom`](../src/main/java/com/carddemo/report/ReportSubmitScreen.java).
- Comes from the program logic.
- Pinned by `report-submit-custom-reversed-range`.
- Fix: reject the range when the end date is before the start date.

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

## `COTRN00C-filter-past-end-abends`

A Tran ID above every key makes STARTBR NOTFND; ENDBR then runs with no RESP on the browse that never started and the task abends AEIP.

- Lives in [`TransactionListScreen.Task.endbrWithoutBrowse`](../src/main/java/com/carddemo/transaction/TransactionListScreen.java).
- Comes from the program logic.
- Pinned by `transaction-list-filter-past-end`.
- Fix: run ENDBR only after a successful STARTBR and show 'You are at the top of the page...'.

## `COTRN00C-invalid-selection-resets-page`

A selection other than S sets the message and then ENTER still re-browses from the start, so the page returns to 1 and the typed code stays in the row.

- Lives in [`TransactionListScreen.Task.invalidSelection`](../src/main/java/com/carddemo/transaction/TransactionListScreen.java).
- Comes from the program logic.
- Pinned by `transaction-list-select-invalid`.
- Fix: redisplay the page that was on screen and clear the bad code.

## `COTRN00C-pf5-skips-first`

The forward browse skips one record unless EIBAID is ENTER, PF7, or PF3. PF5 from the view arrives with that aid, so page 1 starts at the second transaction.

- Lives in [`TransactionListScreen.Task.skipsFirstRecord`](../src/main/java/com/carddemo/transaction/TransactionListScreen.java).
- Comes from the program logic.
- Pinned by `transaction-view-pf5-list`.
- Fix: skip a record only for PF8.

## `COTRN00C-pf8-then-pf7-top-message`

A backward page that includes the first record still runs the look-ahead READPREV, and that ENDFILE leaves 'You have reached the top of the page...' on page 1.

- Lives in [`TransactionListScreen.Task.reachedTop`](../src/main/java/com/carddemo/transaction/TransactionListScreen.java).
- Comes from the program logic.
- Pinned by `transaction-list-pf8-then-pf7`.
- Fix: set the top message only when the page did not fill.

## `COTRN02C-account-replaces-card`

When both an account and a card are typed, only the account is looked up, and the typed card is replaced by the account's card from the cross-reference, so the transaction posts to a card the user did not type.

- Lives in [`TransactionAddScreen.byAccount`](../src/main/java/com/carddemo/transaction/TransactionAddScreen.java).
- Comes from the program logic.
- Pinned by `transaction-add-account-wins-over-card`.
- Fix: reject a typed card that does not belong to the typed account.

## `COTRN02C-bare-date-timestamps`

TRAN-ORIG-TS and TRAN-PROC-TS, 26-character timestamps, get the bare 10-character typed date.

- Lives in [`TransactionAddScreen.timestamp`](../src/main/java/com/carddemo/transaction/TransactionAddScreen.java).
- Comes from the program logic.
- Pinned by `transaction-add-confirm-y-writes`.
- Fix: write the date as a full timestamp, yyyy-MM-dd HH:mm:ss.SSSSSS, as COBIL00C does.

## `COTRN02C-confirm-n-reprompts`

Confirm N does not cancel the add; it asks to confirm again, as a blank confirm does.

- Lives in [`TransactionAddScreen.confirmNo`](../src/main/java/com/carddemo/transaction/TransactionAddScreen.java).
- Comes from the program logic.
- Pinned by `transaction-add-confirm-n`.
- Fix: clear the screen and say the transaction was not added.

## `COTRN02C-copy-last-any-account`

PF5 copies the highest-keyed TRANSACT record whatever its account, not the last transaction of the typed account or card.

- Lives in [`TransactionAddScreen.copyLastTranData`](../src/main/java/com/carddemo/transaction/TransactionAddScreen.java).
- Comes from the program logic.
- Pinned by `transaction-add-pf5-copy-last`, `transaction-add-pf5-copy-last-confirm`.
- Fix: browse the transactions of the typed card and copy its latest.

## `COTRN02C-pre-1582-accepted`

A date before 15 October 1582 passes, because CSUTLDTC's message 2513 (unsupported range) is ignored.

- Lives in [`TransactionAddScreen.validDate`](../src/main/java/com/carddemo/transaction/TransactionAddScreen.java).
- Comes from the program logic.
- Pinned by `transaction-add-orig-date-pre-1582-accepted`.
- Fix: reject message 2513 like every other non-zero severity.

## `COTRN02C-short-field-not-numeric`

IS NUMERIC fails when trailing blanks pad a short entry out to the field, so an account id typed without its leading zeros, or a one-digit type code, is not numeric.

- Lives in [`TransactionAddScreen.numeric`](../src/main/java/com/carddemo/transaction/TransactionAddScreen.java).
- Comes from the program logic.
- Pinned by `transaction-add-short-account-not-numeric`, `transaction-add-type-one-digit`.
- Fix: accept digits shorter than the field and pad them with leading zeros.

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
