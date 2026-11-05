# CardDemo VSAM tables

Postgres tables for the ten CardDemo KSDS files. The SQL is generated from the copybooks, LISTCAT, and the EBCDIC sample data.

## Regenerate the migration

From this directory, pass the CardDemo app root:

```
mvn -q exec:java -Dexec.mainClass=com.carddemo.vsam.SchemaGenerator -Dexec.args=/workspace/app
```

The command writes `src/main/resources/db/migration/V1__vsam_ksds_tables.sql` and `docs/schema-mapping.md`.

`V2__legacy_record_images.sql` is written by hand. COACTUPC and COCRDUPC rewrite bytes V1's typed columns cannot hold, so in `accounts.acct_addr_zip`, `accounts.acct_group_id` and the new `acct_filler`, `cust_filler` and `card_filler` columns NULL means all LOW-VALUES, and in `cards.card_cvv_cd` NULL means spaces. A slice that reads these columns must treat NULL that way. `RoundTripCheck` encodes only the loaded data and does not accept those NULLs.

`V3__online_transactions.sql` is written by hand too. COTRN02C writes a bare date into TRAN-ORIG-TS, so `transactions.tran_orig_ts` is text like `tran_proc_ts`, and the loader writes the 26-character timestamp there. COBIL00C writes LOW-VALUES into TRAN-CARD-NUM when an account has no card cross-reference, so NULL in `transactions.tran_card_num` means all LOW-VALUES. The foreign key from a transaction to its category is gone, because COTRN02C checks only that the codes are numeric. Table `job_submissions` holds one row per job a program writes to a TD queue, as CORPT00C does to `JOBS`. The internal reader runs those jobs (see [Batch](#batch)).

`V4__batch.sql` and `V5__tcatbalf_filler.sql` are written by hand for the batch layer. They are described under [Batch](#batch).

`V6__db2_transaction_types.sql` and `V7__installed_extensions.sql` are written by hand for the optional Db2 transaction-type extension (`app/app-transaction-type-db2`). They are described under [Db2 transaction types](#db2-transaction-types).
`V20__pending_authorizations.sql`, `V21__td_queues_and_mq_triggers.sql` and `V22__install_authorization_extensions.sql` are written by hand for the pending-authorization and VSAM/MQ extensions, and are described under [Pending authorizations and MQ](#pending-authorizations-and-mq).

## Regenerate the screen maps

```
mvn -q exec:java -Dexec.mainClass=com.carddemo.bms.ScreenMapGenerator -Dexec.args=/workspace/app
```

The command writes one field enum per BMS map of `app/bms` and `app/app-transaction-type-db2/bms` into `src/main/java/com/carddemo/online/maps`. `ScreenMapsDriftTest` fails when a checked-in enum differs from the BMS source.

## Load the sample data

Postgres is database `carddemo` on `localhost:5432`, user `carddemo`, password `carddemo`. Override the connection with `CARDDEMO_JDBC_URL`, `CARDDEMO_JDBC_USER`, and `CARDDEMO_JDBC_PASSWORD`.

```
mvn -q spring-boot:run -Dspring-boot.run.profiles=load -Dspring-boot.run.arguments=--carddemo.load.app-root=/workspace/app
```

Flyway applies the migration on startup. The loader then replaces every table in one transaction and checks each row against the EBCDIC file. It also loads IMS database DBPAUTP0 from its DFSURGU0 unload in `app/app-authorization-ims-db2-mq/data/EBCDIC` and checks the root and dependent counts. A field mismatch or a count mismatch makes the process exit non-zero. Filler bytes that are not spaces are printed and do not fail the check. The `load` profile runs no web server.

## Run the web app

```
mvn -q spring-boot:run
```

The API listens on port 8080 (`CARDDEMO_PORT` overrides it) against the same database. Load the sample data once first. A browser client must reach the API from its own origin, for example through a dev-server proxy of `/api` to port 8080, because the cookies are `SameSite=Strict` and the content security policy is `default-src 'self'`.

## HTTP API

Each request is one CICS task. The server keeps the conversation, the terminal and the COMMAREA in the session, and every reply is the screen the terminal now shows.

| Route | Body | Answer |
| --- | --- | --- |
| `GET /api/session` | none | the reply on screen, or 404 with no conversation |
| `POST /api/session` | `{"transid":"CC00"}` | signs off and starts that transaction |
| `POST /api/screens/{screen}/{action}` | `{"turn":n,"fields":{...}}` | the next reply |
| `GET /api/maps/{name}` | none | the BMS layout of a map: its fields and literals with row, column, length, attributes, initial, color and highlight |

A reply has a `kind`.

- `screen` has `turn`, `screen` (the route name), `map` (`mapset`, `name`), `fields` (every field whose shown value is not blank), `colors`, `protectedFields`, `hiddenFields` (BMS DRK), `cursor` and `actions`.
- `text` has `turn`, `screen`, `text` and `actions`.
- `ended` has `turn` and `text`. The conversation is over and the session is signed off. A task that abends ends this way, with the CICS `DFHAC2206` message and the code in `abend`, and its writes are rolled back.

`actions` lists each action's `name` and `key`. A key that no action binds goes to action `invalid-key` with `"key":"PF9"` in the body, so the program answers it. `fields` holds only the fields the user changed since the last reply, by name, because a sent field counts as modified the way a 3270 sets its MDT. `""` erases a field. Typing into a protected or unknown field, past a field's length, or with a character below blank or outside Latin-1 is a 400. A `turn` or route that is not the one on screen is a 409 whose body is the reply on screen.

Every POST needs header `X-XSRF-TOKEN` set to the value of cookie `XSRF-TOKEN`, which the first response sets and scripts can read. A POST without it is a 403. Sign-on changes the session id and the token, so read the cookie before each POST. A route the signed-on user may not use is a 401 before sign-on and a 403 after.

## Test

```
mvn -q verify
```

The Spring tests run on database `carddemo_test` (`CARDDEMO_TEST_DB` overrides it), which they create on first use, so `verify` leaves the dev data alone. `HttpJourneyIT` drives one admin journey over real HTTP with cookies and the CSRF header. `BatchGoldensIT` runs every batch golden under `characterization/cases` (each suite of kind `batch`) through the internal reader. `LegacyDefectRegisterTest` checks every `@LegacyDefect` against the recorded goldens and writes `docs/legacy-defects.md`.

## Run the characterization goldens

From the repository root:

```
python3 characterization/run.py --target java
```

The command prints `java: 457 PASS`: 411 online cases and 46 batch cases. The features the java target covers are listed in `characterization/targets.json`.

`characterization-adapter` rebuilds the jar when a source is newer than it. It keeps one server on `127.0.0.1:18080` under profile `characterization`, against database `carddemo_characterization` and a clock fixed at 2026-11-05 10:00. Before every case it seeds the ten KSDS tables, the two Db2 tables, the IMS segments `PAUTSUM0` and `PAUTDTL1` and the Db2 table `AUTHFRDS` from the harness seeds named in `characterization/fixtures/datasets.json`, empties the job queue, the spool, the dataset catalog and the MQ queues, opens every CICS file, and installs the extensions the case's `install` lists (none by default). A Db2 row is seeded, snapshotted and diffed as the harness's image: the key, a two-digit length, then the 50-byte description. It does all of that in one transaction with foreign-key triggers off, because a case may delete a record others refer to. A step's new `job_submissions` rows are its `queues` observation. A batch case is submitted as a one-step deck to queue `JOBS`, and the server's internal reader runs it. An online `mq` step posts the case's messages to `/characterization/mq`, an endpoint only that profile has, which puts them and runs the trigger transid once; the adapter then reports what the programs put, on any queue, and how many of the case's messages are still unread. `CARDDEMO_CHARACTERIZATION_PORT` and `CARDDEMO_CHARACTERIZATION_DB` give a second checkout its own server and database. The server stops after ten idle minutes, or with `characterization/cleanup.sh stop`.

## Pending authorizations and MQ

The optional extensions `pending-authorizations` (`app/app-authorization-ims-db2-mq`) and `vsam-mq` (`app/app-vsam-mq`) run against three packages.

| Package | What it holds |
| --- | --- |
| `cics` | `Extension.PENDING_AUTHORIZATIONS` (COPAUS0C, COPAUS1C, COPAUS2C, COPAUA0C; CPVS, CPVD, CP00) and `Extension.VSAM_MQ` (COACCT01, CODATE01; CDRA, CDRD), installed by default (V22) and switched as described under [Install switch](#install-switch). `Syncpoint` and `TransientData`, described below. |
| `dli` | IMS database DBPAUTP0. `Dbpautp0.schedule()` gives a task its `Pcb`, which keeps position, parentage and the held segment and answers GU, GN, GNP, REPL, DLET and ISRT with `DliStatus` codes. `PendingAuthSummary` and `PendingAuthDetail` are the segments, typed and with their 100- and 200-byte images. |
| `mq` | Port `MessageQueues` (open, get, put, put1, close, MQRC as `Reason`) and its adapter `JdbcMessageQueues`. A `TriggeredProgram` bean answers a transid; `MqTriggerMonitor` starts it when a put to a queue in `mq_queues` commits. |

`V20__pending_authorizations.sql` adds these tables:

- `pending_auth_summaries` and `pending_auth_details`: one row per PAUTSUM0 root and PAUTDTL1 dependent, a column per field. A detail's key is its parent's `acct_id` with `auth_date_9c` and `auth_time_9c`, and deleting a summary deletes its details, as DLET of a root does. NULL in a text column means all LOW-VALUES, which the unload holds. One unload root holds spaces in its packed and binary fields: a packed sign nibble other than D reads as positive and is written back as the picture's sign, so that root's image changes in its sign nibbles only.
- `authfrds`: Db2 table CARDDEMO.AUTHFRDS per its DDL.
- `mq_queues` and `mq_messages`: trigger definitions, and the messages on every queue in put order. Any queue name is defined.

DL/I follows EXEC DLI, not CBLTDLI:

- Every get holds the segment it returns; there is no GHU.
- REPL and DLET are DJ unless the call just before them was a successful get of that segment type, and REPL that changes the key is DA.
- A failed GU or GN cancels parentage, so the next GNP is GP. GN past the end is GB and starts over.
- ISRT of a key already there is II, and under a missing parent it is GE.

A PCB's calls run in the caller's transaction and lock what they read. An MQGET or MQPUT with syncpoint joins that transaction, so SYNCPOINT ROLLBACK or an abend puts a message back. Without syncpoint it commits at once. An MQGET waits up to its wait interval, capped by `carddemo.mq.max-wait` (zero under profile `characterization`).

`Syncpoint` is EXEC CICS SYNCPOINT inside a task's one transaction: it commits the work so far and the task goes on. `TransientData` is WRITEQ TD to an extrapartition queue such as CSSL, held in `td_queue_records` (V21) and committed at once, as a non-recoverable queue is.

The triggered programs are `TriggeredProgram` beans. `V21__td_queues_and_mq_triggers.sql` defines their production triggers, each of type FIRST.

| Queue | Transid | Bean | Replies on |
| --- | --- | --- | --- |
| `AWS.M2.CARDDEMO.PAUTH.REQUEST` | CP00 | `authorization.AuthorizationProcessor` (COPAUA0C) | the request's reply-to queue |
| `CARDDEMO.REQUEST.QUEUE` | CDRA | `vsammq.AccountInquiry` (COACCT01) | `CARD.DEMO.REPLY.ACCT` |
| `CARDDEMO.REQUEST.DATE` | CDRD | `vsammq.SystemDateInquiry` (CODATE01) | `CARD.DEMO.REPLY.DATE` |

The source names no queue for CODATE01, so `CARDDEMO.REQUEST.DATE` is this port's choice. COPAUA0C logs its warnings and failures to CSSL. COACCT01 and CODATE01 put their failures on `CARD.DEMO.ERROR`.

An administrator reaches the queues over HTTP. Any other caller gets a 401 before sign-on and a 403 after.

| Route | Body | Answer |
| --- | --- | --- |
| `POST /api/mq/queues/{queue}/messages` | `{"data":"...","correlId":"...","replyTo":"..."}` | 201: MQPUT1 without syncpoint, so the queue's trigger starts its transid at once |
| `GET /api/mq/queues/{queue}/messages` | none | every message on the queue in put order, none taken: `msgId`, `correlId`, `replyTo`, `data` |
| `POST /api/mq/queues/{queue}/get` | none | the oldest message, taken off the queue, or 204 when it is empty |

Text is latin-1, up to 4096 characters. Ids are text padded with spaces to 24 bytes and come back without trailing spaces or low-values. The web client's `/mq` page sends an authorization request to `AWS.M2.CARDDEMO.PAUTH.REQUEST` and shows `AWS.M2.CARDDEMO.PAUTH.REPLY`; sign on as an admin user on `/` first.

## Batch

The ten COBOL batch programs and the four IMS batch programs run as Spring Batch jobs against the same tables as the web app. Each JCL step is one tasklet step that runs outside a transaction, so what a step wrote before it abended stays written, as with VSAM. The Db2 program COBTUPDT is the exception: its step is one Db2 unit of work, committed when it ends and rolled back when it abends. The 46 batch goldens pin their behavior.

### Packages

Under `src/main/java/com/carddemo`:

| Package | What it holds |
| --- | --- |
| `batch.step` | The program interface: `BatchProgram` (`name()`, `int run(Step)`), `Step` (PARM, clock, DISPLAY and the DDs), `KeyedDataset`, `SequentialInput`, `SequentialOutput`, `FileStatus`, `Read`, `Abend`. No SQL or Spring Batch types. |
| `batch.programs` | CBACT01C, CBACT02C, CBACT03C, CBACT04C, CBCUS01C, CBSTM03A (with its CBSTM03B collaborator), CBTRN02C, CBTRN03C, COBTUPDT, the IMS programs CBPAUP0C, PAUDBUNL, DBUNLDGS and PAUDBLOD (`DliProgram`s), and the system programs IEFBR14, SDSF, COBSWAIT and IKJEFT01. |
| `batch.ims` | `Dfsrrc00`, the IMS region controller, and the `DliProgram` and `ImsRegion` interfaces it runs programs through. |
| `batch.utilities` | SORT and IDCAMS for the control statements the library jobs code. Any other statement ends RC 12. |
| `batch.jcl` | Typed JCL, and `JclDeck`, the parser for submitted decks. |
| `batch.library` | `LibraryJob`, one entry per `app/jcl` or extension `jcl` member that runs here, and `Proc`, one per `app/proc` member (REPROC, TRANREPT). |
| `batch.runtime` | `BatchRuntime.run(JobDef, RunIdentity)`, DD allocation and DISP, generations, the dataset store and the spool. |
| `batch.drivers` | `InternalReader`, which runs the decks on TD queue `JOBS`. |
| `batch.chains` | `Chains`, `ChainRunner` and `ChainCalendars`. |
| `cics` | `CicsRegion` and `CicsFile`, the gate CLOSEFIL and OPENFIL drive, and `Extension`, the optional CSD groups the region installs. |
| `db2` | `Db2`, embedded SQL that answers an SQLCODE, and `Dsntiac`, the SQLCA message formatter. |
| `jobs` | `JobSubmissions`, which programs append to, and `JobQueue`, the reader's claims. |
| `operator` | The `operator` profile command line. |

### Schema

`V4__batch.sql` adds these tables and changes:

- The Spring Batch 5.2.6 job repository. Boot does not create it (`spring.batch.jdbc.initialize-schema=never`).
- `datasets` and `dataset_records` hold sequential data sets and GDG generations, one record image per row. A V record is stored without its RDW. The ten V1 tables are the KSDS clusters. A keyed work file that is not one of them (CREASTMT's TRXFL) is a `datasets` row with its key.
- `spool` holds SYSOUT=* data sets and DISPLAY lines by job execution, step and DD.
- `job_submission_runs` holds the reader's claim on a `job_submissions` row. Rows that predate V4 get a `NOT RUN` claim and never run.
- `cics_region` is one row listing the closed CICS files.
- `job_submissions.procedure`, `start_date` and `end_date` become nullable, because only CORPT00C's decks carry them. `accounts.acct_reissue_date` becomes nullable, and NULL means spaces.
- Five foreign keys are dropped. VSAM has none, and batch posts balances and transactions that they would reject.

`V5__tcatbalf_filler.sql` adds `transaction_category_balances.tran_cat_filler`, because CBACT04C DISPLAYs the record with its filler.

### How a job is represented

A `JobDef` is a job name and its `StepDef`s, with procedures expanded. A `StepDef` has a path (`STEP10.STEP05R` for a procedure step), a program name, a PARM, a `Cond` and its `Dd`s. A `Dd` is a name and a concatenation of `DdSource`s: `Dataset`, `Instream`, `Sysout` or `Dummy`. A `Dataset` has a `DsnRef`, a `Disposition` and an optional `Dcb` (RECFM F or V and LRECL). A `DsnRef` is either `Named` or a `Generation` of a `Gdg` relative to the job, such as `(+1)` or `(0)`. `Gdg` lists the bases with their LISTCAT LIMIT and SCRATCH.

When a job runs:

- `(0)` and older generations resolve against the catalog as it stood when the job started. The first `(+1)` of a base creates the job's new generation, and every later `(+1)` of that base in the same job names it. `(+2)` and above are a JCL error.
- DISP applies at step end. The abnormal disposition applies when the step abends.
- `COND=(code,op[,step])` bypasses the step when `code op RC` holds for an earlier step that ended with a return code. EVEN and ONLY are not supported.
- A step ends as `RC=n`, an abend code, `JCL ERROR` (a DD cannot be allocated), `BYPASSED` (COND held) or `FLUSH` (an earlier step abended). A program with no bean abends `S806`. An unexpected exception abends `U4038`. The job ends with its first abend or JCL error, or else its highest return code.
- `JclDeck` ignores STEPLIB and JOBLIB, because programs are beans. An override DD replaces the procedure's DD whole.

### From report submit to a TRANREPT run

CORPT00C (`ReportSubmitScreen`) appends the `ReportJcl` cards to `job_submissions` on queue `JOBS`. The deck is job `TRNRPT00` with `EXEC PROC=TRANREPT`, and its `STEP05R.SYMNAMES` and `STEP10R.DATEPARM` overrides carry the dates.

`InternalReader` polls every `carddemo.batch.reader.poll` (5 seconds by default). It claims the oldest unclaimed row by inserting into `job_submission_runs`, parses the deck against `Proc` and runs it. A Postgres advisory lock lets one reader run at a time across every JVM on the database. The claim ends with the job's completion label and its Spring Batch job execution id. A deck that does not parse ends `JCL ERROR`. The reader runs when `carddemo.batch.drivers.enabled` is true, which is the default. The `load` and `operator` profiles turn it off.

The TRANREPT procedure copies the transaction master to `AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)`, then sorts and date-filters that copy into `AWS.M2.CARDDEMO.TRANSACT.DALY(+1)`. CBTRN03C then writes the 133-byte report to `AWS.M2.CARDDEMO.TRANREPT(+1)`. To read the newest report, run operator `print 'AWS.M2.CARDDEMO.TRANREPT(0)'`. To see the run's step codes and spool, run operator `output <submission id>`.

### IMS jobs

CBPAUP0J, UNLDPADB, LOADPADB and UNLDGSAM run `EXEC PGM=DFSRRC00,PARM='BMP,program,psb'` (or `DLI,...`). `Dfsrrc00` finds the `DliProgram` bean of that name, checks its PSB and schedules a `Pcb` on DBPAUTP0. A name with no bean abends `S806`, and `EXEC PGM=` does not find a `DliProgram` either. The IMS system DDs and STEPLIB are left out of the library jobs.

Unlike other steps, an IMS step runs in a transaction. CHKP (`ImsRegion.checkpoint()`) commits it and starts the next, and the program's return commits it, whatever the return code. An abend backs out to the last checkpoint. So CBPAUP0C's RC 16 after the summary DLET's DJ keeps the details it deleted.

- CBPAUP0J purges expired details with SYSIN `00,00001,00001,Y`.
- UNLDPADB deletes the last unload in STEP0, with DISP=OLD, so it ends `JCL ERROR` when there is none. It then writes roots to `AWS.M2.CARDDEMO.PAUTDB.ROOT.FILEO` (100 bytes) and each child, after its 6-byte root key, to `...CHILD.FILEO` (206 bytes).
- LOADPADB inserts those two files back.
- UNLDGSAM writes the GSAM data sets `...ROOT.GSAM` and `...CHILD.GSAM`, which must already be cataloged. Its child records have no root key.

### CLOSEFIL and OPENFIL

Every online file request asks `CicsRegion.isOpen`, which reads the `cics_region` row `FOR SHARE` and holds the lock until the request's transaction commits. A closed file answers RESP NOTOPEN, and each program takes its own file-error path. For example, sign-on shows `Unable to verify the User ...` while USRSEC is closed. `CicsRegion.close` updates the row, so it waits for every in-flight online task that has used a file, as CEMT does before it closes one. Batch programs never ask the gate. CLOSEFIL is what keeps them apart from online tasks.

The CLOSEFIL and OPENFIL jobs run SDSF on the JCL's ISFIN cards, such as `/F CICSAWSA,'CEMT SET FIL(ACCTDAT ) CLO'`. They close or open TRANSACT, CCXREF, ACCTDAT, CXACAIX and USRSEC. CARDDAT, CARDAIX and CUSTDAT stay open, as in the JCL. A card SDSF cannot apply changes nothing and ends the step RC 8. WAITSTEP runs COBSWAIT, which waits the 36 seconds its SYSIN card gives.

### Chains and calendars

`Chains` registers the eight chains of `app/scheduler`. Five can run, and each is CLOSEFIL, the business jobs, WAITSTEP and OPENFIL:

- `CA7_030_POSTING`: CBPAUP0J, then POSTTRAN.
- `CA7_030_EXTRACTS`: READACCT, READCARD, READCUST, READXREF.
- `CA7_030_STATEMENTS`: CREASTMT.
- `CONTROLM_DAILY_BACKUP`: TRANBKP.
- `CONTROLM_MONTHLY_INTEREST`: INTCALC, COMBTRAN.

`CA7_030_REFERENCE`, `CA7_031_CATBAL` and `CONTROLM_WEEKLY_DISCLOSURE` hold an `Unported` member (a reference-data reload or PRTCATBL) and cannot start. CBPAUP0J is an `ExtensionJob`: it runs when extension `pending-authorizations` is installed and is recorded as bypassed when it is not. On the sample data it ends RC 16 (the summary DLET's DJ), which stops the chain with the files closed. The Db2 and REXX jobs not ported (TRANEXTR, TXT2PDF1) are `Optional` members. They are recorded as bypassed, and the chain goes on.

`ChainRunner` runs a chain as one Spring Batch job named after the chain, identified by its order date, with one step per member. Each member runs as its own JCL job, so COND and generations stay inside that member. A member that ends above RC 4, abends or ends in a JCL error stops the chain. A chain stopped between CLOSEFIL and OPENFIL leaves those files closed until it is restarted or an operator opens them. `start` runs a chain once per order date. `restart` reruns a failed chain from the member that failed and skips the members that ended OK. `abandon` records a run whose JVM died as FAILED, so `restart` accepts it.

`ChainCalendars` installs a calendar only when property `carddemo.batch.calendar.<CHAIN>` holds a Spring cron, for example `carddemo.batch.calendar.CONTROLM_DAILY_BACKUP=0 0 22 * * *`. Each fire starts the chain for that day's order date. Startup fails if the property names no chain, names a chain that cannot run, or holds a bad cron.

No calendar is installed by default, because the two scheduler exports conflict. Control-M's daily folder empties TRANSACT every day with TRANBKP, CA-7 posts into TRANSACT with POSTTRAN, and no condition orders the two. Neither export gives a cron either. CA-7 has queue times and no days, and Control-M's monthly folder names no day of the month. A nightly POSTTRAN would also close the online files and leave them closed on any night `DALYTRAN.PS` is missing, because the missing DD ends POSTTRAN `JCL ERROR`. Each chain keeps its export calendar as text, and `operator chains` prints it.

### Operator commands

Profile `operator` runs one command and exits, with no web server and no internal reader. Build the jar with `mvn -q -DskipTests package` first. The command uses the same database settings as the web app.

```
java -jar target/carddemo-0.1.0.jar --spring.profiles.active=operator chains
java -jar target/carddemo-0.1.0.jar --spring.profiles.active=operator receive AWS.M2.CARDDEMO.DALYTRAN.PS 350 ../app/data/ASCII/dailytran.txt
java -jar target/carddemo-0.1.0.jar --spring.profiles.active=operator start CA7_030_POSTING 2026-11-05
java -jar target/carddemo-0.1.0.jar --spring.profiles.active=operator output 42
java -jar target/carddemo-0.1.0.jar --spring.profiles.active=operator print 'AWS.M2.CARDDEMO.TRANREPT(0)'
java -jar target/carddemo-0.1.0.jar --spring.profiles.active=operator cemt open ACCTDAT
```

| Command | What it does |
| --- | --- |
| `output <submissionId>` | Prints a submission's completion, each step's exit code, and its spool by step and DD. |
| `print <dsn>` | Prints a cataloged data set, a cluster, or a generation such as `BASE(0)` or `BASE(-1)`. |
| `cemt open\|close <file>` | Opens or closes a `CicsFile`. A close waits for in-flight online tasks. |
| `start <chain> [orderDate]` | Runs the chain to its end in this process. The order date defaults to today. |
| `restart <chain> <orderDate>` | Reruns a failed chain from the member that failed. |
| `abandon <chain> <orderDate>` | Records a dead STARTED run as FAILED so `restart` accepts it. |
| `chains` | Lists every chain, whether it can run, and its export calendar. |
| `receive <dsn> <lrecl> <file>` | Catalogs a text file as fixed-length records, one per line, replacing the data set of that name. |

The exit code is 0 when the command did its work. It is 4 when a chain did not complete, a request was refused, or a submission or data set was not found. It is 8 for bad arguments. A chain run from the command line really waits 36 seconds at WAITSTEP.

### Add a batch program

1. Write one class in `batch.programs`: a `@Component` that implements `BatchProgram`, with `public static final String NAME` as its `name()`. `Cbact02c` is the smallest model. An IMS program implements `DliProgram` instead, with its PSB, as `Paudblod` does. Use `Step.keyed`, `Step.input` and `Step.output` for files, never the online stores, and throw `Abend` for an abend.
2. Add one `LibraryJob` entry that mirrors the `app/jcl` member, and add the job to a chain in `Chains` if a scheduler runs it.
3. Put each pinned legacy defect in its own method annotated `@LegacyDefect`, then regenerate `docs/legacy-defects.md` with `mvn verify`.

`BatchGoldensIT` picks up every case of a `batch` suite under `characterization/cases` with no change. When the program's cases pass, add its feature to the java target in `characterization/targets.json`.

## Db2 transaction types

The optional extension `app/app-transaction-type-db2` keeps transaction types in Db2 tables. Its batch program COBTUPDT, its list screen COTRTLIC (CTLI) and its maintenance screen COTRTUPC (CTTU) are ported.

### Schema

`V6__db2_transaction_types.sql` creates schema `carddemo`, with the extension's DDL as Postgres tables, seeded from `DB2LTTYP` and `DB2LTCAT`:

- `carddemo.transaction_type`: `tr_type char(2)` key and `tr_description varchar(50)`.
- `carddemo.transaction_type_category`: `trc_type_code`, `trc_type_category` and `trc_cat_data`, keyed on the code and the category. Its foreign key to `transaction_type` is `ON DELETE RESTRICT`, so deleting a type that has categories answers SQLCODE -532.

These are separate from the VSAM-loaded `transaction_types` and `transaction_categories`, because the legacy system keeps two stores with different contents (the Db2 load cards are upper case and spell type 06 `REVERAL`; VSAM has `Reversal`) and VSAM sees Db2 only through the weekly TRANEXTR extract and reloads, which are not ported. Merging them would change what the base screens show after a Db2 edit.

The text columns collate `"C"`, so they sort and compare byte by byte, as the harness's tables do. V6 also creates `sysibm.sysdummy1`, which the extension's priming query selects from. `spring.flyway.schemas` lists `carddemo` and `sysibm`, so a Flyway clean drops them too.

`V7__installed_extensions.sql` adds `cics_region.installed`, the extensions installed in the region, with `transaction-type-db2` installed. V22 adds `pending-authorizations` and `vsam-mq`, so production starts with all three.

### Embedded SQL

`com.carddemo.db2.Db2` runs a program's SQL the way the precompiler's calls do. Each statement answers an `Sqlca` instead of throwing:

| Call | Answer |
| --- | --- |
| `unitOfWork(Supplier<T>)` | Runs the work in one transaction. It commits when the work returns and rolls back when it throws, such as an `Abend`. |
| `syncpoint()` | Commits inside the unit of work and closes every open cursor. |
| `execute(sql, hostVariables...)` | An INSERT, UPDATE or DELETE. |
| `selectInto(sql, rowMapper, hostVariables...)` | A `Fetched<T>`: the SQLCA and the first row. |
| `declare(name, select, rowMapper)` | A `Cursor<T>` with `open(hostVariables...)`, `fetch()` and `close()`. |

Every statement runs under its own savepoint in the caller's transaction. A failed statement rolls back to it and leaves the earlier statements of the unit of work in place, as in Db2. A statement outside a transaction throws `IllegalStateException`. Host variables bind untyped, so a `char` column compares blank-padded, and LOW-VALUES are sent as X'01' because Postgres text cannot hold X'00'.

`sqlcode` is 0, +100 when no row is found or an UPDATE or DELETE changes none, or the Db2 code for the Postgres SQLSTATE: 23505 is -803, 23503 is -532 for a DELETE and -530 otherwise, 22001 is -404, 23502 is -407 and 21000 is -811. A SELECT INTO that finds more than one row is -811 too, with the first row. Opening an open cursor is -502, and fetching or closing a closed one is -501. An SQLSTATE with no mapping is -9999 and is logged. `sqlerrmc` is the first line of the Postgres message, cut to 70 bytes. `Sqlca.sqlcodeEdited()` is the code as PIC `----9` shows it. `Dsntiac.format` writes the DSNT404I or DSNT408I lines the harness's DSNTIAC writes, with its return codes 8 for an LRECL outside 72 to 240 and 12 for a message area under 240 bytes.

### Install switch

`com.carddemo.cics.Extension` lists each optional CSD group and the programs it holds: `TRANSACTION_TYPE_DB2` (`transaction-type-db2`: COTRTLIC, COTRTUPC), `PENDING_AUTHORIZATIONS` (`pending-authorizations`: COPAUS0C, COPAUS1C, COPAUS2C, COPAUA0C) and `VSAM_MQ` (`vsam-mq`: COACCT01, CODATE01). `CicsRegion.installed()` reads the installed set and `CicsRegion.install(Set<Extension>)` replaces it, as CEDA INSTALL and DISCARD would. While an extension is not installed, its programs are not installed for `ProgramCatalog`: a transaction id of theirs is unknown, and an XCTL to one lands on the not-ported screen. A screen ported later becomes reachable only while its extension is installed. `MqTriggerMonitor` answers a trigger for an uninstalled extension's `TriggeredProgram` as an undefined transid, and a chain bypasses an `ExtensionJob` whose extension is not installed.

### MNTTRDB2

`LibraryJob.MNTTRDB2` mirrors the extension's JCL: IKJEFT01 reads SYSTSIN `DSN SYSTEM(DAZ1)` then `RUN PROGRAM(COBTUPDT) PLAN(CARDDEMO)` and runs COBTUPDT on its own step, against the `INPFILE` data set. DBRMLIB and STEPLIB are left out, because RUN binds nothing and programs are beans. Any other TSO command ends the step RC 12, and a program with no bean abends `S806`. `CONTROLM_WEEKLY_DISCLOSURE` runs MNTTRDB2 as a job.

COBTUPDT reads 53-byte records: the action (A, U, D, or * for a comment), the two-character type code and the description. An error DISPLAYs the message and ends the step RC 4. Its four `@LegacyDefect` methods pin the defects its goldens show.

### COTRTLIC

`trantype.TranTypeListScreen` is COTRTLIC, admin menu option 5 or transid CTLI: pages of seven types read through forward and backward cursors, type and description filters (the description is a SQL `LIKE`, with `ESCAPE ''` because Db2's LIKE has no escape character and Postgres defaults to backslash), `U` to edit a description in place and `D` to delete, both saved with F10, and F2 to COTRTUPC. A CTLI with no COMMAREA opens the list without sign-on, as the COBOL does; leaving it signs in the blank admin identity the COBOL builds. Its eleven `@LegacyDefect` methods are listed in `docs/legacy-defects.md`.

### COTRTUPC

`trantype.TransactionTypeMaintenanceScreen` is COTRTUPC, admin menu option 6: search a type by code, then change its description (ENTER, then F5), delete it (F4, then F4), or add a code that is not found (F5, ENTER, F5). Its `Task` follows 0000-MAIN paragraph by paragraph, and its `State` keeps TTUP-CHANGE-ACTION and the old and new details as PIC X bytes, with X'00' for LOW-VALUES, because the program compares them with SPACES and LOW-VALUES separately. All of its SQL stays in the class.

Its ABEND-ROUTINE sends ABEND-DATA and then abends 9999, so `Screen.sendTextAndAbend` puts that text on the terminal in place of the CICS abend message. `FieldAttribute.BMDAR` is the dark attribute it gives the ENTER/F3 legend during a delete confirmation. LOW-VALUES inside a sent value show as blanks, as the SQLERRML bytes in its SQL error messages do. `TransactionTypeMaintenanceIT` forces the SQL failures no golden reaches (a -911 lock, a failed UPDATE, INSERT or DELETE) with a trigger that raises the matching SQLSTATE. Its twelve `@LegacyDefect` methods are listed in `docs/legacy-defects.md`.
