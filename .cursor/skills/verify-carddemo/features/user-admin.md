# User administration

Admins list, add, update and delete CardDemo users (the USRSEC file). Each screen validates its fields in a fixed order, writes USRSEC on success, and reports the result on the same screen.

## Sub-features

- `user-list` pages users 10 at a time from an optional starting ID; PF7/PF8 page; `U`/`u` and `D`/`d` in a row's select field open update or delete for that user.
- `user-add` checks first name, last name, user ID, password, user type (in that order), then writes the record. User type is not validated beyond being non-blank. Duplicates show `User ID already exist...`.
- `user-update` ENTER fetches a user; PF5 saves changed fields (or says `Please modify to update ...`); PF3 saves and returns; PF12 returns without saving; PF4 clears.
- `user-delete` ENTER fetches; PF5 deletes (even without a prior fetch); PF12 returns.
- `user-effects` a new user can sign on; a promoted user lands on the admin menu; a deleted user is not found.

## How to get to it (user POV)

- Sign on as `ADMIN001`, then option 1 (list), 2 (add), 3 (update) or 4 (delete).
- From the list, type `U` or `D` next to a user and press ENTER.

## Driving it with run.py

Preconditions: baseline; cases in `characterization/cases/user-admin/COUSR0[0-3]C.json`.

- **All of it.** Run `python3 characterization/run.py --feature user-list --feature user-add --feature user-update --feature user-delete`. Expect 49 PASS.
- **A write.** Run `--case user-add-success`. The golden's `files.USRSEC.inserted` holds the new record and `ERRMSG` reads `User GHOPPER has been added ...` in green.
- **End-to-end effect.** Run `--case user-update-promote-routes-to-admin-menu`. The journey promotes USER0002 to type A, signs off, signs on as USER0002 and lands on `COADM1A`.

## Gotchas

- Paging needs more than 10 users; the seed has exactly 10. Cases that page add users in `setup`.
- With the seed's 10 users, an invalid select letter's message is overwritten by `You have reached the bottom of the page...`; the message only shows when the page does not reach end of file. Both are pinned.
- The update and delete screens' fields are BMS `FSET`, so fields the user does not touch are still sent. Erasing a field (`""`) is how to test the empty-field messages.
- User IDs are stored as typed (no upper-casing), but sign-on upper-cases what it reads, so a lower-case user ID cannot sign on.
