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

TODO(each online slice): list the routes that replace each program and the replay test that loads the spec YAML. Then list which case ids pass, which are deliberately changed (with the reason recorded in the spec's `notes`), and the command that runs the replay.

## Gotchas

- The validator proves messages, fields, files, and line ranges exist. It cannot prove the outcome logic was read correctly. Treat each case as a reviewed claim.
- CICS pseudo-conversational state lives in the COMMAREA. Cases set it through `given.commarea`, and a Java slice must map it to session or request state, not drop it.
- Messages keep the mainframe's exact text, including trailing ` ...` and odd capitalization. Assert the exact string unless the slice records a deliberate change.
- Specs cover the base CardDemo programs only. The optional Db2, IMS, and MQ modules under `app/app-*` are not captured.
