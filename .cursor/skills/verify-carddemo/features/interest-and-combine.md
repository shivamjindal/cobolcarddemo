# Interest and transaction merge

INTCALC (`CBACT04C`) walks the category balances, looks up each account's disclosure-group interest rate, and writes one system interest transaction per account to SYSTRAN. It also adds the interest to the account balance and resets the cycle totals. COMBTRAN then backs up TRANSACT, merges it with SYSTRAN sorted by transaction id, and reloads TRANSACT.

## Sub-features

- `intcalc-systran` writes interest transactions with ids `<parm date><6-digit seq>` (for example `2022071800000001`), type `01`, category `0005`, source `System`, and description `Int. for a/c <acct>`.
- `intcalc-account` rewrites each account with the interest added to its current balance.
- `intcalc-default-rate` falls back to the `DEFAULT` disclosure group when the account's group has no row (see `INTCALC/CBACT04C.sysout`).
- `combtran-merge` produces a TRANSACT holding every posted transaction plus every interest transaction, in key order.

## How to get to it (user POV)

- An operator runs `app/jcl/INTCALC.jcl` (PARM `2022071800`), then `app/jcl/TRANBKP.jcl` and `app/jcl/COMBTRAN.jcl`.
- In Java, the interest job the batch slice builds.

## Driving it with harness.py

Preconditions:

- `scripts/doctor.sh` exits 0. POSTTRAN runs first in the same cycle, which the harness does automatically.

- **Interest.** Run `python3 characterization/batch/harness.py --job INTCALC`. `INTCALC/SYSTRAN` has 50 lines, the first starting `2022071800000001010005System    Int. for a/c 00000000001`. `INTCALC/ACCTDATA.ksds` PASSES.
- **Merge.** Run `--job COMBTRAN`. `COMBTRAN/TRANSACT.ksds` has 312 lines: 262 posted plus 50 interest.
- **Proof.** Run `scripts/verify.sh`, then cite `DIR/batch/INTCALC/*` and `DIR/batch/COMBTRAN/TRANSACT.ksds.txt`.

## Gotchas

- The interest transaction ids embed the PARM, not the clock. Changing the PARM changes every id downstream, including statements and the report.
- The DFSORT and REPRO steps are emulated in Python (`job_combtran`). A failure here can be an emulation bug, so check `harness.py` before blaming COBOL.
- The `XREFFIL1` alternate-index path DD exists in the JCL, but `CBACT04C` never opens it.
