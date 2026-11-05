# Mainframe construct map and slice order

Scope: CardDemo core (`app/`, 31 COBOL programs, 17 BMS mapsets, 38 JCL jobs and 2 procs, 2 assembler utilities) plus the three optional modules.
Facts below come from `inventory.md` and the sources. Target stack: Java/Spring, Postgres, React.

## Construct map

| Mainframe construct | Where it appears | Target | Notes and risks |
|---|---|---|---|
| BMS mapset and symbolic map | `app/bms/*.bms` (17), `app/cpy-bms/*.CPY` | React page per mapset, plus a typed request/response DTO per map | Symbolic map copybooks give the field list and lengths: generate DTOs from them. Input field attributes (`-1` length to place the cursor, protected, bright, colour) become field error state and focus. Do not port screen layout literally |
| AID keys and PF keys | `DFHAID`, `CSSTRPFY`, `CCARD-AID-*` in `CVCRD01Y` | Buttons and keyboard shortcuts | ENTER, PF3 (back), PF7/PF8 (page up/down) cover most screens. CLEAR and PA keys are dropped |
| Pseudo-conversational loop | Every online program: `RETURN TRANSID COMMAREA` | Stateless REST endpoints | One program turn (receive map, validate, act, send map) becomes one request. `CDEMO-PGM-ENTER`/`REENTER` is client state, not server state |
| COMMAREA `CARDDEMO-COMMAREA` | `COCOM01Y` | Server session for identity; route and query parameters for context | Keep only user id and user type in the session (claims in the token). `CDEMO-CUST-ID`, `ACCT-ID`, `CARD-NUM`, `LAST-MAP` are navigation context and move to the URL. `CDEMO-FROM/TO-PROGRAM` disappear |
| `XCTL` between programs | `COMEN01C`/`COADM01C` menu tables (`COMEN02Y`, `COADM02Y`), `CDEMO-TO-PROGRAM` returns | Client-side routes. Menu served per role | Targets are mostly dynamic (the caller recorded in the COMMAREA). The static graph is in `inventory.md` |
| Transaction id to program (`CSD DEFINE TRANSACTION`) | `app/csd/CARDDEMO.CSD` | REST resource path | 18 core transids. `CDV1` points at `COCRDSEC`, which is not in the repo |
| CICS file I/O: `READ`, `STARTBR`/`READNEXT`/`READPREV`, `WRITE`, `REWRITE`, `DELETE` | Eight CICS files, all KSDS or AIX paths | Spring Data JPA or JDBC repositories | `STARTBR`+`READNEXT` list screens become keyset pagination on the PK. Order follows EBCDIC bytes: use `COLLATE "C"` where order shows |
| `READ UPDATE` + `REWRITE` locking | `COACTUPC` (account and customer), `COCRDUPC`, `COUSR02C`, `COUSR03C`, `COTRN01C`, `COBIL00C` | `@Transactional` plus optimistic version check or `SELECT ... FOR UPDATE` | `COACTUPC` snapshots old values, compares on re-read, and issues `SYNCPOINT ROLLBACK` when the second file update fails: this is one DB transaction. No `version` column exists yet; add it with the first update endpoint |
| `RESP`/`RESP2`, `HANDLE` | All online programs | Exception mapping (`NOTFND` to 404, `DUPREC` to 409) | |
| TD queue `JOBS` | `CORPT00C` writes JCL records to submit the report job | `POST /reports` that enqueues a batch job | `CORPT00C` builds 80-byte JCL records and writes them to TD queue `JOBS` (submit to the internal reader). Replace with a job request, never with generated JCL |
| Message and title copybooks | `CSMSG01Y`, `CSMSG02Y`, `COTTL01Y`, `CSDAT01Y` | i18n bundle, shared header component, `java.time` | |
| Validation copybooks | `CSLKPCDY` (phone area codes, US state, state-zip combos), `CSUTLDPY`/`CSUTLDWY` (date edit) | Validation library with the same tables | Keep the tables as data. They are the only business rule source for address edits |
| JCL job (`EXEC PGM`) | 38 jobs in `app/jcl`, procs `REPROC`, `TRANREPT` | Spring Batch job per program plus a scheduler definition | `PARM='2022071800'` (CBACT04C run date) becomes a job parameter. `COND=(0,NE)` becomes step flow. `DISP=(NEW,CATLG,DELETE)` output becomes a transactional write |
| JCL scheduling | `app/scheduler/CardDemo.controlm`, `CardDemo.ca7` | Keep Control-M or move to Kubernetes CronJobs, Quartz or Airflow | Three flows: daily `CLOSEFIL`, `TRANBKP`, `WAITSTEP`, `OPENFIL`; monthly `CLOSEFIL`, `INTCALC`, `COMBTRAN`, `WAITSTEP`, `OPENFIL`; weekly `MNTTRDB2` (Db2 module) |
| `CEMT SET FIL CLOSE/OPEN` via SDSF | `CLOSEFIL`, `OPENFIL`, and inside `CARDFILE`, `TRANFILE`, `CUSTFILE` | A maintenance flag or advisory lock | Closing files quiesces online use during batch. Postgres does not need file open/close, but the batch window still has to exclude concurrent updates to the same rows |
| IDCAMS `DEFINE CLUSTER`, `REPRO`, `BLDINDEX`, `DELETE` | `ACCTFILE`, `CARDFILE`, `CUSTFILE`, `TRANFILE`, `XREFFILE`, `TCATBALF`, `DISCGRP`, `TRANTYPE`, `TRANCATG`, `DUSRSECJ` | Flyway migrations (`db/migration`) and `load_sample_data.py` | Done in phase 1 for the ten KSDS clusters |
| GDG | `TRANSACT.BKUP` (76 generations), `TRANSACT.DALY`, `TRANSACT.COMBINED`, `DALYREJS`, `SYSTRAN`, `TRANREPT`, `TCATBALF.BKUP` | Dated archive tables or object-store keys with retention | `DEFGDGB`, `DEFGDGD` define them. `(+1)` generation semantics need an explicit date key |
| DFSORT and IEBGENER steps | `COMBTRAN` (sort by `TRAN-ID`), `TRANREPT`, `PRTCATBL`, `DEFGDGD` copies | SQL `ORDER BY`, Spring Batch readers, file copies | `COMBTRAN` sorts the master backup `TRANSACT.BKUP(0)` plus the interest postings `SYSTRAN(0)` and `REPRO`s the result back over the master: model it as inserting the interest rows, not rebuilding the table |
| Sequential and VSAM batch I/O | 12 core batch programs, `SELECT ... ASSIGN` | `JdbcCursorItemReader` / `ItemWriter` | `CBTRN02C` posts the daily file: reject file `DALYREJS` (LRECL 430) becomes a rejects table |
| RACF | Application sign-on does not use it: it reads the `USRSEC` file (`LISTCAT` also shows `RACF (NO)` on the datasets) | OAuth2/OIDC with an identity provider; roles `ADMIN` and `USER` from `usr_type` | Users and passwords live in the application data, in clear text, compared after upper-casing. Migrate users, force a password reset, add `ROLE_*` from `A`/`U`. The `COUSR*` screens become user admin APIs on top of the IdP or the `users` table |
| Assembler `COBDATFT` (macro `COCDATFT`) | Called by `CBACT01C` via `CODATECN` | `java.time.format.DateTimeFormatter` | Pure date reformatting (`YYYYMMDD` and `YYYY-MM-DD`). Delete, do not port |
| Assembler `MVSWAIT` | Called by `COBSWAIT`, run as `WAITSTEP` | Scheduler delay or `Thread.sleep`, or delete | It is a timed wait between the backup and the file reopen in the nightly chain. Likely unnecessary once file close/open is gone |
| LE services `CEEDAYS`, `CEE3ABD` | `CSUTLDTC` wraps `CEEDAYS` (date validation); every batch program abends with `CEE3ABD` | `java.time` parsing; throw and exit non-zero | |
| Control-block walking | `CBSTM03A` (PSA, TCB, TIOT pointers, `SET ADDRESS`) | Remove | It walks PSA, TCB and TIOT to read job and step names. No equivalent and no need |
| Packed and binary data (`COMP-3`, `COMP`), zoned overpunch, EBCDIC | `CVEXPORT`, zoned money in the ASCII/EBCDIC extracts | Decoder in the importer (`db_mapping.decode_zoned`, `validate_schema.unpack_comp3`) | Proven on the shipped extracts |
| `REDEFINES` multi-record file | `CVEXPORT` (`CBEXPORT`/`CBIMPORT`) | Discriminated union keyed by `EXPORT-REC-TYPE` | Branch-extract format. Keep only if the extract has to stay |
| Db2 | `app-transaction-type-db2` (`COTRTLIC`, `COTRTUPC`, `COBTUPDT`, `CARDDEMO.TRANSACTION_TYPE`); `app-authorization-ims-db2-mq` (`COPAUS2C`, `CARDDEMO.AUTHFRDS`) | Already relational. Fold `TRANSACTION_TYPE` into `transaction_types` | The core menus already list `COTRTLIC`/`COTRTUPC` (admin) and `COPAUS0C` (main menu). Without these modules those options are dead |
| IMS DL/I | `COPAUS0C`, `COPAUA0C`, `CBPAUP0C`, `PAUDBLOD`, `PAUDBUNL`, `DBUNLDGS` (pending authorization database) | New relational tables for authorizations | Hierarchical summary and detail segments. Model parent/child tables from `CIPAUSMY`/`CIPAUDTY` |
| MQ | `COPAUA0C` (authorization requests), `COACCT01`, `CODATE01` | Kafka/RabbitMQ or REST | Request/reply by correlation id |

