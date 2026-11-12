# Reports and statements

TRANREPT (`CBTRN03C`) prints a 133-column daily transaction report for a date window, with account totals, page totals, and a grand total. CREASTMT (`CBSTM03A` calling `CBSTM03B`) prints one statement per card, in plain text (80 columns) and HTML (100 columns), with customer address, account summary, and transaction lines.

## Sub-features

- `tranrept-filter` includes only transactions whose processing date falls in the window, sorted by card number (emulated DFSORT INCLUDE and SORT).
- `tranrept-totals` prints account, page (every 20 lines), and grand totals. The fixture cycle's grand total is `79,254.29`.
- `tranrept-lookups` prints the type and category descriptions from TRANTYPE and TRANCATG.
- `stmt-text` prints statement blocks in `CREASTMT/STMTFILE`, from `START OF STATEMENT` to `END OF STATEMENT`, including the `Total EXP:` line.
- `stmt-html` prints the same content as HTML in `CREASTMT/HTMLFILE`.

## How to get to it (user POV)

- An operator runs `app/jcl/TRANREPT.jcl` and `app/jcl/CREASTMT.JCL`. Online users request reports from screen CR00 (`CORPT00C`), which submits TRANREPT.
- In Java, the `TRANREPT` Spring Batch job (`com.carddemo.batch.TransactionReport`, parameters `start` and `end`) and `CREASTMT` (in the `DAILY` chain). Screen CR00 calls `ReportSubmission.submit(start, end)`, which launches TRANREPT asynchronously instead of writing JCL to the internal reader. The report lands in the spool, `<carddemo.batch.spool-dir>/TRANREPT/<execution id>/TRANREPT`.

## Driving it with harness.py

Preconditions:

- `scripts/doctor.sh` exits 0. POSTTRAN, INTCALC, and COMBTRAN run first in the same cycle.

- **Report.** Run `python3 characterization/batch/harness.py --job TRANREPT`. `TRANREPT/TRANREPT` has 519 lines. Line 1 is the `DALYREPT` header with `Date Range: 2022-01-01 to 2022-07-06`, and the last line is `Grand Total...` ending `79,254.29`.
- **Statements.** Run `--job CREASTMT`. `CREASTMT/STMTFILE` (1262 lines) and `CREASTMT/HTMLFILE` (6632 lines) PASS. The first statement is for `Aniya Alba Von`, account `00000000050`.
- **Proof.** Run `scripts/verify.sh`, then cite `DIR/batch/TRANREPT/TRANREPT.txt` and `DIR/batch/CREASTMT/STMTFILE.txt`.
- **With the fixes.** `golden-fixed/TRANREPT/TRANREPT.txt` ends with the last account's `Account Total` and a grand total of `89,994.21`: the POSTTRAN credit fix posts 15 more rows, and the CBTRN03C fix stops the last amount being added twice. The statements change with the balances.
- **Java.** `./gradlew test --tests com.carddemo.batch.BatchGoldenTest`; the TRANREPT and CREASTMT artifacts print `FIXED`.

## Gotchas

- The report only has rows because the fixed clock (2022-07-06) falls inside the window. With a later clock, every posted row is filtered out and only the totals remain.
- `CBTRN03C` has an `IF ... NEXT SENTENCE` inside its own date filter (line 177). It relies on DFSORT having filtered already. Java reads only the window's rows (`TransactionRepository.processedBetween`), so the branch cannot arise and is not ported.
- At end of file legacy `CBTRN03C` adds the last record's amount to the page and account totals a second time, and never writes the last account's total. Java fixes both and keeps the two debug `DISPLAY` lines (`TRAN-AMT`, `WS-PAGE-TOTAL`) in SYSOUT.
- `CBSTM03A` normally prints the job's DD names by walking z/OS control blocks. The harness strips that block, so `CREASTMT/CBSTM03A.sysout` is empty apart from the return code. That output was never part of the business behavior.
- The statement tables are fixed at 51 cards and 10 transactions per card (`WS-TRNX-TABLE`). Fixtures stay under that, and larger inputs overflow on the mainframe too.
