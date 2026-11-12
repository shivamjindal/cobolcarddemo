# carddemo-java

Spring Boot 3 / Java 21 port of CardDemo. This module currently holds the data layer: Postgres tables for the VSAM KSDS clusters, derived from the copybooks in `../app/cpy`, and a loader for the sample data in `../app/data`.

## Commands

```bash
./gradlew test                      # unit tests + Testcontainers round-trip (needs Docker)
./gradlew generateDdl               # rewrite V1__vsam_tables.sql from the copybooks
docker compose up -d                # local Postgres on :5432 (carddemo/carddemo)
./gradlew bootRun --args='--carddemo.seed.dir=../app/data/EBCDIC'
./gradlew bootRun --args='--carddemo.seed.dir=../app/data/ASCII --carddemo.seed.format=ASCII'
```

`DdlGeneratorTest` fails when the committed migration drifts from the copybooks.

## Layout

- `copybook/` parses a single-record copybook into fields with byte offsets.
- `vsam/VsamDataset` lists each KSDS with its copybook, sample files, LRECL, primary key and alternate index byte ranges (from `app/catlg/LISTCAT.txt` and the `DEFINE ALTERNATEINDEX` steps in `app/jcl`).
- `vsam/Table` and `vsam/Column` turn a dataset into DDL and decode records into row values.
- `vsam/SeedLoader` truncates the tables and reloads them in one transaction.

## Mapping rules

| COBOL | Postgres |
| --- | --- |
| `PIC X(n)` | `varchar(n) NOT NULL`, trailing spaces trimmed |
| `PIC 9(n)` | `smallint` / `integer` / `bigint` by width, with a `CHECK` for the PIC range |
| `PIC S9(p)V9(s)` (DISPLAY or COMP-3) | `numeric(p+s, s)`; unsigned adds `CHECK (>= 0)` |
| `PIC X(10)` dates listed in `VsamDataset` | `date`, blank becomes `NULL` |
| `PIC X(26)` transaction timestamps | `timestamp(6)`, accepts `yyyy-MM-dd HH:mm:ss.ffffff` and DB2 `yyyy-MM-dd-HH.mm.ss.ffffff` |
| VSAM key / AIX | `PRIMARY KEY` / non-unique index |
| `FILLER` | dropped |

Column names are the COBOL names in snake case, typos included (`acct_expiraion_date`), so they stay greppable against the legacy source.
