# CardDemo verification map

This directory is the maintained source for verifying CardDemo behavior across the migration. Read this index first, then use the matching feature file as the recipe.

## Baseline preconditions

- `scripts/doctor.sh` exits 0.
- The datasets come from `app/data/ASCII` only. The harness reloads every KSDS from these fixtures at the start of each run, so no state carries over between runs.
- The clock is fixed at `2022-07-06 10:00:00.00` through `COB_CURRENT_DATE`. The INTCALC parm is `2022071800`, and the TRANREPT window is `2022-01-01` to `2022-07-06`.

## Driving conventions

- Batch features are driven with `characterization/batch/harness.py`. The run always executes the full cycle in order, and `--job` only narrows which artifacts are compared.
- Online features are driven with `characterization/online/validate_specs.py` today. A Spring slice drives them by replaying spec cases against its endpoints.
- Cite the artifact path (batch) or the spec case id (online) with every claim.

## Proof and skip reporting

- Batch proof is the harness PASS line for each named artifact, plus the artifact itself from `verify.sh` evidence.
- Online proof today shows that the spec is grounded, never that it was observed. Say "derived" in the report.
- If a behavior you need has no pinning artifact or case, report the gap and add the scenario or case. Do not infer it from a neighboring one.

## Feature entry contract

Each feature file has an H1, one paragraph on the user-visible behavior, and four H2 sections in order: `Sub-features`, `How to get to it (user POV)`, `Driving it with <harness>`, `Gotchas`.

## Features

- [Daily transaction posting](./daily-posting.md) covers POSTTRAN, its rejection reasons and boundaries, and the VALIDTRAN pre-check.
- [Interest and transaction merge](./interest-and-combine.md) covers INTCALC interest transactions and the COMBTRAN merge.
- [Reports and statements](./reports-and-statements.md) covers the TRANREPT daily report and CREASTMT text and HTML statements.
- [Master file extracts](./master-file-extracts.md) covers READACCT, READCARD, READXREF, and READCUST.
- [Online screens](./online-screens.md) covers sign-on, user admin, account, card, transaction, bill-pay, and report screens through derived specs.
- [MQ inquiries](./mq-inquiries.md) covers the account and system-date requests answered by COACCT01 and CODATE01.
