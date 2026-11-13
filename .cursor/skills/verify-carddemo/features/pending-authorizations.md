# Pending authorizations

The optional module `app/app-authorization-ims-db2-mq` decides card authorization requests that arrive on MQ (`COPAUA0C`, transaction CP00). It keeps each decision under the account in the IMS database DBPAUTP0 (a PAUTSUM0 summary per account, a PAUTDTL1 detail per authorization). Users browse the decisions and mark one as fraud (`COPAUS0C` CPVS, `COPAUS1C` CPVD, `COPAUS2C` into the Db2 table AUTHFRDS), and the nightly CBPAUP0J (`CBPAUP0C`) purges expired ones. In Java the module is `com.carddemo.authorizations`, on the Postgres tables `pending_auth_summaries`, `pending_auth_details` and `authfrds` (`V5__pending_authorizations.sql`). The request queue is on the embedded Artemis broker.

## Sub-features

- `auth-decide` (COPAUA0C-001 to 010) approves an amount up to the available credit. That is the summary's when the account has one, else the account's. Above it the decline reason is 4100, or 3100 when the account or the customer was not found. A card missing from CCXREF is declined with 3100 and writes nothing. Any other request writes its detail and adds to the summary's counters. The reply `card,transaction-id,time,response,reason,amount,` goes to the request's reply-to queue.
- `auth-list` (COPAUS0C-001 to 020) shows an account's customer, its summary and its authorizations five to a page, newest first. `S` on a row opens the detail. PF7 and PF8 page, and PF3 returns to the main menu (option 11).
- `auth-detail` (COPAUS1C-002 to 009, 013 to 015) shows one authorization with its reason description, or `9999-ERROR` for an unknown code. PF8 moves to the next older authorization and PF3 returns to the list.
- `auth-fraud` (COPAUS1C-010 to 012) is PF5. It turns the fraud flag blank or R into F and F into R, writing AUTHFRDS (insert, or update on a duplicate key) and the detail in one transaction.
- `auth-purge` (CBPAUP0J) deletes each detail at least the SYSIN expiry days old, and each summary then left with no approved and no declined authorizations. Deleting a summary deletes the details still under it.
- `auth-seed` loads `app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat` (21 summaries, 202 details, one blank segment skipped) whenever the app seeds. It stands in for PAUDBUNL, DBUNLDGS and PAUDBLOD, which are not ported.

## How to get to it (user POV)

- Sign on as `USER0001`, choose option 11 (Pending Authorization View), and type an account id. Account 7 has 50 authorizations and account 13 has 58.
- Send an authorization request as a JMS text message with a reply-to queue to `AWS.M2.CARDDEMO.PAUTH.REQUEST` (`carddemo.authorizations.request-queue`). The broker is in-VM, so only code in the app (or a test) can reach it.
- CBPAUP0J is the first job of the `DAILY` chain. Its SYSIN is the `sysin` job parameter, defaulting to the JCL's `00,00001,00001,Y`.

## Driving it with harness.py, the spec replay and the drives

Preconditions:

- `scripts/doctor.sh` exits 0.

- **Purge (executed).** `python3 characterization/batch/harness.py --job CBPAUP0J` runs `CBPAUP0C` from the module's source with each `EXEC DLI` turned into `CALL 'DLISTUB'` (`characterization/batch/stubs/DLISTUB.cbl`). The segments come from `characterization/batch/fixtures/cbpaup0j/pautdb.txt`, and the job reads its JCL SYSIN at DAY 22187. `golden/CBPAUP0J/PAUTDB` keeps only account 2. `golden-fixed/CBPAUP0J/PAUTDB` also keeps account 1 with its unexpired decline.
- **Online (derived, then replayed).** `python3 characterization/online/validate_specs.py` grounds the COPAUA0C, COPAUS0C and COPAUS1C specs. `cd carddemo-java && ./gradlew test` replays them and prints `[spec-replay] COPAUA0C`, `COPAUS0C` and `COPAUS1C` tallies. Its `[batch-golden] CBPAUP0J/...` lines read `FIXED`.
- **Live.** With `scripts/spring.sh up <port>`, `scripts/drive_spring.py` and `scripts/browser_drive.py` open option 11 for account 7, page with PF8 and PF7, open row 1, and toggle its fraud mark with PF5.

## Gotchas

- Every detail key is stored subtracted from all nines (`99999-YYDDD`, `999999999-HHMMSSmmm`), so ascending key order lists the newest first. Two requests on one account in the same millisecond get the same key, and the second insert fails.
- The purge decides from the summary's stored counters minus the details it deletes. It never rewrites a summary that stays, so the counters and amounts on screen still include purged authorizations.
- The legacy summary test checked the approved count twice and deleted a summary whose approvals had expired together with its unexpired declines. The harness `FIXES` entry and the Java port test the declined count.
- The expiry is YYDDD minus YYDDD. Across a new year every older detail looks hundreds of days old, so the first purge of a year deletes them all. The JCL's expiry of `00` deletes every authorization dated today or earlier.
- CBPAUP0C compares its 8-digit counters with the 5-character SYSIN fields as text. With `00001` the in-loop checkpoint and the `CHKP SUCCESS` line wait for 1000 summaries, so the fixture run prints neither.
- The specs pin `F-`/`R-` plus the report date only through the file effects and COPAUS1C-005, because screen fields compare exactly and the date is the run's.
- Writing to the CSSL transient data queue becomes a log line (`AuthorizationRequests`). Nothing reads CSSL back.
