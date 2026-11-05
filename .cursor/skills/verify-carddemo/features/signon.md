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
- Java slice: open `http://127.0.0.1:5173`. The React app starts `CC00` itself and paints `COSGN0A`.

## Driving it with run.py

Preconditions: baseline; the case file is `characterization/cases/signon/COSGN00C.json`.

- **All sign-on behavior.** Run `python3 characterization/run.py --feature signon`. Expect `legacy: 9 PASS`.
- **Admin routing.** Run `--case signon-admin`. The last step's golden shows `"map": "COADM1A"`.
- **Session end.** Run `--case signon-pf3`. The golden has `text` `Thank you for using CardDemo application...` and `"session": "ended"`.
- **Java slice.** Add `--target java`. Expect `java: 9 PASS`.
- **Java API by hand.** With the API on 8080, this signs on as an admin. The last call answers `"screen":"admin-menu"` and map `COADM1A`:

```bash
B=http://127.0.0.1:8080/api; J=/tmp/carddemo.cookies; rm -f $J
curl -s -c $J -b $J $B/session -o /dev/null      # 404, but sets XSRF-TOKEN
post() { curl -s -c $J -b $J -H "X-XSRF-TOKEN: $(awk '/XSRF-TOKEN/{print $7}' $J)" -H 'Content-Type: application/json' -d "$2" $B/$1; echo; }
post session '{"transid":"CC00"}'
post screens/signon/sign-on '{"turn":1,"fields":{"USERID":"admin001","PASSWD":"PASSWORD"}}'
```

## Gotchas

- The password field is FSET with underscores in the BMS. ENTER without touching it sends `________`, which fails as a wrong password, not as an empty password. Erase it (`"PASSWD": ""`) to get `Please enter Password ...`.
- Lower-case input works because the program upper-cases it; do not assume the terminal does.
- A password change through user update takes effect at the next sign-on: drive both in one journey to prove it.
- Java: the React client sends only fields the user typed. Untouched `PASSWD` becomes `________` on the server, as the 3270 would send it, so the wrong-password golden holds over HTTP too. That defect lives in `SignonScreen.passwordBlank`, and the distinct unknown-user versus wrong-password messages live in `SignonScreen.rejection`. Both are listed in `java/docs/legacy-defects.md`.
- Java: a successful sign-on rotates the session id and the CSRF token. A client that keeps the old `X-XSRF-TOKEN` gets 403 on the next call. Re-read the cookie after every response, as the recipe above does.
- Java: posting a stale `turn` returns 409 and changes nothing. Starting a protected transid while signed off returns 403, where legacy would transfer to sign-on; no golden pins it.
