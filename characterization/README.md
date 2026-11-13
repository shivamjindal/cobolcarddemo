# CardDemo characterization suite

Captures what the legacy CardDemo programs do today, so the Java/Spring slices can prove they still do it. How to run it, and the recipes per feature, live in the `verify-carddemo` skill (`.cursor/skills/verify-carddemo/SKILL.md`).

```bash
sudo apt-get install -y gnucobol3                    # once per machine
python3 characterization/batch/harness.py            # batch golden masters, exit 0 when all match
python3 characterization/online/validate_specs.py    # online spec grounding, exit 0 when all hold
```

## What is executed and what is derived

| Programs | Evidence | Where |
| --- | --- | --- |
| `CBACT01C`, `CBACT02C`, `CBACT03C`, `CBACT04C`, `CBCUS01C`, `CBTRN01C`, `CBTRN02C`, `CBTRN03C`, `CBSTM03A`, `CBSTM03B`, `COBTUPDT` | **Executed** by GnuCOBOL 3.1.2. `COBTUPDT` is patched so its three `EXEC SQL` statements call the `TRTYPE` stub over the `TRANTYPE` indexed file | `batch/golden/<JOB>/` |
| `CBPAUP0C` (`app/app-authorization-ims-db2-mq`) | **Executed** by GnuCOBOL with its DL/I calls served by a stub, on crafted segments | `batch/golden/CBPAUP0J/` |
| `COSGN00C`, `COMEN01C`, `COADM01C`, `COUSR00C`-`COUSR03C`, `COACTVWC`, `COACTUPC`, `COCRDLIC`, `COCRDSLC`, `COCRDUPC`, `COTRN00C`-`COTRN02C`, `COBIL00C`, `CORPT00C`, `COTRTLIC`, `COTRTUPC`, `COPAUA0C`, `COPAUS0C`, `COPAUS1C` (with `COPAUS2C`'s AUTHFRDS write), `COACCT01`, `CODATE01` | **Derived** by reading source. Never executed, because CICS is unavailable. The validator checks only that each claim is grounded in real source | `online/specs/<PROGRAM>.yaml` |
| `CBEXPORT`, `CBIMPORT`, `COBSWAIT`, `CSUTLDTC` (standalone); the IMS load/unload utilities `PAUDBLOD`, `PAUDBUNL`, `DBUNLDGS` | Not captured | |

## Batch: how faithful the run is

The COBOL compiles from `app/cbl` and `app/cpy` with `-fsign=EBCDIC` (the fixtures use the mainframe sign overpunch) and `-ftab-width=4` (`CUSTREC.cpy` and `CSLKPCDY.cpy` contain tab characters). Deviations from a z/OS run:

- **CBSTM03A** is compiled from a copy without its PSA/TCB/TIOT walk, which only DISPLAYs DD names and dereferences absolute z/OS addresses. The harness fails loudly if the patch stops applying.
- **COBDATFT** (assembler) is replaced by a COBOL port in `batch/stubs/COBDATFT.cbl`.
- **CEE3ABD** (LE abend) is replaced by a stub that prints `CEE3ABD: ABEND U<code>` and ends with RC 16.
- **JCL PARM** is passed by `batch/stubs/RUNPGM.cbl` as the z/OS halfword-length block.
- **DL/I** in `CBPAUP0C` is compiled from a copy where each `EXEC DLI` GN, GNP, DLET and CHKP is a `CALL 'DLISTUB'` (`batch/stubs/DLISTUB.cbl`). The stub serves DBPAUTP0 from a flat file of 201-byte records (`S` or `D`, then the segment). A summary's DLET also removes its details, and CHKP writes the surviving segments back. It returns only blank, GB and GE statuses, so the program's abend paths are unverified. The harness fails loudly if a translation stops applying. Its SYSIN is the JCL member's in-stream data, fed on standard input.
- **IDCAMS REPRO/DEFINE and DFSORT** steps are emulated in Python (`harness.py`, one function per JCL member). KSDS files are GnuCOBOL BDB indexed files with the same keys and alternate keys as the IDCAMS definitions. DFSORT ties keep input order.
- **Clock** is fixed with `COB_CURRENT_DATE=2022/07/06 10:00:00.00`.
- **File status semantics** come from GnuCOBOL, not VSAM. The runs only exercise statuses both share (00, 02, 10, 23). A path that depends on a VSAM-only status is unverified.

Golden format: one record per line, bytes outside printable ASCII (and `\`) written as `\xNN`, trailing spaces dropped. SYSOUT is captured verbatim, followed by `[return code N]`.

Coverage is only as wide as the fixtures. The fixture day rejects 38 of 300 transactions, all for reason 102. The `POSTTRAN-EDGES` scenario (inputs in `batch/fixtures/posttran-edges/`) adds crafted records for reasons 100, 101, and 103, the credit-limit and expiry boundaries, and the credit-handling quirk. Without that scenario, a `>=` changed to `>` in the limit check went undetected. The `CBPAUP0J` scenario (inputs in `batch/fixtures/cbpaup0j/`) covers the purge's expiry boundary at DAY 22187, approved and declined details, a summary that empties, and one that keeps only a decline.

**Deliberate fixes.** The Java port fixes some legacy bugs on purpose. Each is listed in `FIXES` in `harness.py` as the smallest edit to the COBOL source, with a one-sentence reason, and the harness runs the cycle a second time with those edits compiled in. `batch/golden-fixed/` holds each artifact that run writes differently, so the expected effect of a fix is computed by the legacy programs, not edited by hand. The Java golden test (`carddemo-java`, `BatchGoldenTest`) compares against `golden-fixed/` where a file exists there and `golden/` otherwise.

## Online: what a spec is

See `online/SCHEMA.md`. Each case is a claim of the form "given this COMMAREA and these file rows, when the user presses this key with these fields, the program shows this message, moves the cursor here, transfers there, and writes this". Each case cites the line range that implements it. Java slices replay the cases against their endpoints. A slice that deliberately departs from a case records why in that spec's `notes`.
