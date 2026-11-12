# Daily transaction posting

The nightly POSTTRAN job (`CBTRN02C`) reads the daily transaction file and validates each record against the card cross-reference and account. It posts good records to the transaction master, updates account balances and category balances, and writes rejects with a reason code. Return code 4 means at least one record was rejected.

## Sub-features

- `post-accept` posts a valid transaction to TRANSACT, adds it to the account's current balance and to cycle credit (amount >= 0) or cycle debit (amount < 0), and adds it to TCATBALF, creating the category row if missing.
- `post-reject-100` rejects with `0100INVALID CARD NUMBER FOUND` when the card is not in CARDXREF.
- `post-reject-101` rejects with `0101ACCOUNT RECORD NOT FOUND` when the xref account is missing.
- `post-reject-102` rejects with `0102OVERLIMIT TRANSACTION` when cycle credit minus cycle debit plus amount exceeds the credit limit. Exactly at the limit is accepted.
- `post-reject-103` rejects with `0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION` when the origin date is after the expiration date. On the date itself is accepted. When a record fails both 102 and 103, 103 wins.
- `post-credit-quirk` records a credit as a negative cycle debit, so the over-limit formula subtracts it twice. A credit therefore reduces the available credit. POSTTRAN-EDGES record 10 (a 50.00 purchase after a 50.00 credit on an account at its limit) is rejected for it; record 9 (50.01) is over the limit either way. The Java port fixes this (harness `FIXES`, `golden-fixed/`), so record 10 posts there.
- `post-counts` prints the processed and rejected counts and sets RC 4 on any reject.
- `validtran` (`CBTRN01C`) prints the xref and account lookups for each daily record without writing anything.

## How to get to it (user POV)

- An operator runs JCL `app/jcl/POSTTRAN.jcl` in the nightly cycle.
- In Java, the `POSTTRAN` Spring Batch job (`com.carddemo.batch.DailyPosting`), started by the `DAILY` chain after `CBPAUP0J`. `spring.sh up` also runs it once at start-up on the EBCDIC daily feed, so the seeded TRANSACT, balances and TCATBALF are what POSTTRAN posted.

## Driving it with harness.py

Preconditions:

- `scripts/doctor.sh` exits 0.

- **Fixture day.** Run `python3 characterization/batch/harness.py --job POSTTRAN`. All five artifacts PASS. `POSTTRAN/CBTRN02C.sysout` ends with `TRANSACTIONS PROCESSED :000000300`, `TRANSACTIONS REJECTED  :000000038`, and `[return code 4]`. `POSTTRAN/DALYREJS` has 38 lines, all ending `0102OVERLIMIT TRANSACTION`.
- **Boundaries.** Run `python3 characterization/batch/harness.py --job POSTTRAN-EDGES`. `POSTTRAN-EDGES/DALYREJS` lists EDGE records 1, 2, 4, 6, 7, and 9 with reasons 100, 101, 102, 103, 103, and 102. `POSTTRAN-EDGES/TRANSACT.ksds` holds records 3, 5, and 8.
- **Side effects.** In `POSTTRAN-EDGES/ACCTDATA.ksds`, account 1 shows a current balance of `00000021640{` (2164.00), cycle credit `00000020200{`, and cycle debit `00000000500}` (-50.00).
- **VALIDTRAN.** Run `--job VALIDTRAN`. `VALIDTRAN/CBTRN01C.sysout` PASSES.
- **Proof.** Run `scripts/verify.sh`, then cite `DIR/batch/POSTTRAN/*` and `DIR/batch/POSTTRAN-EDGES/*`.
- **With the fixes.** The harness also runs the cycle with `FIXES` patched into the COBOL. The credit fix changes the fixture day to 23 rejects (`golden-fixed/POSTTRAN/DALYREJS`) and posts EDGE record 10 (`golden-fixed/POSTTRAN-EDGES/TRANSACT.ksds`).
- **Java.** `cd carddemo-java && ./gradlew test --tests com.carddemo.batch.BatchGoldenTest` runs the Java job on the same fixtures and clock and prints one `[batch-golden]` line per artifact: `MATCHED` (equals `golden/`), `FIXED` (equals `golden-fixed/`) or `OVERRIDDEN` (a named entry in `carddemo-java/src/test/resources/batch-overrides.yaml`). `DailyPostingTest` pins the rerun and locking behavior below.

## Gotchas

- The main fixture day only exercises reason 102. Reasons 100, 101, and 103 and both boundaries are pinned only by POSTTRAN-EDGES.
- TRAN-PROC-TS comes from the fixed clock (`2022-07-06-10.00.00.000000`). A Java job must use the same clock, or the TRANSACT diff fails on every row.
- Amounts are zoned decimal with an EBCDIC-style sign overpunch (`{` = +0, `A` = +1, `}` = -0). Compare decoded values, not raw characters, when explaining a diff.
- TRANSACT starts empty, as after `app/jcl/TRANFILE.jcl`. POSTTRAN opens it OUTPUT.
- Java TRAN-PROC-TS is ISO (`2022-07-06 10:00:00.000000`); a regex rewrite in `batch-overrides.yaml` maps the DB2 form in the goldens.
- A Java rerun of a feed repeats each record's first outcome: posted records print `ALREADY POSTED : <id>`, rejected ones `ALREADY REJECTED : <id>` with the stored reason (table `daily_rejects`). A feed record whose id is posted with other data abends the job with RC 16. The legacy program has no rerun; it fails on the duplicate key.
- Java posts each record in its own transaction under the account row lock, so an online bill payment on the same account waits instead of losing an update.
