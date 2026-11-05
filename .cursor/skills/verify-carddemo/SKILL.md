---
name: verify-carddemo
description: "Drive the CardDemo COBOL/CICS app (3270 screens and batch jobs) under the legacy GnuCOBOL harness, or a Java slice through the same goldens, and capture evidence. Use to prove sign-on, user admin, menus, account/card, transaction, bill pay and batch behavior, to record new characterization goldens, or to check a Java port against legacy behavior."
---

# Verify CardDemo

CardDemo has two user surfaces: 3270 screens (CICS transactions such as `CC00` sign-on) and batch jobs (JCL steps such as POSTTRAN's `CBTRN02C`). There is no mainframe here. The legacy programs run for real under GnuCOBOL. `EXEC CICS` is translated into calls to a C CICS stub. Screens are a 3270 model built from the BMS maps in `app/bms`. VSAM files are text fixtures decoded by copybook.

Every verification is a **case**: a JSON journey (screen steps or one batch job) plus the observed result, the golden. `characterization/run.py` replays cases against a **target**. `legacy` is the GnuCOBOL harness. A Java slice is another target that answers the same requests. Same case, same golden, different implementation.

Read `features/README.md` before driving; it maps each user feature to its cases and recipes.

## Launch

Prerequisites (Ubuntu): `sudo apt-get install -y gnucobol3 gcc python3`. GnuCOBOL 3.1+ is required.

Build everything once from the repo root (idempotent, rebuilds only what changed):

```bash
python3 characterization/harness/build.py
```

Ready means the command exits 0 and prints `built ... online programs` and `built ... batch programs`. `run.py` also builds before running, so this step is only needed to see build errors on their own. There is no server: each screen task runs as one short-lived `cicsrun` process, and each batch job is one `jobrun` process. Each case gets a fresh copy of the seed data under `characterization/.build/runs/<case-id>/`, so runs never share state and two runs of different cases can go side by side. Two concurrent runs of the *same* case id clobber each other's run directory; give each a distinct `--evidence` path and do not run the same case twice at once.

## Doctor

```bash
python3 characterization/doctor.py
```

Read-only. Checks GnuCOBOL and gcc, that all online and batch modules are built, and that `CC00` paints the sign-on map `COSGN0A`. Every line must start with `ok`. Run it first whenever a case errors unexpectedly.

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

## Evidence

Every run writes `characterization/.evidence/<timestamp>-<target>/` (or `--evidence DIR`): `report.json` (status per case with diffs), `<case-id>.json` (the actual observations), and `runs/<case-id>/` (the seeded `.dat` files after the run, `task.in`, `events.NN.jsonl` per CICS task with every CICS command issued, and batch `dd/` files). Proof standard: the case drives the user path (transid and keys, or the job's DD bindings), and the golden pins both the screen and the file side effects. A screen message alone does not prove a write; the `files` diff does.

## Cleanup

```bash
characterization/cleanup.sh          # remove run directories
characterization/cleanup.sh --all    # also remove compiled modules
```

Cleanup never touches `characterization/.evidence/` or a custom `--evidence` directory. Nothing runs in the background, so there are no processes to kill.

## Helpers

- `characterization/run.py`: run, compare, record (usage above; `--help`).
- `characterization/doctor.py`: read-only health check.
- `characterization/cleanup.sh`: teardown that keeps evidence.
- `characterization/reference_adapter.py`: external-target adapter template (`--target legacy-adapter`).
- `characterization/harness/build.py`: compile programs, the CICS stub and the LE stand-ins.
- Inside `characterization/harness/`: `cicsprep.py` (EXEC CICS translator), `runtime/cicsstub.c` (CICS commands supported: SEND/RECEIVE MAP, SEND TEXT, RETURN, XCTL, READ/WRITE/REWRITE/DELETE, STARTBR/READNEXT/READPREV/ENDBR, ASKTIME, FORMATTIME, ASSIGN, INQUIRE, WRITEQ TD, SYNCPOINT, ABEND; an unhandled condition abends with its CICS code, such as AEIM for NOTFND or AEIP for INVREQ; a command the stub lacks logs an `unsupported` event and abends `HUNS`, which a golden must never contain), `screen.py` (3270 model), `online.py`, `batch.py`, `datasets.py`, `copybook.py`.

Fixed assumptions every golden depends on: the clock is `2026-11-05 10:00:00.00`; working storage starts as low-values; all files are `RECOVERY(NONE)` (no rollback); TRANSACT online seed is `dailytran.txt`. See `features/README.md` for the full list.
