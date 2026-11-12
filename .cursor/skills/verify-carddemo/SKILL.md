---
name: verify-carddemo
description: Verify CardDemo behavior during the COBOL-to-Java/Spring migration. Runs the legacy characterization suite (GnuCOBOL golden masters for batch programs, source-derived specs for CICS screens) and, once slices land, drives the Spring app against the same expectations. Use before and after changing any migrated slice, when asked to prove parity with the mainframe, or when a golden file or spec diff needs explaining.
---

# Verify CardDemo

CardDemo has two legacy surfaces and one future one.

| Surface | What runs here | Evidence kind |
| --- | --- | --- |
| Batch jobs (`app/jcl`, `app/cbl/CB*`) | The real COBOL, compiled by GnuCOBOL, against `app/data/ASCII` fixtures | **Executed.** Golden files in `characterization/batch/golden/` |
| CICS screens (`app/cbl/CO*`, `app/bms`) | Nothing. CICS is not available | **Derived from source.** YAML specs in `characterization/online/specs/`, checked for grounding only |
| Java/Spring app | Not built yet | See [Spring app](#spring-app-filled-in-by-slices) |

Never describe a derived spec as observed behavior. When you report results, say which kind of evidence backs each claim.

## Launch

No server exists yet. "Launch" means compiling the batch programs, and the harness does that itself.

1. Install the toolchain once per machine: `sudo apt-get install -y gnucobol3` (GnuCOBOL 3.1.2 with the Berkeley DB indexed handler). Python 3 and PyYAML are already on the image.
2. Run `.cursor/skills/verify-carddemo/scripts/doctor.sh`. Proceed only on exit 0.
3. Run the suite with `.cursor/skills/verify-carddemo/scripts/verify.sh`. It compiles into `characterization/batch/build/` (about 3 s) when any COBOL source, copybook, stub, or the harness changed, then runs every job in a fresh scratch directory. Ready means the summary line prints. There is no long-lived process.

## Doctor

`.cursor/skills/verify-carddemo/scripts/doctor.sh` is read-only. It checks for `cobc` 3.x, the BDB indexed handler, PyYAML, the golden files, the specs, and the fixtures, and it reports whether a JVM build exists yet. Run it first whenever something looks off. A `FAIL` line names the fix.

## Drive

**Batch (executed).** `python3 characterization/batch/harness.py` runs this nightly cycle in order against one evolving set of datasets: load the KSDS files, then READACCT, READCARD, READXREF, READCUST, VALIDTRAN, POSTTRAN, INTCALC, COMBTRAN, TRANREPT, and CREASTMT. A separate POSTTRAN-EDGES scenario runs crafted transactions at each `CBTRN02C` validation boundary. Every artifact (`<JOB>/<PROGRAM>.sysout`, output datasets, unloaded KSDS files) is diffed against `characterization/batch/golden/<JOB>/<artifact>.txt`.

- Run everything and diff: `python3 characterization/batch/harness.py`. It exits 0 only when every artifact matches.
- Diff one job (all jobs still run, because they share state): `--job POSTTRAN`.
- Keep the scratch datasets for inspection: `--keep` (prints the path).
- Keep this run's artifacts: `--out DIR`.
- Re-bless after a deliberate legacy change: `--update`, then review `git diff characterization/batch/golden` line by line.

The jobs follow `app/jcl/*.jcl`. The harness emulates IDCAMS REPRO and DFSORT in Python. It replaces the assembler routine `COBDATFT` and the LE abend `CEE3ABD` with stubs in `characterization/batch/stubs/`, and strips `CBSTM03A`'s z/OS control-block walk. `characterization/README.md` lists every deviation.

**Online (derived).** `python3 characterization/online/validate_specs.py` checks every spec. It confirms that each message literal exists in the cited COBOL, each field exists in the BMS copybook, each file and transid exists in the CSD, and each cited line range is real. Exit 0 proves grounding, not correctness. The format is in `characterization/online/SCHEMA.md`.

## Evidence

`verify.sh [DIR]` writes to `DIR` (default `/tmp/carddemo-verify/<UTC timestamp>/`):

- `summary.txt` holds the commit, plus the batch and online results and exit codes.
- `batch/` holds every actual artifact in the golden layout, so `diff -r characterization/batch/golden DIR/batch` reproduces the comparison.
- `batch.log` and `online.log` hold the full harness and validator output.

Proof standards:

- A batch claim cites the job and artifact, for example `POSTTRAN/DALYREJS`. When a golden changes, show the diff lines and the COBOL lines that explain them.
- Fixtures cover only what they contain. One example: a `>=` changed to `>` in the credit-limit check of `CBTRN02C` passed the main cycle and was caught only by POSTTRAN-EDGES. When you rely on a behavior, find the artifact that pins it, and add an edge scenario if none does. Never weaken a golden to make a run pass.
- An online claim cites the spec case id, such as `COSGN00C-003`, and states that it is derived.
- After `--update`, every changed golden line needs an explanation before commit.

## Cleanup

The harness deletes its own scratch directory unless you pass `--keep`. Remove kept ones with `rm -rf /tmp/carddemo-batch-*`, after copying anything you need. `characterization/batch/build/` is a git-ignored cache and safe to delete. Evidence directories under `/tmp/carddemo-verify/` are never removed by these scripts.

## Spring app (filled in by slices)

The Java/Spring app does not exist yet. The slice that first makes it runnable fills in each subsection below, replacing the TODO with real commands verified on this VM, and updates `doctor.sh` and the feature map. Until then, do not claim any Java parity.

### Launch

TODO(first runnable slice): the exact build and run commands (for example the Maven or Gradle wrapper and the Spring profile), the Postgres setup (container or local, migrations, seed load from `app/data/ASCII`), the port, and the log line or health endpoint that means ready. It must also cover teardown, and say whether two instances can run side by side.

### Doctor

TODO: extend `scripts/doctor.sh` to check the JVM version, that the port is owned by an instance this run started, the database schema version, and the build revision.

### Drive

TODO per slice. The parity contract the slices should implement:

- **Online specs.** Load `characterization/online/specs/<PROGRAM>.yaml` in a parameterized test. For each case, set up `given.files` rows, call the endpoint that replaces the screen with `when.fields` and `when.aid`, and assert `then.message`, `then.fields`, the `then.transfer` navigation, and `then.file_effects` against the database. Record any case the slice deliberately changes (a fixed legacy bug, for example) in the spec's `notes`, with the reason.
- **Batch goldens.** Run the replacement job on the same fixtures and the same fixed clock (`2022-07-06 10:00:00.00`, INTCALC parm `2022071800`). Render outputs in the golden text format: one record per line, non-printables as `\xNN`, trailing spaces dropped. Then `diff -r` against `characterization/batch/golden/`.
- **UI.** When screens exist, add the browser recipe here, using stable handles (labels or `data-testid` named after BMS fields such as `USERID` and `PASSWD`).

### Evidence

TODO: where the Spring run's screenshots, HTTP transcripts, and DB snapshots go, with the same `summary.txt` convention.

## Feature map

Start at [`features/README.md`](features/README.md). Each feature file says how to drive it now, and what the Spring slice must add.
