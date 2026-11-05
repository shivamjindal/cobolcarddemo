---
name: verify-carddemo
description: "Drive the CardDemo COBOL/CICS app (3270 screens and batch jobs) under the legacy GnuCOBOL harness, or a Java slice through the same goldens, and capture evidence. Use to prove sign-on, user admin, menus, account/card, transaction, bill pay and batch behavior, to record new characterization goldens, or to check a Java port against legacy behavior."
---

# Verify CardDemo

CardDemo has two user surfaces: 3270 screens (CICS transactions such as `CC00` sign-on) and batch jobs (JCL steps such as POSTTRAN's `CBTRN02C`). There is no mainframe here. The legacy programs run for real under GnuCOBOL. `EXEC CICS` is translated into calls to a C CICS stub. Screens are a 3270 model built from the BMS maps in `app/bms`. VSAM files are text fixtures decoded by copybook. The optional Db2 extension (`app/app-transaction-type-db2`) runs its embedded SQL for real against a local Postgres database: `EXEC SQL` is translated into calls to a C runtime that emulates Db2's SQLCODEs, statement rollback and commit points.

Every verification is a **case**: a JSON journey (screen steps or one batch job) plus the observed result, the golden. `characterization/run.py` replays cases against a **target**. `legacy` is the GnuCOBOL harness. A Java slice is another target that answers the same requests. Same case, same golden, different implementation.

Read `features/README.md` before driving; it maps each user feature to its cases and recipes.

## Launch

Prerequisites (Ubuntu): `sudo apt-get install -y gnucobol3 gcc python3 libpq-dev pkg-config postgresql`. GnuCOBOL 3.1+ is required. The Db2 extension needs Postgres running with the `carddemo` role described under "Java slice"; the harness creates database `carddemo_legacy_db2` (override with `CARDDEMO_LEGACY_DB2`) and rebuilds its tables from the shipped `ddl/` before every case.

Build everything once from the repo root (idempotent, rebuilds only what changed):

```bash
python3 characterization/harness/build.py
```

Ready means the command exits 0 and prints `built ... online programs`, `built ... batch programs` and `built 3 Db2 extension programs`. `run.py` also builds before running, so this step is only needed to see build errors on their own. There is no server: each screen task runs as one short-lived `cicsrun` process, and each batch job is one `jobrun` process. Each case gets a fresh copy of the seed data under `characterization/.build/runs/<case-id>/`, so runs never share state and two runs of different cases can go side by side. Two concurrent runs of the *same* case id clobber each other's run directory; give each a distinct `--evidence` path and do not run the same case twice at once.

Inside a tmux session on the cloud VM, `LD_LIBRARY_PATH` is `/exec-daemon/tmux-root/lib`. The GnuCOBOL runtime `libcob` then loads that directory's `libncursesw.so.6`, prints `no version information available`, and every legacy batch case reports `ERROR`. Run `run.py` and `doctor.py` from tmux as `env -u LD_LIBRARY_PATH python3 characterization/run.py ...`.

### Java slice

Prerequisites: JDK 21, Maven 3.9, Postgres 16 with role and database `carddemo` (password `carddemo`, allowed to create databases), Node 22 for the React client. On a fresh Ubuntu VM: `sudo apt-get install -y postgresql && sudo service postgresql start`, then `sudo -u postgres psql -c "CREATE ROLE carddemo LOGIN SUPERUSER PASSWORD 'carddemo'" -c "CREATE DATABASE carddemo OWNER carddemo"`.

The `java` target needs no manual launch. `java/characterization-adapter` rebuilds the jar when a source is newer, creates database `carddemo_characterization` if it is missing, and keeps one warm server on `127.0.0.1:18080` (profile `characterization`, clock fixed at `2026-11-05 10:00`, internal reader polling every 0.2 seconds). Before every case it reseeds the ten keyed datasets (USRSEC, ACCTDATA, CUSTDATA, CARDDATA, CARDXREF, TRANSACT, DISCGRP, TCATBALF, TRANCATG, TRANTYPE) and the two Db2 tables (`carddemo.transaction_type`, `carddemo.transaction_type_category`), installs exactly the extensions the case lists, empties the job queue (`job_submissions`, `job_submission_runs`), the spool and the sequential-dataset catalog (`datasets`, `dataset_records`), and reopens every CICS file, so cases never share state. Do not run two `--target java` runs at once: they share that database. A second checkout gets its own server and data with `CARDDEMO_CHARACTERIZATION_PORT=18081 CARDDEMO_CHARACTERIZATION_DB=carddemo_char_2`, and its own Spring test database with `CARDDEMO_TEST_DB=carddemo_test_2`. The server stops after ten idle minutes.

To drive the slice by hand (API and React screens), use two long-lived tmux sessions:

```bash
cd java && mvn -q spring-boot:run -Dspring-boot.run.profiles=load -Dspring-boot.run.arguments=--carddemo.load.app-root=/workspace/app  # once: load database carddemo
cd java && mvn -q spring-boot:run          # API on 127.0.0.1:8080, ready when GET /api/maps/COSGN0A answers 200
cd web && npm install && npm run dev -- --host 127.0.0.1 --port 5173   # React on http://127.0.0.1:5173, proxies /api to 8080
```

## Doctor

```bash
python3 characterization/doctor.py                 # legacy harness
python3 characterization/doctor.py --target java   # Java slice
```

The legacy doctor is read-only. It checks GnuCOBOL and gcc, that all online and batch modules are built, and that `CC00` paints the sign-on map `COSGN0A`. The Java doctor checks the JDK, Maven and Postgres, then sends a one-step `CC00` case through the adapter, which starts the warm server if needed and must answer `COSGN0A`. Every line must start with `ok`. Run it first whenever a case errors unexpectedly.

## Drive

Run cases (the exit status is 0 only when every selected case the target supports passes):

```bash
python3 characterization/run.py                                # every case, legacy target
python3 characterization/run.py --feature signon               # one feature (repeatable)
python3 characterization/run.py --case user-add-duplicate      # ids containing this text
python3 characterization/run.py --target java --feature signon # a Java slice, same goldens
```

Statuses: `PASS`; `FAIL` (prints a JSON-path diff per mismatch); `ERROR` (the target crashed; message printed); `NOT-RUN` (a `source-derived` case: the legacy harness cannot execute it, expectations were written from source); `SKIP` (feature not yet ported to that target).

### Case format

Suites live in `characterization/cases/<area>/<PROGRAM>.json`:

```json
{"feature": "user-add", "kind": "online", "programs": ["COUSR01C"],
 "cases": [{"id": "user-add-duplicate", "title": "An existing user ID is rejected",
   "provenance": "observed", "source": "COUSR01C WRITE-USER-SEC-FILE DUPREC",
   "setup": {"USRSEC": {"upsert": [{"SEC-USR-ID": "X", "SEC-USR-TYPE": "U"}]}},
   "steps": [{"do": {"transid": "CC00"}},
             {"do": {"aid": "ENTER", "fields": {"USERID": "ADMIN001", "PASSWD": "PASSWORD"}}},
             {"do": {"aid": "ENTER", "fields": {"OPTION": "2"}}},
             {"do": {"aid": "ENTER", "fields": {"USERID": "USER0001", "...": "..."}}, "expect": {"map": "COUSR1A", "...": "..."}}]}]}
```

- Online step `do`: `{"transid": "CC00"}` starts a transaction on a blank screen; `{"aid": "ENTER"|"PF1".."PF24"|"CLEAR"|"PA1".."PA3", "fields": {...}}` types into fields and presses the key. Field names are the BMS names without the `I`/`O` suffix (`USERID`, `OPTION`, `SEL0001`). A typed value replaces the field; `""` erases it. Untouched fields are sent only if their MDT is on (BMS `FSET` or set by the program), exactly as a 3270 would.
- Batch case: `"job": {"program": "CBTRN02C", "parm": "...", "dd": {DDNAME: binding}}` instead of `steps`, and one `expect`. Bindings: `"ACCTDATA"` (dataset; keyed ones become indexed files and are diffed), `{"dataset": "TRANSACT"}` (keyed dataset as a flat file), `{"lines": [...], "reclen": 80}` (inline card), `{"output": "NAME", "reclen": n}` with optional `"recfm": "V"`, `"decode_as": "DATASET"`, `"hex": true`, and `{"derive": "creastmt-trxfl"}`.
- `setup` edits seed datasets per case: `upsert` (an existing key changes only the named fields), `delete` (keys), `replace` (whole dataset), `append` (sequential). Dataset names and layouts: `characterization/fixtures/datasets.json`.
- `"install": ["transaction-type-db2"]` at suite level installs that optional extension for every case in the suite: its CSD group (transids `CTLI`, `CTTU`), its program library and its Db2 tables. Without it the region is the base install, where admin options 5 and 6 answer "not installed".
- A step without `expect` is a lead-in and is not compared. `"match": "subset"` compares only the keys the golden pins.

### Observation (what a golden contains)

Online: `map`; `fields` (non-empty values; clock, applid and title fields excluded); `colors` and `protected`/`unprotected` (only where they differ from the BMS default); `cursor`; `text` (SEND TEXT); `session: "ended"` (RETURN without transid); `abend`; `queues` (TD writes); `files` (per dataset `inserted` records, `updated` as key plus `{field: [old, new]}`, `deleted` keys). Batch: `completion` (`RC=n`, `U0999`-style user abend, or `ABEND-NO-CODE`), `sysout` lines, `files`, `outputs` per output DD. Keys starting with `_` are diagnostics and never compared.

### Recording new goldens

1. Write the case without any `expect`, then run `python3 characterization/run.py --record --case <id>`. Recording fills `expect` on **every** step of the selected cases; delete lead-in expectations you do not want.
2. Review each recording against the COBOL source before committing. A recording is a golden only when the source agrees. If the source disagrees, decide whether it is a legacy quirk (keep it, name it in the title) or a harness artifact (switch the case to `"provenance": "source-derived"`, hand-write the expectation, report the harness gap).
3. Re-run without `--record`; it must PASS.

### Adding a Java slice (target protocol)

Register the slice in `characterization/targets.json`:

```json
"java": {"command": ["java/characterization-adapter"], "features": ["signon"]}
```

`features` lists ported features; the rest report `SKIP`. `run.py` runs `command` from the repo root once per case, writes one JSON request to stdin, and reads one JSON response from stdout (exit 0; stderr is shown on failure):

```json
{"kind": "online", "feature": "signon", "datasets": "/abs/characterization/fixtures/datasets.json",
 "case": {"id": "...", "setup": {...}, "steps": [{"do": {...}}, ...]}}
```

Respond with `{"steps": [observation, ...]}`, one observation per step in the format above (batch: one element). The adapter loads the seed data named by `datasets.json` plus `setup` into a fresh store per case, drives the slice through its real entry point (HTTP/UI controller, batch job launcher), and reports dataset diffs keyed and decoded the same way. Add the slice's feature name to `features` only when its cases pass; never edit a golden to make a port pass.

`characterization/reference_adapter.py` is a working adapter (it answers with the legacy harness) and the template to copy. `python3 characterization/run.py --target legacy-adapter` proves the protocol plumbing; build first, since `run.py` only builds for the `legacy` target.

The Java port is registered as target `java`: `java/characterization-adapter` (launcher) and `com.carddemo.characterization.Adapter` (the client). Its `features` are every feature with cases: the 19 online features (`signon` through `report-submit`, plus the Db2 extension's `tran-type-list` and `tran-type-maint`) and the six batch features (`batch-posting`, `batch-interest`, `batch-statements`, `batch-reports`, `batch-extracts`, `batch-tran-type-maint`). Run them all:

```bash
python3 characterization/run.py --target java
```

Ready means `java: 424 PASS`: 384 online (signon 9, main-menu 10, admin-menu 10, user admin 49, account and card 119, transactions and bill pay 100, Db2 transaction types 87) and 40 batch (posting 12, interest 7, extracts 4, statements 3, reports 2, Db2 type maintenance 12). The first run builds the jar and starts the server, which takes about 30 seconds more.

An online case runs over HTTP. The client turns each step into the same calls the React app makes (`POST /api/session`, then `POST /api/screens/{screen}/{action}` or `invalid-key`), so a pass proves the API, not a test double. A screen that submits a job records it through `JobSubmissions`. The adapter reports the rows recorded during a step as that step's `queues`, and waits until the internal reader has ended each of them before it diffs the files.

A batch case runs through the same queue the online report submit uses (`BatchCase`):

1. The adapter seeds the tables and the case's `setup`, then turns the case's `job` into a one-step deck: `//CHARZ JOB '<case id>'`, `//STEP01 EXEC PGM=<program>,PARM='<parm>'`, a `SYSOUT DD SYSOUT=*` for DISPLAY lines, and one DD per binding.
2. It stages each binding the way `harness/batch.py` does. A keyed dataset is `DSN=<its cluster>,DISP=SHR` on the seeded table. A sequential dataset (DALYTRAN) is cataloged as `AWS.M2.CARDDEMO.<name>.PS` from the seed or `setup`. `{"dataset": X}` is a key-ordered flat copy cataloged as `AWS.M2.CARDDEMO.CHARZ.<dd>`. `{"lines": ...}` is `DD *` for 80-byte cards; any other `reclen` (COBTUPDT's 53-byte INPFILE) is a fixed-length dataset cataloged as `AWS.M2.CARDDEMO.CHARZ.<dd>`. `{"derive": "creastmt-trxfl"}` catalogs `AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS` through `SortSpec.CreastmtTrxfl`, the CREASTMT sort. `{"output": ...}` is `DD SYSOUT=*` with `DCB=(RECFM=F,LRECL=<reclen>)`, or `DCB=(RECFM=V)` when the case says `"recfm": "V"`.
3. It inserts the deck into `job_submissions` on TD queue `JOBS`. The warm server's `InternalReader` claims the row into `job_submission_runs`, parses the deck, and runs it on Spring Batch. The adapter polls the claim until `ended_at` is set (300 seconds at most).
4. It observes what the harness observes: `completion` is the claim's completion label, `sysout` is STEP01's spooled `SYSOUT`, `files` diffs the ten keyed tables and the two Db2 tables, and each output DD's spool is decoded as the case asks. Fixed records are re-sliced to `reclen` first, as the harness does.

`BatchGoldensIT` runs every case in `characterization/cases/batch/` through the same `BatchCase` with the internal reader of its own Spring context, so `mvn verify` checks all 40 batch goldens without `run.py`. To run one case on java, select it by id: `python3 characterization/run.py --target java --case posting-mixed-batch`.

To port an online feature, add a `Screen` bean for each program, copying `useradmin/UserDeleteScreen` (a `Screen` returns an `Outcome`; the `Terminal` owns every 3270 rule, so a screen never touches attributes or cursor bytes). `SeededTable.SEEDED` lists the keyed datasets and Db2 tables the adapter seeds and diffs. A case that sets up a keyed dataset outside that list is refused. To port a batch program, follow "Add a batch program" in `java/README.md`. Add the feature name to `features` only once its cases pass. Pinned legacy defects are reproduced in methods annotated `@LegacyDefect`. `LegacyDefectRegisterTest` checks each annotation's goldens exist and rewrites `java/docs/legacy-defects.md` from the annotations on every `mvn verify`, so commit the file it writes.

## Evidence

Every run writes `characterization/.evidence/<timestamp>-<target>/` (or `--evidence DIR`): `report.json` (status per case with diffs), `<case-id>.json` (the actual observations), and `runs/<case-id>/` (the seeded `.dat` files after the run, `task.in`, `events.NN.jsonl` per CICS task with every CICS command issued, and batch `dd/` files). Proof standard: the case drives the user path (transid and keys, or the job's DD bindings), and the golden pins both the screen and the file side effects. A screen message alone does not prove a write; the `files` diff does.

On the `java` target, `<case-id>.json` is the Java observation. A `runs/<case-id>/` directory there is a copy of the last legacy run of that case, not Java state. The Java side's evidence is in the server log, `java/target/characterization/server.log`, and in database `carddemo_characterization` (the spool and `job_submission_runs`), which holds only the last case because each case's seed clears it.

## Cleanup

```bash
characterization/cleanup.sh          # remove run directories
characterization/cleanup.sh --all    # also remove compiled modules
```

Cleanup never touches `characterization/.evidence/` or a custom `--evidence` directory. The legacy target runs nothing in the background. The `java` target leaves its warm server on `127.0.0.1:18080`; stop it with `characterization/cleanup.sh stop`. Stop hand-driving sessions with `tmux kill-session -t <name>`.

## Helpers

- `characterization/run.py`: run, compare, record (usage above; `--help`).
- `characterization/doctor.py`: health check (`--target java` for the slice).
- `characterization/cleanup.sh`: teardown that keeps evidence; `stop` ends the Java warm server.
- `java/characterization-adapter`: Java target launcher (warm server, database, jar rebuild).
- `characterization/reference_adapter.py`: external-target adapter template (`--target legacy-adapter`).
- `characterization/harness/build.py`: compile programs, the CICS stub and the LE stand-ins.
- Inside `characterization/harness/`: `sqlprep.py` (EXEC SQL translator; writes each program's statement table `<PROG>.sql`), `runtime/sqlstub.c` (the SQL runtime over libpq: SQLCODE mapping, a savepoint per statement, commit at SYNCPOINT and task or step end, a DSNTIAC stand-in; an unmapped Postgres error is SQLCODE -9999, which a golden must never contain), `db2.py` (resets and dumps the Db2 tables as row images), `cicsprep.py` (EXEC CICS translator), `runtime/cicsstub.c` (CICS commands supported: SEND/RECEIVE MAP, SEND TEXT, RETURN, XCTL, READ/WRITE/REWRITE/DELETE, STARTBR/READNEXT/READPREV/ENDBR, ASKTIME, FORMATTIME, ASSIGN, INQUIRE, WRITEQ TD, SYNCPOINT, ABEND; an unhandled condition abends with its CICS code, such as AEIM for NOTFND or AEIP for INVREQ; a command the stub lacks logs an `unsupported` event and abends `HUNS`, which a golden must never contain), `screen.py` (3270 model), `online.py`, `batch.py`, `datasets.py`, `copybook.py`.

Fixed assumptions every golden depends on: the clock is `2026-11-05 10:00:00.00`; working storage starts as low-values; all files are `RECOVERY(NONE)` (no rollback); TRANSACT online seed is `dailytran.txt`. See `features/README.md` for the full list.
