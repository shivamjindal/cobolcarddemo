# CardDemo verification map

This directory is the maintained source for verifying CardDemo's user-facing behavior. Each feature file names the cases that prove it; `characterization/run.py --feature <name>` drives them. Read this index, then the feature file.

## Baseline preconditions

- `python3 characterization/doctor.py` prints only `ok` lines. For the Java slice, `python3 characterization/doctor.py --target java` does too. Inside tmux, prefix both with `env -u LD_LIBRARY_PATH`. Otherwise legacy batch cases report `ERROR` (`SKILL.md`, "Launch").
- Seed data is the shipped ASCII data in `app/data/ASCII` plus `characterization/fixtures/seed/usrsec.txt` (converted from the EBCDIC USRSEC file). Every case starts from a fresh copy, edited by its `setup`.
- Users: `ADMIN001`..`ADMIN005` (type A) and `USER0001`..`USER0005` (type U), all with password `PASSWORD`.
- Clock: `2026-11-05 10:00:00.00` for CICS ASKTIME/FORMATTIME and COBOL `CURRENT-DATE`.

## Driving conventions

- Every online journey starts with `{"transid": "CC00"}` and signs on; there is no way to enter a program mid-flow, because programs depend on the COMMAREA the previous screen built.
- Name fields by their BMS names (see `app/bms/*.bms`). Type exactly what a user types; the harness does not upper-case input (the CSD has no UCTRAN for these terminals in this model).
- Batch jobs bind DD names exactly as the JCL in `app/jcl` does. Steps the JCL runs outside COBOL (IDCAMS, SORT) are reproduced in the case setup or with a `derive` binding, never by editing program inputs by hand.
- One feature name per program. Feature names are the unit a Java slice registers in `characterization/targets.json`.
- On the Java slice the same journey runs over HTTP. A transid is `POST /api/session {"transid":"CC00"}`. A key is `POST /api/screens/{screen}/{action}` with the typed fields and the reply's `turn`. The action names come from the reply's `actions` (on sign-on, `ENTER` is `sign-on` and `PF3` is `exit`). An unbound key is `POST /api/screens/{screen}/invalid-key {"key":"PF9"}`. Requests carry the session cookie and the `XSRF-TOKEN` cookie echoed as `X-XSRF-TOKEN`. In the React app at `http://127.0.0.1:5173`, the keyboard's Enter and F1-F12 keys are the 3270 keys, and Escape is CLEAR.

## Java slice coverage

Every feature with cases is ported. `python3 characterization/run.py --target java` prints `java: 325 PASS`: 297 online cases across the 17 online features and 28 batch cases across `batch-posting`, `batch-interest`, `batch-statements`, `batch-reports` and `batch-extracts`. No feature reports `SKIP`. Main menu options 1-10 and admin options 1-4 reach ported screens. Main option 11 and admin options 5-6 reach a placeholder screen in Java (`PendingProgramScreen`, a text reply naming the unported program), as does any program with no `Screen` bean.

On the Java slice a batch case is a one-step deck that the adapter submits to TD queue `JOBS`. The warm server's internal reader runs the deck, and the adapter reads the completion, spool and table diffs back (`SKILL.md`, "Adding a Java slice"). The report submit (`report-submit`, CORPT00C) queues a deck that the reader runs as the TRANREPT procedure. The adapter waits for that run to end, but the golden compares only the queued cards. The CLOSEFIL and OPENFIL jobs, WAITSTEP and the scheduler chains have no goldens. Spring tests cover them (`java/README.md`, "Batch").

## Proof and skip reporting

- A case proves a behavior only when its golden pins the screen and any file effects.
- Report `NOT-RUN` (source-derived) and `SKIP` (not ported) as such; never as verified.
- A failing case is reported with its diff from `report.json`. Do not re-record to make it pass unless the source confirms the new behavior.

## Assumptions every golden depends on

- Working storage without `VALUE` starts as low-values (`cobc -fdefaultbyte=0`), as under CICS and in practice under LE batch.
- `CEEDAYS` (date validation through `CSUTLDTC`) is a C stand-in that implements the YYYY/MM/DD pictures and feedback codes CardDemo uses. `COBDATFT` (assembler) is transliterated to C. `CEE3ABD` ends the step as a user abend.
- Every file is `RECOVERY(NONE)` in the CSD: no rollback on SYNCPOINT ROLLBACK or abend.
- The online TRANSACT seed is `dailytran.txt` (the mainframe INIT seed is one placeholder record).
- CBSTM03A's TIOT walk (z/OS control blocks) is commented out at build time; its DD-name SYSOUT lines are not characterized.
- Optional Db2, IMS and MQ modules (`app/app-*`) are out of scope.

## Feature entry contract

Each feature file starts with an H1 and one paragraph describing the user-visible behavior, then four H2s in order: `Sub-features`, `How to get to it (user POV)`, `Driving it with run.py`, `Gotchas`.

## Features

- [Sign-on](./signon.md): `signon` (COSGN00C).
- [Menus](./menus.md): `main-menu` (COMEN01C) and `admin-menu` (COADM01C).
- [User administration](./user-admin.md): `user-list`, `user-add`, `user-update`, `user-delete` (COUSR00C-03C).
- [Account and card](./account-card.md): `account-view`, `account-update`, `card-list`, `card-detail`, `card-update`.
- [Transactions and bill pay](./transactions.md): `transaction-list`, `transaction-view`, `transaction-add`, `bill-pay`, `report-submit`.
- [Batch jobs](./batch.md): `batch-posting`, `batch-interest`, `batch-statements`, `batch-reports`, `batch-extracts`.
