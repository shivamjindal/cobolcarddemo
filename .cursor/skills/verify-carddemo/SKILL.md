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
| Java/Spring app (`carddemo-java/`) | Slice 1-4 screens and batch jobs, plus transaction-type maintenance (`COTRTLIC`, `COTRTUPC`, `MNTTRDB2`) and the MQ inquiries `COACCT01` and `CODATE01`, on Postgres, OIDC sign-on, and an embedded Artemis broker | **Executed.** Spec replay and the batch golden comparison in `./gradlew test`, plus live HTTP and browser drives. See [Spring app](#spring-app-filled-in-by-slices) |

Never describe a derived spec as observed behavior. When you report results, say which kind of evidence backs each claim.

## Launch

For the legacy suite, "launch" means compiling the batch programs, and the harness does that itself. The Spring app has its own launch under [Spring app](#spring-app-filled-in-by-slices).

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
- Re-bless after a deliberate legacy change: `--update`, then review `git diff characterization/batch/golden characterization/batch/golden-fixed` line by line.

The harness then runs the whole cycle a second time with `FIXES` (in `harness.py`) patched into the COBOL source: each is a bug the Java port fixes on purpose, written as the smallest source edit with a one-sentence reason. `characterization/batch/golden-fixed/` holds every artifact those fixes change, and only those; `STALE` means a golden-fixed file the fixes no longer change. The tally reads `45/45 artifacts match golden (17 of them golden-fixed)`. `MNTTRDB2` (`COBTUPDT`) is the extra pair. GnuCOBOL cannot precompile its `EXEC SQL`, so the harness compiles a copy whose three statements call `stubs/TRTYPE.cbl`. That stub keeps `TRANSACTION_TYPE` in the `TRANTYPE` indexed file and returns SQLCODE 0, 100, -803, or -532 when the type still has a category.

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

Slice 1 made the app runnable. Later slices add their programs to the replay and their routes to the drives below. Claim Java parity only for programs that `[spec-replay]` lists.

### Launch

1. Once per machine, the doctor's JDK 21 and docker checks must pass. If `repo.maven.apache.org` is blocked, put this in `~/.gradle/init.d/mirror.gradle.kts` (machine-local, never committed):

   ```kotlin
   val mirror = "https://repo1.maven.org/maven2/"
   fun RepositoryHandler.useMirror() = all {
       if (this is MavenArtifactRepository && (url.toString().contains("repo.maven.apache.org") || url.host == "plugins.gradle.org")) setUrl(mirror)
   }
   settingsEvaluated { pluginManagement.repositories.useMirror() }
   allprojects { repositories.useMirror(); buildscript.repositories.useMirror() }
   ```

2. `scripts/spring.sh up [port]` (default 8080). It starts a dedicated `postgres:16-alpine` container `carddemo-pg-<port>` on a random loopback port, builds the boot jar, and runs it with `--carddemo.seed.dir=app/data/EBCDIC`. Flyway applies V1 to V4 and V6 (V3 is the Spring Batch job repository, V4 the POSTTRAN outcomes, V6 the transaction-type foreign key). The seed loads 10 users with bcrypt passwords on every start, and then runs the `POSTTRAN` job on the EBCDIC daily feed, so each launch begins from the fixtures as POSTTRAN leaves them (277 transactions, 23 rejects). The batch schedule is off unless `--carddemo.batch.schedule.enabled=true`. Job SYSOUT and datasets go to `/tmp/carddemo-spring/<port>/spool/<job>/<execution id>/`. Ready means `/actuator/health` says `UP`, which the script waits for (about 10 s). The log is `/tmp/carddemo-spring/<port>/app.log`.
3. `scripts/spring.sh status [port]` prints pid, health, build info, and schema version. `scripts/spring.sh down [port]` stops the app and removes its container, keeping the log.

Two instances can run side by side on different ports, because each has its own database and in-memory sessions. Browse each at `http://localhost:<port>`. The OIDC issuer and redirect URI are built from that URL, so another host name loses the sign-on cookies.

### Doctor

`scripts/doctor.sh` also checks JDK 21, docker, and Maven reachability. When `spring.sh` has an instance on `$CARDDEMO_PORT` (default 8080), it checks that the instance's pid owns the port, health is UP, the schema is at the newest `V*__*.sql`, and `/actuator/info` reports the current `git describe` (restart after rebuilding).

### Drive

- **Spec replay (executed).** `cd carddemo-java && ./gradlew test`. `OnlineSpecReplayTest` replays every case of each installed program's spec through `/api/screens` against Testcontainers Postgres. `SignonReplayTest` replays COSGN00C over real HTTP through the OIDC flow. `MessageSpecReplayTest` replays every `entry: trigger` case through the embedded broker. The output has one `[spec-replay] <PROGRAM>: N passed, M overridden, K skipped` line per program, with the case ids. A deliberate difference lives in `carddemo-java/src/test/resources/spec-overrides/<PROGRAM>.yaml` with a one-sentence `reason`, and the spec's `notes` point there.
- **Batch golden comparison (executed).** `BatchGoldenTest` runs the Java batch jobs in the harness's order, on `app/data/ASCII` and a clock fixed at 2022-07-06 10:00, and prints one `[batch-golden] <JOB>/<artifact> <OUTCOME>` line per golden artifact and a tally. `MATCHED` equals `golden/`, `FIXED` equals `golden-fixed/`, `OVERRIDDEN` needed an entry in `carddemo-java/src/test/resources/batch-overrides.yaml` (each with a one-sentence `reason`; the test fails on an unused one), and `NOT_PORTED` is VALIDTRAN (`CBTRN01C`, never scheduled). `MNTTRDB2` is compared the same way and is not in the daily or monthly chains. `DailyPostingTest` and `BatchChainTest` pin reruns, locking, chain order, restart, and the CBPAUP0J no-op.
- **HTTP drive (executed).** `python3 scripts/drive_spring.py --base http://localhost:8080 --out DIR` signs on through the OIDC flow with a cookie jar, then walks the admin menu, user add, sign-on as the new user, update, delete, sign-off, the account and card screens, transactions, and a concurrent bill payment through `/api/screens`. It also checks the bcrypt column in the instance's database. It prints one PASS line per check and exits non-zero on the first FAIL. For curl, follow the dance in `carddemo-java/README.md`.
- **Browser drive (executed).** `python3 scripts/browser_drive.py --base http://localhost:8080 --out DIR` drives Chrome with ENTER and the F-keys, finding elements by `data-testid` named after BMS fields (`USERID`, `PASSWD`, `OPTION`, `USRID01`, `ERRMSG`). It saves one screenshot per screen. It needs `pip install --user --break-system-packages playwright` and Chrome at `$CHROME` (default `/usr/local/bin/google-chrome`). No Playwright browser download is needed.

### Evidence

`verify.sh [DIR]` adds these files to the batch and online evidence:

- `spring-test.log` holds the full Gradle test run, `spec-replay.txt` holds the per-program tallies, and `batch-golden.txt` the per-artifact batch outcomes.
- `spring-status.txt`, `drive/` (`http-transcript.json` and `drive.txt`), and `browser/` (`NN-<screen>.png` and `browser.txt`) appear only when `spring.sh` has an instance up.
- `summary.txt` gains `spring` and `drive` lines.

The HTTP transcript never records a request body that carries a password. An online Java claim cites the spec case id and says whether it passed as written or under a named override.

## Feature map

Start at [`features/README.md`](features/README.md). Each feature file says how to drive it now, and what the Spring slice must add.
