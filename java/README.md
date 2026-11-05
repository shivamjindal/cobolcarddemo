# CardDemo VSAM tables

Postgres tables for the ten CardDemo KSDS files. The SQL is generated from the copybooks, LISTCAT, and the EBCDIC sample data.

## Regenerate the migration

From this directory, pass the CardDemo app root:

```
mvn -q exec:java -Dexec.mainClass=com.carddemo.vsam.SchemaGenerator -Dexec.args=/workspace/app
```

The command writes `src/main/resources/db/migration/V1__vsam_ksds_tables.sql` and `docs/schema-mapping.md`.

`V2__legacy_record_images.sql` is written by hand. COACTUPC and COCRDUPC rewrite bytes V1's typed columns cannot hold, so in `accounts.acct_addr_zip`, `accounts.acct_group_id` and the new `acct_filler`, `cust_filler` and `card_filler` columns NULL means all LOW-VALUES, and in `cards.card_cvv_cd` NULL means spaces. A slice that reads these columns must treat NULL that way. `RoundTripCheck` encodes only the loaded data and does not accept those NULLs.

`V3__online_transactions.sql` is written by hand too. COTRN02C writes a bare date into TRAN-ORIG-TS, so `transactions.tran_orig_ts` is text like `tran_proc_ts`, and the loader writes the 26-character timestamp there. COBIL00C writes LOW-VALUES into TRAN-CARD-NUM when an account has no card cross-reference, so NULL in `transactions.tran_card_num` means all LOW-VALUES. The foreign key from a transaction to its category is gone, because COTRN02C checks only that the codes are numeric. Table `job_submissions` holds one row per job a program writes to a TD queue, as CORPT00C does to `JOBS`. Nothing runs those jobs yet.

## Regenerate the screen maps

```
mvn -q exec:java -Dexec.mainClass=com.carddemo.bms.ScreenMapGenerator -Dexec.args=/workspace/app
```

The command writes one field enum per BMS map into `src/main/java/com/carddemo/online/maps`. `ScreenMapsDriftTest` fails when a checked-in enum differs from the BMS source.

## Load the sample data

Postgres is database `carddemo` on `localhost:5432`, user `carddemo`, password `carddemo`. Override the connection with `CARDDEMO_JDBC_URL`, `CARDDEMO_JDBC_USER`, and `CARDDEMO_JDBC_PASSWORD`.

```
mvn -q spring-boot:run -Dspring-boot.run.profiles=load -Dspring-boot.run.arguments=--carddemo.load.app-root=/workspace/app
```

Flyway applies the migration on startup. The loader then replaces every table in one transaction and checks each row against the EBCDIC file. A field mismatch or a count mismatch makes the process exit non-zero. Filler bytes that are not spaces are printed and do not fail the check. The `load` profile runs no web server.

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

The Spring tests run on database `carddemo_test` (`CARDDEMO_TEST_DB` overrides it), which they create on first use, so `verify` leaves the dev data alone. `HttpJourneyIT` drives one admin journey over real HTTP with cookies and the CSRF header. `LegacyDefectRegisterTest` checks every `@LegacyDefect` against the recorded goldens and writes `docs/legacy-defects.md`.

## Run the characterization goldens

From the repository root:

```
python3 characterization/run.py --target java --feature signon --feature main-menu --feature admin-menu \
  --feature user-list --feature user-add --feature user-update --feature user-delete \
  --feature account-view --feature account-update --feature card-list --feature card-detail --feature card-update
```

The features the java target covers are listed in `characterization/targets.json`.

`characterization-adapter` rebuilds the jar when a source is newer than it. It keeps one server on `127.0.0.1:18080` under profile `characterization`, against database `carddemo_characterization` and a clock fixed at 2026-11-05 10:00. Before every case it seeds USRSEC, ACCTDATA, CUSTDATA, CARDDATA, CARDXREF and TRANSACT from the harness seeds named in `characterization/fixtures/datasets.json` and empties `job_submissions`, in one transaction with foreign-key triggers off, because a case may delete a record others refer to. A step's new `job_submissions` rows are its `queues` observation. `CARDDEMO_CHARACTERIZATION_PORT` and `CARDDEMO_CHARACTERIZATION_DB` give a second checkout its own server and database. The server stops after ten idle minutes, or with `characterization/cleanup.sh stop`.
