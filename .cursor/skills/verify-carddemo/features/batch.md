# Batch jobs

The nightly jobs post the daily transaction file, compute interest, produce statements and reports, and extract files. Each case runs one COBOL step with the DD bindings of its JCL and checks the return code, SYSOUT, file changes and output files. All five features run on the `legacy` and `java` targets. The IMS jobs of the pending authorization extension (CBPAUP0J, UNLDPADB, LOADPADB, UNLDGSAM) are in [authorization.md](./authorization.md).

## Sub-features

- `batch-posting` POSTTRAN `CBTRN02C`: validates each daily transaction (reasons 100 card, 101 account, 102 over limit, 103 expired), posts accepted ones to TRANSACT, ACCTDATA and TCATBALF, writes rejects to DALYREJS, RC 4 when any reject.
- `batch-interest` INTCALC `CBACT04C` with `PARM='2022071800'`: interest per category balance by disclosure group (falling back to `DEFAULT`), written as SYSTRAN transactions, account balance updated.
- `batch-statements` CREASTMT: the SORT step builds TRXFL from TRANSACT, then `CBSTM03A`/`CBSTM03B` write the text and HTML statements.
- `batch-reports` TRANREPT `CBTRN03C`: the transaction detail report for a DATEPARM range.
- `batch-extracts` `CBACT01C`, `CBACT02C`, `CBACT03C`, `CBCUS01C`: list or extract the master files.

## How to get to it (user POV)

- Operators submit the JCL in `app/jcl` (POSTTRAN, INTCALC, CREASTMT, TRANREPT, READACCT, READCARD, READXREF, READCUST).
- On Java the same members are `LibraryJob` entries, run by the scheduler chains or by the `operator` profile (`java/README.md`, "Batch"). The online report submit (main option 9) runs TRANREPT through the internal reader.

## Driving it with run.py

Preconditions: baseline; cases in `characterization/cases/batch/`. Inside tmux, run every command below as `env -u LD_LIBRARY_PATH python3 ...`. Otherwise each legacy case reports `ERROR`.

- **All jobs.** Run `python3 characterization/run.py --feature batch-posting --feature batch-interest --feature batch-statements --feature batch-reports --feature batch-extracts`. Expect 28 PASS (posting 12, interest 7, extracts 4, statements 3, reports 2).
- **Posting rules.** Run `--feature batch-posting`. `posting-mixed-batch` shows one run accepting two records, rejecting an invalid card (100) and an over-limit amount (102), with `TRANSACTIONS PROCESSED :000000004` and RC 4.
- **Statements.** Run `--case statement-one-account`. `outputs.STMTFILE` is the printed statement; `outputs.HTMLFILE` is the HTML version.
- **Java slice.** Add `--target java` to any command above. Expect the same counts. One case is `python3 characterization/run.py --target java --case posting-mixed-batch`. The adapter submits the case as a one-step deck to TD queue `JOBS`, and the warm server's internal reader runs it on Spring Batch (`SKILL.md`, "Adding a Java slice").
- **Without run.py.** `cd java && mvn verify` runs these 28 goldens, and the six IMS batch goldens of [authorization.md](./authorization.md), in `BatchGoldensIT` through the same deck path.

## Gotchas

- POSTTRAN opens TRANSACT for OUTPUT; cases start it empty (the TRANBKP job deletes and redefines it first). Loading it non-empty would test GnuCOBOL's OUTPUT semantics, not VSAM's.
- Legacy defects are pinned on purpose: CBACT04C never rewrites the last account in TCATBALF; CBTRN03C adds the last amount twice at end of file and stops at the first out-of-range record (`NEXT SENTENCE`); CBACT03C and CBCUS01C print every record twice. A port that "fixes" these fails its goldens; change the golden only with a recorded decision. Java keeps each one in a `@LegacyDefect` method, listed in `java/docs/legacy-defects.md`.
- Interest transaction descriptions end in low-values (`STRING` into uninitialized storage). That is the `-fdefaultbyte=0` assumption; real z/OS storage may differ. On Java the SYSTRAN generation keeps the low-values, but a copy stored in the `transactions` table has spaces there, because Postgres text cannot hold `0x00`.
- CBSTM03A's table holds 10 transactions per card; more than that is undefined behavior and not characterized.
- Full-seed runs are pinned with `"match": "subset"` on completion and SYSOUT only; the focused cases pin every file change.
- On Java the case deck is not the library JCL. It is job `CHARZ` with one step, `STEP01`, and output DDs are `SYSOUT=*` read back from the spool. A `{"dataset": X}` binding is a flat copy cataloged as `AWS.M2.CARDDEMO.CHARZ.<dd>`. `{"derive": "creastmt-trxfl"}` builds TRXFL with the adapter's `SortSpec.CreastmtTrxfl`, not by running CREASTMT's SORT step. The library jobs themselves (CREASTMT, TRANBKP, COMBTRAN, the CLOSEFIL and OPENFIL gate, WAITSTEP) and the chains have no goldens. Spring tests such as `RunnableChainsIT`, `BatchWindowJobsIT` and `UtilityStatementsIT` cover them.
- Do not run two `--target java` runs at once. They share database `carddemo_characterization`, and each case's seed clears the job queue and spool the other run is reading.
