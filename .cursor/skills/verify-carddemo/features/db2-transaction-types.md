# Db2 transaction types (optional extension)

Admins list, filter, edit and delete transaction types held in Db2 table `CARDDEMO.TRANSACTION_TYPE`, and add, edit or delete one type at a time on a maintenance screen. A weekly batch job applies add, update and delete records from a file to the same table. The extension is optional: it ships in `app/app-transaction-type-db2` and is reachable only after its CSD group is installed. All three features run on the `legacy` and `java` targets.

## Sub-features

- `tran-type-list` CTLI `COTRTLIC`: pages of 7 types (F7/F8), type and description filters (description is a SQL `LIKE`), `U` to edit a row's description in place, `D` to delete, F10 to save, F2 to the maintenance screen.
- `tran-type-maint` CTTU `COTRTUPC`: search by type code, edit the description, delete (F4 then confirm), add a missing type (F5), F12 to cancel.
- `batch-tran-type-maint` MNTTRDB2 `COBTUPDT`: 53-byte INPFILE records `A`/`U`/`D` plus type and description, `*` comments; one SYSOUT line per record; RC 4 after a failed statement.

## How to get to it (user POV)

- Admin menu option 5 (list) and option 6 (maintenance), or transids `CTLI` and `CTTU`. Without the extension installed, both options answer "This option is not installed ..." (`admin-menu-option-5-not-installed` in `menus.md`).
- Operators submit `app/app-transaction-type-db2/jcl/MNTTRDB2.jcl` (IKJEFT01, `DSN RUN PROGRAM(COBTUPDT)`). On Java it is `LibraryJob.MNTTRDB2`, run through the `Ikjeft01` bean.
- On Java the extension's tables are `carddemo.transaction_type` and `carddemo.transaction_type_category` (Flyway V6), separate from the VSAM-loaded `transaction_types` and `transaction_categories`. The install set lives in `cics_region.installed` (V7); production installs the extension.

## Driving it with run.py

Preconditions: baseline plus Postgres for the legacy target (`SKILL.md`, "Launch"). Suites: `characterization/cases/transaction-type-db2/COTRTLIC.json`, `COTRTUPC.json` and `characterization/cases/batch/COBTUPDT.json`, each with `"install": ["transaction-type-db2"]`. Db2 rows are seeded from `fixtures/seed/db2-*.txt` (the DB2LTTYP and DB2LTCAT load cards) and edited per case through datasets `TRANSACTION_TYPE` and `TRANSACTION_TYPE_CATEGORY`.

- **All three.** `python3 characterization/run.py --feature tran-type-list --feature tran-type-maint --feature batch-tran-type-maint`. Expect 99 PASS (list 43, maintenance 44, batch 12).
- **Delete refused by child rows.** `--case tran-type-list-delete-child-records` shows SQLCODE -532 on the screen and an unchanged table after ENTER rereads it.
- **Batch.** `--case batch-tran-type-add-update-delete` shows one record of each action and the `files` diff on `TRANSACTION_TYPE`.
- **Java slice.** Add `--target java`. Expect the same counts.

## Gotchas

- A Db2 row image is the VARCHAR length plus the text: `TR-TYPE X2, TR-DESCRIPTION-LEN 9(2), TR-DESCRIPTION X50`. A `files` diff that changes only `TR-DESCRIPTION-LEN` (7 to 50) is real: every save pads the description to 50 and Db2 VARCHAR keeps the blanks.
- The Db2 data is not the VSAM data: the load cards are upper case and spell type 06 `REVERAL`. VSAM only sees Db2 through the weekly TRANEXTR extract (DSNTIAUL), which is not ported or characterized.
- SQLCODEs come from the harness's mapping of Postgres errors, not from Db2. Messages that print SQLERRM (COTRTUPC's -532) are pinned with `"match": "subset"` without that field, because Db2's tokens cannot be reproduced. DSNTIAC is a stand-in that follows DSNTIAR's documented return codes; COTRTLIC zeroes its message area, so its -532 message ends `DSNTIAC CD12`.
- Collation is ASCII (`C`); Db2 for z/OS orders by EBCDIC, so a mix of digit and letter type codes would page in a different order there.
- Unreachable from one terminal and therefore not pinned: lock timeouts (-911), a row changed between fetch and update or delete, and Db2 connection failures. The Java ports keep those branches from the source.
- Legacy defects are pinned on purpose (`java/docs/legacy-defects.md`), among them: CTLI on a blank screen opens the list without sign-on; F10 after switching `U` to `D` deletes without confirmation; COTRTUPC accepts `-1` as a type code and inserts a retyped non-numeric code; COBTUPDT does not validate the type code and resets RC to 0 after a later successful statement.
