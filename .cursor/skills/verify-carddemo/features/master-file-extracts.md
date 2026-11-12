# Master file extracts

The READ* utility jobs print every record of a master file to SYSOUT. READACCT (`CBACT01C`) also writes three extract files: a fixed-length extract with a packed-decimal field and a reformatted reissue date (through the `COBDATFT` assembler routine), an array-layout file, and variable-length records.

## Sub-features

- `readacct-sysout` prints each account field by name.
- `readacct-outfile` writes 107-byte records. The reissue date is converted from `YYYY-MM-DD` to `YYYYMMDD`, and the record ends with packed-decimal bytes (shown as `\xNN`).
- `readacct-arryfile` writes 110-byte array-layout records.
- `readacct-vbrcfile` writes two variable-length records per account.
- `readcard`, `readxref`, and `readcust` print every card, xref, and customer record.

## How to get to it (user POV)

- An operator runs `app/jcl/READACCT.jcl`, `READCARD.jcl`, `READXREF.jcl`, and `READCUST.jcl`.
- These are diagnostic utilities, so the migration may retire them instead of porting them. Record that decision here if it is made.

## Driving it with harness.py

Preconditions:

- `scripts/doctor.sh` exits 0.

- **Accounts.** Run `python3 characterization/batch/harness.py --job READACCT`. Four artifacts PASS. `READACCT/OUTFILE` line 1 starts `00000000001Y00000001940{` and contains `20250520`.
- **Others.** Run `--job READCARD --job READXREF --job READCUST`. Each sysout PASSES.
- **Proof.** Run `scripts/verify.sh`, then cite `DIR/batch/READACCT/*`.

## Gotchas

- `COBDATFT` runs as a COBOL port (`characterization/batch/stubs/COBDATFT.cbl`), not as the assembler original. On error, the assembler copies 38 bytes from a 13-byte literal. The port writes only the 13 bytes.
- The VBRCFILE golden decodes GnuCOBOL's 4-byte record header. z/OS RDWs differ on disk, but the record contents are the same.
