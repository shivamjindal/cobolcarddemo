# Online screens

The CICS screens let users sign on, let admins manage users, and let users view and update accounts and cards, browse and add transactions, pay bills, and request reports. Their behavior is captured as one YAML spec per program, derived from reading the COBOL, BMS, and copybooks. None of it was executed.

## Sub-features

- `signon` (`COSGN00C`, CC00) authenticates against USRSEC and routes admins to `COADM01C` and users to `COMEN01C`.
- `menus` (`COMEN01C`, `COADM01C`) handle option selection and user-type gating.
- `user-admin` (`COUSR00C` list, `COUSR01C` add, `COUSR02C` update, `COUSR03C` delete) maintains the USRSEC records.
- `account` (`COACTVWC` view, `COACTUPC` update) covers account and customer view, field validation, and the confirm-and-save flow.
- `card` (`COCRDLIC` list, `COCRDSLC` view, `COCRDUPC` update) covers card list filters, selection, and update validation.
- `transactions` (`COTRN00C` list, `COTRN01C` view, `COTRN02C` add) covers transaction browse, view, and add with validation.
- `billpay` (`COBIL00C`) pays the full balance, writing a transaction and rewriting the account.
- `report-request` (`CORPT00C`) validates the date range and submits TRANREPT.

## How to get to it (user POV)

- On the mainframe, a user enters a transid (CC00 for sign-on) on a 3270 terminal and moves between screens with ENTER and the PF keys.
- In Java, use the REST endpoints and screens each slice adds. Each slice lists its routes under "Driving it with the Spring app" below.

## Driving it with validate_specs.py

Preconditions:

- `scripts/doctor.sh` exits 0.

- **Grounding.** Run `python3 characterization/online/validate_specs.py`. It exits 0 and prints `N specs, M cases, 0 errors`.
- **Read one program's expectations.** Open `characterization/online/specs/COSGN00C.yaml`. Each case gives `given`, `when` (aid key and input fields), `then` (message, cursor, transfer, file effects), and `source` lines.
- **Spot-check a case against source.** For a case id, open `source.file` at `source.lines` and confirm the outcome. Do this for any case a slice relies on that was not reviewed before.
- **Proof.** Cite case ids and say "derived from source, not executed".

## Driving it with the Spring app

Preconditions: `scripts/doctor.sh` exits 0. The live drives also need `scripts/spring.sh up`.

Routes: every installed program is `GET`/`POST /screens/<PROGRAM>` (HTML) and `GET`/`POST /api/screens/<PROGRAM>` (JSON `{aid, fields}`). Sign-on is `/signon`, the OIDC provider's login page. A program that isn't installed is 404, and a menu option pointing at one says "not installed".

| Sub-feature | Programs installed | Replay (`./gradlew test`) |
| --- | --- | --- |
| `signon` | COSGN00C | 12 passed, 3 overridden |
| `menus` | COMEN01C, COADM01C | 23 passed, 5 overridden |
| `user-admin` | COUSR00C-03C | 58 passed, 12 overridden |
| `account` | COACTVWC, COACTUPC | 131 passed, 17 overridden, 3 skipped |
| `card` | COCRDLIC, COCRDSLC, COCRDUPC | 61 passed, 38 overridden, 1 skipped |
| `transactions` | COTRN00C, COTRN01C, COTRN02C | 65 passed, 12 overridden, 1 skipped |
| `billpay` | COBIL00C | 14 passed, 2 overridden, 1 skipped |
| others | none yet | not replayed |

- **Replay.** `cd carddemo-java && ./gradlew test`, then read the `[spec-replay]` lines. Overrides and their reasons are in `carddemo-java/src/test/resources/spec-overrides/<PROGRAM>.yaml`.
- **HTTP.** `python3 scripts/drive_spring.py --out DIR` covers sign-on, the menus, user add, update, delete, and sign-off, then account view and update (group id lands in `acct_group_id`, zip kept, submitted keys and country ignored) and the card list, update and return-to-list as a regular user (CVV kept), then the transaction list, view and add (the next id, the account's card, impossible dates refused) and bill payment (two simultaneous confirmations pay once).
- **Browser.** `python3 scripts/browser_drive.py --out DIR` covers the same screens with the keyboard and saves screenshots, through account view, the regular-user card list and card update, the transaction list and view, and the bill payment balance.
- **Proof.** Cite the case id and say "replayed against Java" with its status (passed as written, or the override's reason). The legacy side stays derived.

## Gotchas

- The validator proves messages, fields, files, and line ranges exist. It cannot prove the outcome logic was read correctly. Treat each case as a reviewed claim.
- CICS pseudo-conversational state lives in the COMMAREA. Cases set it through `given.commarea`, and a Java slice must map it to session or request state, not drop it.
- Messages keep the mainframe's exact text, including trailing ` ...` and odd capitalization. Assert the exact string unless the slice records a deliberate change.
- In Java, `CDEMO-PGM-CONTEXT` is implicit: arriving through a transfer is a GET (context 0), and pressing a key is a POST (context 1). The replay ignores that field. `CDEMO-FROM-PROGRAM` is `Conversation.from()`.
- `SEC-USR-PWD` holds a bcrypt hash in Postgres. The replay hashes `given.files` passwords and compares `file_effects` passwords by matching, never by equality.
- No seeded customer passes every COACTUPC field edit (FICO range, North American area codes, SSN, state and zip). To drive a save, type valid customer values; `drive_spring.py` shows a set.
- Field protection follows each program's SETUP-SCREEN-ATTRS paragraph, phase by phase. `Outcome.Show.readOnly` names the protected fields; they render read-only, the JSON screen lists them in `readOnly`, and the engine resubmits the value shown in place of whatever the client sends (DFHBMPRF comes back as sent).
- The `transactions` table is seeded from the daily feed (`dailytran.txt`, 300 records) as if POSTTRAN had posted every one, with the processed time equal to the original time. It stands in until the batch slice ports POSTTRAN, which must replace it.
- Bill payment and transaction add number new records under a Postgres advisory lock (highest numeric id + 1); the bill payment locks the account row with `RecordLock` in the same transaction.
- COCRDLIC's selection takes uppercase `S` or `U` only, as in legacy.
- A regular user's card list needs an account filter; COCRDSLC and COCRDUPC do not restrict regular users yet.
- Specs cover the base CardDemo programs only. The optional Db2, IMS, and MQ modules under `app/app-*` are not captured.
