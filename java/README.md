# CardDemo VSAM tables

Postgres tables for the ten CardDemo KSDS files. The SQL is generated from the copybooks, LISTCAT, and the EBCDIC sample data.

## Regenerate the migration

From this directory, pass the CardDemo app root:

```
mvn -q exec:java -Dexec.mainClass=com.carddemo.vsam.SchemaGenerator -Dexec.args=/workspace/app
```

The command writes `src/main/resources/db/migration/V1__vsam_ksds_tables.sql` and `docs/schema-mapping.md`.

## Load the sample data

Postgres is database `carddemo` on `localhost:5432`, user `carddemo`, password `carddemo`. Override the connection with `CARDDEMO_JDBC_URL`, `CARDDEMO_JDBC_USER`, and `CARDDEMO_JDBC_PASSWORD`.

```
mvn -q spring-boot:run -Dspring-boot.run.arguments=--carddemo.load.app-root=/workspace/app
```

Flyway applies the migration on startup. The loader then replaces every table in one transaction and checks each row against the EBCDIC file. A field mismatch or a count mismatch makes the process exit non-zero. Filler bytes that are not spaces are printed and do not fail the check.

## Test

```
mvn -q verify
```
