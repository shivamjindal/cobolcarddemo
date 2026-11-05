# Transactions and bill pay

A signed-on user lists and views transactions, adds a transaction by hand, pays an account's balance in full, and submits the transaction report job. Adding a transaction and paying a bill write TRANSACT; paying also rewrites the account balance; report submit writes JCL to a TD queue.

## Sub-features

- `transaction-list` (COTRN00C, main option 6) pages TRANSACT 10 at a time from an optional Tran ID; PF7/PF8 page; `S` selects a row into transaction view.
- `transaction-view` (COTRN01C, option 7) shows one transaction by ID; PF4 clears; PF5 goes to the list.
- `transaction-add` (COTRN02C, option 8) validates account or card, then type, category, source, description, amount, dates and merchant fields in source order; asks for confirmation `Y`; writes the next Tran ID. PF5 copies the last transaction's data.
- `bill-pay` (COBIL00C, option 10) shows the balance for an account, asks for confirmation, writes a payment transaction and zeroes the balance.
- `report-submit` (CORPT00C, option 9) submits monthly, yearly or custom-range report JCL to the `JOBS` TD queue after validating dates.

## How to get to it (user POV)

- Sign on as `USER0001`, then type the option number (6, 7, 8, 9, 10) on the main menu.
- Select a row with `S` in the transaction list to open it in view.

## Driving it with run.py

Preconditions: baseline (online TRANSACT is the 300-record `dailytran.txt`); cases in `characterization/cases/transactions/`.

- **All of it.** Run `python3 characterization/run.py --feature transaction-list --feature transaction-view --feature transaction-add --feature bill-pay --feature report-submit`. Expect 99 PASS and 1 NOT-RUN.
- **A write.** Run `--feature transaction-add`; the confirm-`Y` cases pin `files.TRANSACT.inserted` with the new Tran ID and amounts.
- **A balance change.** Run `--feature bill-pay`; the paid case pins the inserted payment and the `ACCT-CURR-BAL` change to zero.
- **A job submit.** Run `--feature report-submit`; the golden's `queues` holds the JCL lines written to `JOBS`.
- **Java slice.** Add `--target java` to the same command. Expect 100 PASS: the source-derived case that legacy cannot run passes on Java. On Java a submit records one row in `job_submissions` instead of writing a TD queue, and the adapter reports that row as the same `queues` observation.

## Gotchas

- Numeric IDs must be typed with leading zeros. GnuCOBOL turns `1` plus spaces into `00000000001` on a MOVE into `PIC 9(11)`; Enterprise COBOL keeps the bytes and the key does not match. Cases that depend on this are source-derived.
- Pinned legacy quirks: confirm `N` in transaction add re-prompts instead of cancelling; dates before 1582 pass because CSUTLDTC's message 2513 is ignored; added transactions store a bare 10-character date as timestamps; bill pay writes a payment even when the account has no card cross-reference (the card number field is then uninitialized storage, low-values here); custom report ranges with end before start are submitted.
- PF5 from transaction view into the list arrives with PF5 still the AID, so the list skips its first record.
- A Tran ID filter above every key abends the list with AEIP (STARTBR NOTFND, then an ENDBR with no RESP on a browse that never started). Pinned on purpose.
- A custom date part with letters becomes 0 under GnuCOBOL's NUMVAL-C: `AB` in a month is `00` and fails the date check, while `20X6` in a year is `0000`, passes as pre-1582, and is submitted. No golden pins it (Enterprise COBOL leaves it undefined); Java reproduces the GnuCOBOL answer as `CORPT00C-non-numeric-date-part-is-zero`.
- On Java, typing into a protected field (`TRNID01` on the list, `CURBAL` on bill pay) is HTTP 400 `field ... is protected`, where a 3270 would lock the keyboard.
- Paging all 30 pages is not driven; paging cases start near the end via the Tran ID filter.
