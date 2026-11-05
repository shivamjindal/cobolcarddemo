# CardDemo VSAM tables

Postgres tables for the ten CardDemo KSDS files. The SQL is generated from the copybooks, LISTCAT, and the EBCDIC sample data.

## Regenerate the migration

From this directory, pass the CardDemo app root:

```
mvn -q exec:java -Dexec.mainClass=com.carddemo.vsam.SchemaGenerator -Dexec.args=/workspace/app
```

The command writes `src/main/resources/db/migration/V1__vsam_ksds_tables.sql` and `docs/schema-mapping.md`.

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

The API listens on port 8080 (`CARDDEMO_PORT` overrides it) against the same database. Load the sample data once first.

## Test

```
mvn -q verify
```

The Spring tests run on database `carddemo_test`, which they create on first use, so `verify` leaves the dev data alone.

## Run the characterization goldens

From the repository root:

```
python3 characterization/run.py --target java --feature signon
```

`characterization-adapter` rebuilds the jar when a source is newer than it. It keeps one server on `127.0.0.1:18080` under profile `characterization`, against database `carddemo_characterization` and a clock fixed at 2026-11-05 10:00. Before every case it seeds USRSEC from `characterization/fixtures`. The server stops after ten idle minutes, or with `characterization/cleanup.sh stop`.