## Slice order

Each slice ships end to end (API, UI, batch touching the same tables) behind the same schema.
Entry gate: the previous slice's tables are loaded and the validation script passes.

| # | Slice | Programs (transids) | Tables | Why here |
|---|---|---|---|---|
| 0 | Data layer (done in phase 1) | none | all ten | Everything reads it |
| 1 | Sign-on, menus, user admin | `COSGN00C` (CC00), `COMEN01C` (CM00), `COADM01C` (CA00), `COUSR00C`-`COUSR03C` (CU00-CU03) | `users` | Every other screen needs identity and routing. Smallest table, no joins, and it forces the auth decision (OIDC, password reset) early |
| 2 | Account and card | `COACTVWC` (CAVW), `COACTUPC` (CAUP), `COCRDLIC` (CCLI), `COCRDSLC` (CCDL), `COCRDUPC` (CCUP) | `accounts`, `customers`, `cards`, `card_account_xref` | Read paths first, then the two-table update in `COACTUPC`. Brings in `CSLKPCDY`/`CSUTLDPY` validation |
| 3 | Transactions and bill pay | `COTRN00C` (CT00), `COTRN01C` (CT01), `COTRN02C` (CT02), `COBIL00C` (CB00), `CSUTLDTC` | `transactions`, `accounts`, `card_account_xref` | Writes money. Needs the transaction id sequence and the account balance update in one transaction |
| 4 | Batch | `CBTRN02C` (post, `POSTTRAN`), `CBACT04C` (interest, `INTCALC`), `COMBTRAN`, `CBTRN03C` (report, `TRANREPT`) and `CORPT00C` (CR00), `CBSTM03A`/`B` (statements), `CBEXPORT`/`CBIMPORT`, `TRANBKP`, scheduler | `transaction_category_balances`, `disclosure_groups`, `transactions`, `accounts` | Posting and interest change balances, so they need parity tests against the mainframe output first. `CBACT01C`-`CBACT03C`, `CBCUS01C`, `CBTRN01C` are read and print utilities: replace with SQL checks |
| 5 | Optional modules | Db2 transaction type, IMS/Db2/MQ authorizations, VSAM-MQ | `transaction_types` fold-in, new authorization tables | Leave last. Needs new data models and a messaging decision |

Parity approach for slices 2 to 4: replay the shipped extracts through both systems, then diff account balances, `transaction_category_balances` and the reject file.
