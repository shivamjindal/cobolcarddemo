# Sign-on

A user starts transaction `CC00`, types a user ID and password, and is routed by user type: admins to the admin menu, regular users to the main menu. Wrong input is reported on the same screen. PF3 ends the session.

## Sub-features

- `signon-display` paints `COSGN0A` with the cursor on `USERID`.
- `signon-validate` checks user ID, then password, in that order.
- `signon-auth` rejects unknown users and wrong passwords with distinct messages.
- `signon-route` sends type A to `COADM1A` and type U to `COMEN1A`. User ID and password are upper-cased by the program before lookup.
- `signon-exit` PF3 sends a goodbye text and ends the session; other keys show the invalid key message.

## How to get to it (user POV)

- Clear the terminal and type `CC00`.
- PF3 from the main or admin menu returns here.

## Driving it with run.py

Preconditions: baseline; the case file is `characterization/cases/signon/COSGN00C.json`.

- **All sign-on behavior.** Run `python3 characterization/run.py --feature signon`. Expect `legacy: 9 PASS`.
- **Admin routing.** Run `--case signon-admin`. The last step's golden shows `"map": "COADM1A"`.
- **Session end.** Run `--case signon-pf3`. The golden has `text` `Thank you for using CardDemo application...` and `"session": "ended"`.

## Gotchas

- The password field is FSET with underscores in the BMS. ENTER without touching it sends `________`, which fails as a wrong password, not as an empty password. Erase it (`"PASSWD": ""`) to get `Please enter Password ...`.
- Lower-case input works because the program upper-cases it; do not assume the terminal does.
- A password change through user update takes effect at the next sign-on: drive both in one journey to prove it.
