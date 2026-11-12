#!/usr/bin/env python3
"""Run CardDemo batch programs under GnuCOBOL and diff their outputs against golden masters.

    python3 characterization/batch/harness.py            # build, run all jobs, diff against golden/
    python3 characterization/batch/harness.py --update   # rewrite golden/ from this run
    python3 characterization/batch/harness.py --job POSTTRAN --keep
    python3 characterization/batch/harness.py --out /tmp/evidence   # also keep this run's artifacts

Each job mirrors one JCL member under app/jcl. COBOL programs run unmodified except
CBSTM03A (see PATCHES). IDCAMS REPRO and DFSORT steps are emulated in Python.

The cycle then runs a second time with FIXES applied to the legacy source: golden-fixed/ holds each
artifact whose output those fixes change, so the Java port's deliberate fixes are pinned by the legacy
programs themselves rather than by hand-edited expectations.
"""
import argparse
import difflib
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
APP = REPO / "app"
FIXTURES = APP / "data" / "ASCII"
GOLDEN = HERE / "golden"
GOLDEN_FIXED = HERE / "golden-fixed"
BUILD = HERE / "build"
BUILD_FIXED = BUILD / "fixed"

# Fixed clock so CURRENT-DATE derived timestamps are reproducible. Chosen inside the
# TRANREPT date window (2022-01-01..2022-07-06) so the report has rows.
CLOCK = "2022/07/06 10:00:00.00"
INTCALC_PARM = "2022071800"
TRANREPT_WINDOW = ("2022-01-01", "2022-07-06")

PROGRAMS = ["CBACT01C", "CBACT02C", "CBACT03C", "CBACT04C", "CBCUS01C",
            "CBTRN01C", "CBTRN02C", "CBTRN03C", "CBSTM03A", "CBSTM03B"]

# CBSTM03A walks z/OS control blocks (PSA -> TCB -> TIOT) through absolute addresses to
# DISPLAY the job's DD names. That cannot run off-mainframe, so the harness compiles a copy
# with that diagnostic block removed. Nothing after it depends on it.
PATCHES = {
    "CBSTM03A": (r"\n(\s+SET ADDRESS OF PSA-BLOCK.*?)(?=\n\s+OPEN OUTPUT STMT-FILE HTML-FILE\.)",
                 "\n      *> characterization harness: TIOT walk removed"),
}


@dataclass(frozen=True)
class Fix:
    """A legacy bug the Java port deliberately fixes, as the smallest edit to the COBOL source."""
    program: str
    pattern: str
    replacement: str
    reason: str


FIXES = [
    Fix("CBTRN02C", r"(\n\s+)- ACCT-CURR-CYC-DEBIT", r"\1+ ACCT-CURR-CYC-DEBIT",
        "Cycle debit holds credits as negative amounts, so subtracting it made every credit use up limit instead of freeing it."),
    Fix("CBTRN03C",
        r"(\n {16}ELSE +\n)( {17}DISPLAY 'TRAN-AMT ' TRAN-AMT +\n {17}DISPLAY 'WS-PAGE-TOTAL'  WS-PAGE-TOTAL +\n)"
        r" {17}ADD TRAN-AMT TO WS-PAGE-TOTAL +\n {33}WS-ACCOUNT-TOTAL +\n",
        "\\1                 IF WS-FIRST-TIME = 'N'\n                   PERFORM 1120-WRITE-ACCOUNT-TOTALS\n"
        "                 END-IF\n\\2",
        "At end of file the report added the last amount to the totals a second time and never wrote the last account's total."),
    Fix("CBACT04C", r"(\n {15}ELSE +\n {20}PERFORM 1050-UPDATE-ACCOUNT +\n {15}END-IF +\n {11}END-PERFORM\.)",
        "\\1\n           IF WS-FIRST-TIME NOT = 'Y'\n              PERFORM 1050-UPDATE-ACCOUNT\n           END-IF.",
        "The loop exits before its end-of-file branch, so the last account's interest was posted to TRANSACT but never added to its balance."),
]


def patched(src, edits):
    text = src.read_text(errors="replace")
    for pattern, repl in edits:
        text, n = re.subn(pattern, repl, text, flags=re.S)
        if n != 1:
            raise RuntimeError(f"patch for {src.stem} no longer applies: {pattern}")
    return text


@dataclass(frozen=True)
class Dataset:
    """A VSAM KSDS as defined by its IDCAMS DEFINE CLUSTER/AIX in app/jcl."""
    name: str
    reclen: int
    key: tuple
    alt: tuple = None
    fixture: str = None


KSDS = {d.name: d for d in [
    Dataset("ACCTDATA", 300, (0, 11), fixture="acctdata.txt"),
    Dataset("CARDDATA", 150, (0, 16), alt=(16, 11), fixture="carddata.txt"),
    Dataset("CUSTDATA", 500, (0, 9), fixture="custdata.txt"),
    Dataset("CARDXREF", 50, (0, 16), alt=(25, 11), fixture="cardxref.txt"),
    Dataset("TCATBALF", 50, (0, 17), fixture="tcatbal.txt"),
    Dataset("DISCGRP", 50, (0, 16), fixture="discgrp.txt"),
    Dataset("TRANTYPE", 60, (0, 2), fixture="trantype.txt"),
    Dataset("TRANCATG", 60, (0, 6), fixture="trancatg.txt"),
    Dataset("TRANSACT", 350, (0, 16), alt=(304, 26)),
    Dataset("TRXFL", 350, (0, 32)),
]}

TRAN_LEN = 350


def fixture_records(name, reclen):
    recs = []
    for line in (FIXTURES / name).read_text(encoding="latin-1").splitlines():
        line = line.rstrip("\r")
        if not line.strip():
            continue
        if len(line) > reclen:
            raise ValueError(f"{name}: record longer than {reclen}: {line!r}")
        recs.append(line.ljust(reclen).encode("latin-1"))
    return recs


def kv_program(kind, ds):
    """COBOL source that copies a fixed-length sequential file into (LD) or out of (UL) a KSDS."""
    off, klen = ds.key
    alt_select = alt_fd = ""
    if ds.alt:
        aoff, alen = ds.alt
        alt_select = "\n               ALTERNATE RECORD KEY KS-ALT WITH DUPLICATES"
        alt_fd = (f"\n       01  KS-REC-ALT.\n"
                  + (f"           05  FILLER PIC X({aoff}).\n" if aoff else "")
                  + f"           05  KS-ALT PIC X({alen}).")
    key_fd = (f"           05  FILLER PIC X({off}).\n" if off else "") + f"           05  KS-KEY PIC X({klen})."
    if kind == "LD":
        body = """           OPEN INPUT SQ-FILE OUTPUT KS-FILE
           PERFORM UNTIL SQ-STAT NOT = '00'
               READ SQ-FILE
               IF SQ-STAT = '00'
                   MOVE SQ-REC TO KS-REC
                   WRITE KS-REC
                   IF KS-STAT(1:1) NOT = '0'
                       DISPLAY 'LOAD ERROR ' KS-STAT ' KEY ' KS-KEY
                       MOVE 8 TO RETURN-CODE
                   END-IF
               END-IF
           END-PERFORM"""
    else:
        body = """           OPEN INPUT KS-FILE OUTPUT SQ-FILE
           PERFORM UNTIL KS-STAT NOT = '00'
               READ KS-FILE NEXT
               IF KS-STAT = '00'
                   WRITE SQ-REC FROM KS-REC
               END-IF
           END-PERFORM"""
    return f"""       IDENTIFICATION DIVISION.
       PROGRAM-ID. {kind}{ds.name}.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT SQ-FILE ASSIGN TO SQFILE
               ORGANIZATION SEQUENTIAL FILE STATUS SQ-STAT.
           SELECT KS-FILE ASSIGN TO KSFILE
               ORGANIZATION INDEXED ACCESS DYNAMIC
               RECORD KEY KS-KEY{alt_select}
               FILE STATUS KS-STAT.
       DATA DIVISION.
       FILE SECTION.
       FD  SQ-FILE.
       01  SQ-REC PIC X({ds.reclen}).
       FD  KS-FILE.
       01  KS-REC.
{key_fd}
           05  FILLER PIC X({ds.reclen - off - klen}).{alt_fd}
       WORKING-STORAGE SECTION.
       01  SQ-STAT PIC XX VALUE '00'.
       01  KS-STAT PIC XX VALUE '00'.
       PROCEDURE DIVISION.
{body}
           CLOSE SQ-FILE KS-FILE
           GOBACK.
"""


def cobc(args, cwd):
    proc = subprocess.run(["cobc", *args], cwd=cwd, capture_output=True, text=True)
    noise = re.compile(r"_FORTIFY_SOURCE|location of the previous definition")
    msgs = "\n".join(l for l in (proc.stdout + proc.stderr).splitlines() if not noise.search(l))
    if proc.returncode != 0:
        raise RuntimeError(f"cobc {' '.join(args)} failed:\n{msgs}")


COBTUPDT = REPO / "app/app-transaction-type-db2/cbl/COBTUPDT.cbl"


def patch_cobtupdt(text):
    """EXEC SQL cannot be precompiled here. The three statements become CALLs to TRTYPE."""
    text = text.replace(
        "            EXEC SQL                                                    00410032\n"
        "                INCLUDE SQLCA                                           00420032\n"
        "            END-EXEC                                                    00430032",
        "       01 SQLCODE PIC S9(9) COMP VALUE ZERO.")
    text = text.replace(
        "            EXEC SQL INCLUDE DCLTRTYP END-EXEC                          00450046",
        "      * DCLTRTYP omitted: host variables are INPUT-REC-NUMBER and INPUT-REC-DESC")
    text = text.replace(
        "           EXEC SQL                                                     01140032\n"
        "                INSERT INTO CARDDEMO.TRANSACTION_TYPE                   01150032\n"
        "                (                                                       01160032\n"
        "                TR_TYPE,                                                01170032\n"
        "                TR_DESCRIPTION                                          01180032\n"
        "                )                                                       01190032\n"
        "                VALUES                                                  01200032\n"
        "                (                                                       01210032\n"
        "                :INPUT-REC-NUMBER,                                      01220033\n"
        "                :INPUT-REC-DESC                                         01230033\n"
        "                )                                                       01240032\n"
        "           END-EXEC.                                                    01250034",
        '           CALL "TRTYPE" USING "I"\n'
        "                INPUT-REC-NUMBER INPUT-REC-DESC SQLCODE.")
    text = text.replace(
        "           EXEC SQL                                                     01522037\n"
        "                UPDATE CARDDEMO.TRANSACTION_TYPE                        01523040\n"
        "                   SET TR_DESCRIPTION = :INPUT-REC-DESC                 01524041\n"
        "                 WHERE TR_TYPE = :INPUT-REC-NUMBER                      01525041\n"
        "           END-EXEC                                                     01526037",
        '           CALL "TRTYPE" USING "U"\n'
        "                INPUT-REC-NUMBER INPUT-REC-DESC SQLCODE")
    text = text.replace(
        "           EXEC SQL                                                     01900032\n"
        "                DELETE FROM CARDDEMO.TRANSACTION_TYPE                   01910032\n"
        "                 WHERE TR_TYPE = :INPUT-REC-NUMBER                      01920033\n"
        "           END-EXEC.                                                    01930034",
        '           CALL "TRTYPE" USING "D"\n'
        "                INPUT-REC-NUMBER INPUT-REC-DESC SQLCODE.")
    if "EXEC SQL" in text:
        raise RuntimeError("COBTUPDT still has an EXEC SQL after the harness patch")
    return text


def build_inputs_hash():
    h = hashlib.sha256()
    inputs = [*sorted((APP / "cbl").iterdir()), *sorted((APP / "cpy").iterdir()),
              *sorted((HERE / "stubs").glob("*.cbl")), COBTUPDT, Path(__file__)]
    for p in inputs:
        h.update(p.name.encode() + p.read_bytes())
    return h.hexdigest()


def build():
    """Compile everything into build/, skipped only when no input changed since the last build."""
    stamp = BUILD / ".inputs-sha256"
    digest = build_inputs_hash()
    if stamp.exists() and stamp.read_text() == digest:
        return
    if BUILD.exists():
        shutil.rmtree(BUILD)
    gen = BUILD / "src"
    gen.mkdir(parents=True)
    common = ["-fsign=EBCDIC", "-ftab-width=4", "-I", str(APP / "cpy")]
    for prog in PROGRAMS:
        src = next(APP.glob(f"cbl/{prog}.[cC][bB][lL]"))
        if prog in PATCHES:
            text = patched(src, [PATCHES[prog]])
            src = gen / f"{prog}.cbl"
            src.write_text(text)
        cobc(["-m", *common, "-o", str(BUILD / f"{prog}.so"), str(src)], BUILD)
    patched_update = gen / "COBTUPDT.cbl"
    patched_update.write_text(patch_cobtupdt(COBTUPDT.read_text()))
    cobc(["-m", "-o", str(BUILD / "COBTUPDT.so"), str(patched_update)], BUILD)
    BUILD_FIXED.mkdir()
    for prog in sorted({f.program for f in FIXES}):
        src = next(APP.glob(f"cbl/{prog}.[cC][bB][lL]"))
        fixed = gen / "fixed" / f"{prog}.cbl"
        fixed.parent.mkdir(exist_ok=True)
        fixed.write_text(patched(src, [*([PATCHES[prog]] if prog in PATCHES else []),
                                       *((f.pattern, f.replacement) for f in FIXES if f.program == prog)]))
        cobc(["-m", *common, "-o", str(BUILD_FIXED / f"{prog}.so"), str(fixed)], BUILD_FIXED)
    for stub in (HERE / "stubs").glob("*.cbl"):
        if stub.stem != "RUNPGM":
            cobc(["-m", "-free", "-o", str(BUILD / f"{stub.stem}.so"), str(stub)], BUILD)
    cobc(["-x", "-free", "-o", str(BUILD / "runpgm"), str(HERE / "stubs" / "RUNPGM.cbl")], BUILD)
    for ds in KSDS.values():
        for kind in ("LD", "UL"):
            src = gen / f"{kind}{ds.name}.cbl"
            src.write_text(kv_program(kind, ds))
            cobc(["-m", "-o", str(BUILD / f"{kind}{ds.name}.so"), str(src)], BUILD)
    stamp.write_text(digest)


class Run:
    """One end-to-end batch run in a scratch directory."""

    def __init__(self, work, library):
        self.work = work
        self.library = library
        self.artifacts = {}

    def path(self, name):
        return self.work / name

    def env(self, dds):
        env = dict(os.environ, COB_LIBRARY_PATH=os.pathsep.join(map(str, self.library)), COB_CURRENT_DATE=CLOCK)
        env.update({f"DD_{dd}": str(self.path(f)) for dd, f in dds.items()})
        return env

    def call(self, program, dds, parm=""):
        proc = subprocess.run([str(BUILD / "runpgm"), program, parm], cwd=self.work,
                              env=self.env(dds), capture_output=True)
        return proc.returncode, proc.stdout + proc.stderr

    def exec_pgm(self, job, program, dds, parm=""):
        rc, out = self.call(program, dds, parm)
        self.artifacts[f"{job}/{program}.sysout"] = out.decode("latin-1") + f"\n[return code {rc}]\n"
        return rc

    def write_seq(self, name, records):
        self.path(name).write_bytes(b"".join(records))

    def read_seq(self, name, reclen):
        data = self.path(name).read_bytes() if self.path(name).exists() else b""
        if len(data) % reclen:
            raise ValueError(f"{name}: {len(data)} bytes is not a multiple of {reclen}")
        return [data[i:i + reclen] for i in range(0, len(data), reclen)]

    def load_ksds(self, ds_name, records):
        """IDCAMS DEFINE CLUSTER + REPRO: replaces the KSDS with these records."""
        for p in self.work.glob(f"{ds_name}.ksds*"):
            p.unlink()
        self.write_seq(f"{ds_name}.repro", records)
        rc, out = self.call(f"LD{ds_name}", {"SQFILE": f"{ds_name}.repro", "KSFILE": f"{ds_name}.ksds"})
        if rc:
            raise RuntimeError(f"load {ds_name} failed: {out.decode('latin-1')}")

    def unload_ksds(self, ds_name):
        """IDCAMS REPRO of a KSDS to a flat file, in primary-key order."""
        ds = KSDS[ds_name]
        rc, out = self.call(f"UL{ds_name}", {"SQFILE": f"{ds_name}.unload", "KSFILE": f"{ds_name}.ksds"})
        if rc:
            raise RuntimeError(f"unload {ds_name} failed: {out.decode('latin-1')}")
        return self.read_seq(f"{ds_name}.unload", ds.reclen)

    def capture(self, job, label, records):
        self.artifacts[f"{job}/{label}"] = "".join(render(r) + "\n" for r in records)

    def capture_ksds(self, job, ds_name):
        self.capture(job, f"{ds_name}.ksds", self.unload_ksds(ds_name))


def render(record):
    """One record per golden line: printable bytes as-is, others as \\xNN, trailing spaces dropped."""
    text = "".join(chr(b) if 32 <= b < 127 and b != 0x5C else f"\\x{b:02x}" for b in record)
    return text.rstrip(" ")


def read_variable(path):
    """GnuCOBOL variable-length sequential records: 4-byte header, big-endian length in bytes 0-1."""
    data, recs, i = path.read_bytes(), [], 0
    while i < len(data):
        n = int.from_bytes(data[i:i + 2], "big")
        recs.append(data[i + 4:i + 4 + n])
        i += 4 + n
    return recs


def ksds_dd(*names, **renamed):
    dds = {n: f"{n}.ksds" for n in names}
    dds.update({dd: f"{ds}.ksds" for dd, ds in renamed.items()})
    return dds


# Jobs run in this order against one evolving set of datasets, like a nightly cycle.

def job_load(run):
    for ds in KSDS.values():
        run.load_ksds(ds.name, fixture_records(ds.fixture, ds.reclen) if ds.fixture else [])
    run.write_seq("DALYTRAN.ps", fixture_records("dailytran.txt", TRAN_LEN))


def job_readacct(run):
    run.exec_pgm("READACCT", "CBACT01C", {**ksds_dd(ACCTFILE="ACCTDATA"),
                 "OUTFILE": "ACCTDATA.pscomp", "ARRYFILE": "ACCTDATA.arryps", "VBRCFILE": "ACCTDATA.vbps"})
    run.capture("READACCT", "OUTFILE", run.read_seq("ACCTDATA.pscomp", 107))
    run.capture("READACCT", "ARRYFILE", run.read_seq("ACCTDATA.arryps", 110))
    run.capture("READACCT", "VBRCFILE", read_variable(run.path("ACCTDATA.vbps")))


def job_readcard(run):
    run.exec_pgm("READCARD", "CBACT02C", ksds_dd(CARDFILE="CARDDATA"))


def job_readxref(run):
    run.exec_pgm("READXREF", "CBACT03C", ksds_dd(XREFFILE="CARDXREF"))


def job_readcust(run):
    run.exec_pgm("READCUST", "CBCUS01C", ksds_dd(CUSTFILE="CUSTDATA"))


def job_validtran(run):
    """CBTRN01C has no JCL member; DDs follow its SELECT clauses."""
    run.exec_pgm("VALIDTRAN", "CBTRN01C", {"DALYTRAN": "DALYTRAN.ps", **ksds_dd(
        CUSTFILE="CUSTDATA", XREFFILE="CARDXREF", CARDFILE="CARDDATA", ACCTFILE="ACCTDATA", TRANFILE="TRANSACT")})


def job_posttran(run):
    run.exec_pgm("POSTTRAN", "CBTRN02C", {"DALYTRAN": "DALYTRAN.ps", "DALYREJS": "DALYREJS.ps", **ksds_dd(
        TRANFILE="TRANSACT", XREFFILE="CARDXREF", ACCTFILE="ACCTDATA", TCATBALF="TCATBALF")})
    run.capture("POSTTRAN", "DALYREJS", run.read_seq("DALYREJS.ps", 430))
    for ds in ("TRANSACT", "ACCTDATA", "TCATBALF"):
        run.capture_ksds("POSTTRAN", ds)


def job_intcalc(run):
    run.exec_pgm("INTCALC", "CBACT04C", {"TRANSACT": "SYSTRAN.ps", **ksds_dd(
        TCATBALF="TCATBALF", XREFFILE="CARDXREF", XREFFIL1="CARDXREF", ACCTFILE="ACCTDATA", DISCGRP="DISCGRP")},
        parm=INTCALC_PARM)
    run.capture("INTCALC", "SYSTRAN", run.read_seq("SYSTRAN.ps", TRAN_LEN))
    run.capture_ksds("INTCALC", "ACCTDATA")


def job_combtran(run):
    """TRANBKP (REPRO TRANSACT to backup, redefine empty) then COMBTRAN (SORT backup+SYSTRAN by TRAN-ID, REPRO)."""
    backup = run.unload_ksds("TRANSACT")
    run.write_seq("TRANSACT.bkup", backup)
    combined = sorted(backup + run.read_seq("SYSTRAN.ps", TRAN_LEN), key=lambda r: r[0:16])
    run.load_ksds("TRANSACT", combined)
    run.capture_ksds("COMBTRAN", "TRANSACT")


def job_tranrept(run):
    """REPROC backup, DFSORT INCLUDE on TRAN-PROC-TS(1:10) within the window, SORT by card number, CBTRN03C."""
    start, end = (d.encode() for d in TRANREPT_WINDOW)
    daily = [r for r in run.unload_ksds("TRANSACT") if start <= r[304:314] <= end]
    daily.sort(key=lambda r: int(r[262:278]))
    run.write_seq("TRANSACT.daly", daily)
    run.write_seq("DATEPARM", [f"{TRANREPT_WINDOW[0]} {TRANREPT_WINDOW[1]}".ljust(80).encode()])
    run.exec_pgm("TRANREPT", "CBTRN03C", {"TRANFILE": "TRANSACT.daly", "DATEPARM": "DATEPARM",
                 "TRANREPT": "TRANREPT.rpt", **ksds_dd(CARDXREF="CARDXREF", TRANTYPE="TRANTYPE", TRANCATG="TRANCATG")})
    run.capture("TRANREPT", "TRANREPT", run.read_seq("TRANREPT.rpt", 133))


def job_creastmt(run):
    """SORT FIELDS=(263,16,CH,A,1,16,CH,A) OUTREC FIELDS=(1:263,16,17:1,262,279:279,50) into TRXFL, then CBSTM03A."""
    trans = sorted(run.unload_ksds("TRANSACT"), key=lambda r: (r[262:278], r[0:16]))
    run.load_ksds("TRXFL", [(r[262:278] + r[0:262] + r[278:328]).ljust(TRAN_LEN) for r in trans])
    run.exec_pgm("CREASTMT", "CBSTM03A", {"STMTFILE": "STATEMNT.ps", "HTMLFILE": "STATEMNT.html", **ksds_dd(
        TRNXFILE="TRXFL", XREFFILE="CARDXREF", ACCTFILE="ACCTDATA", CUSTFILE="CUSTDATA")})
    run.capture("CREASTMT", "STMTFILE", run.read_seq("STATEMNT.ps", 80))
    run.capture("CREASTMT", "HTMLFILE", run.read_seq("STATEMNT.html", 100))


EDGES = HERE / "fixtures" / "posttran-edges"

# Crafted daily records in fixtures/posttran-edges/dailytran.txt, cloned from the first fixture row. Fixture
# accounts: 1 limit 2020.00 exp 2025-05-20 card 9680294154603697; 2 limit 6130.00 card 0923877193247330;
# 3 exp 2024-01-10 card 3999169246375885; 4 limit 3503.00 exp 2023-12-16 card 2988091353094312. Cycle
# credit/debit start at zero.
#   1 card not in xref (100)               2 xref points at missing account (101)
#   3 exactly at credit limit              4 one cent over limit (102)
#   5 on the expiration date               6 day after expiration (103)
#   7 over limit and expired (103)         8 credit of 50.00 on the maxed account
#   9 purchase of 50.01 after that credit  10 purchase of 50.00 after that credit (102: the credit did not free limit)


def logical_type_records(path):
    """One maintenance request a line: action, two-character type, description. Padded to COBTUPDT's 53 bytes."""
    records = []
    for line in path.read_text(encoding="latin-1").splitlines():
        op = line[:1].ljust(1)
        number = line[1:3].ljust(2)[:2]
        description = line[3:].ljust(50)[:50]
        records.append((op + number + description).encode("latin-1"))
    return records


def scenario_mnttrdb2(run):
    """MNTTRDB2 (COBTUPDT) against the transaction-type fixture. Not part of the daily or monthly chains."""
    run.load_ksds("TRANTYPE", fixture_records("trantype.txt", 60))
    codes = sorted({record[:2] for record in fixture_records("trancatg.txt", 60)})
    run.write_seq("CATTYPES.dat", codes)
    run.write_seq("INPFILE.dat", logical_type_records(HERE / "fixtures" / "mnttrdb2-inpfile.txt"))
    run.exec_pgm("MNTTRDB2", "COBTUPDT",
                 {"INPFILE": "INPFILE.dat", "KSFILE": "TRANTYPE.ksds", "CATFILE": "CATTYPES.dat"})
    run.capture("MNTTRDB2", "TRANTYPE", [record[:52] for record in run.unload_ksds("TRANTYPE")])


def scenario_posttran_edges(run):
    """POSTTRAN against fresh fixtures plus crafted daily transactions at each validation boundary."""
    job_load(run)
    xref = fixture_records("cardxref.txt", 50) + fixture_records(EDGES / "cardxref.txt", 50)
    run.load_ksds("CARDXREF", sorted(xref, key=lambda r: r[0:16]))
    run.write_seq("DALYTRAN.ps", fixture_records(EDGES / "dailytran.txt", TRAN_LEN))
    job = "POSTTRAN-EDGES"
    run.exec_pgm(job, "CBTRN02C", {"DALYTRAN": "DALYTRAN.ps", "DALYREJS": "DALYREJS.ps", **ksds_dd(
        TRANFILE="TRANSACT", XREFFILE="CARDXREF", ACCTFILE="ACCTDATA", TCATBALF="TCATBALF")})
    run.capture(job, "DALYREJS", run.read_seq("DALYREJS.ps", 430))
    run.capture(job, "TRANSACT.ksds", run.unload_ksds("TRANSACT"))
    run.capture(job, "ACCTDATA.ksds", run.unload_ksds("ACCTDATA")[:4])


JOBS = {
    "READACCT": job_readacct, "READCARD": job_readcard, "READXREF": job_readxref, "READCUST": job_readcust,
    "VALIDTRAN": job_validtran, "POSTTRAN": job_posttran, "INTCALC": job_intcalc, "COMBTRAN": job_combtran,
    "TRANREPT": job_tranrept, "CREASTMT": job_creastmt,
}


def compare(artifacts, golden_dir, update):
    failures = 0
    for name, text in sorted(artifacts.items()):
        golden = golden_dir / f"{name}.txt"
        if update:
            golden.parent.mkdir(parents=True, exist_ok=True)
            golden.write_text(text, encoding="latin-1")
            continue
        if not golden.exists():
            print(f"NEW   {name} (no golden; run with --update)")
            failures += 1
            continue
        expected = golden.read_text(encoding="latin-1")
        if expected == text:
            print(f"PASS  {name}")
        else:
            failures += 1
            print(f"FAIL  {name}")
            label = golden_dir.name
            sys.stdout.writelines(list(difflib.unified_diff(
                expected.splitlines(True), text.splitlines(True), f"{label}/{name}", f"actual/{name}", n=1))[:40])
    return failures


def cycle(work, library):
    """The nightly cycle plus the POSTTRAN-EDGES scenario, each in its own scratch directory."""
    run = Run(work / "cycle", library)
    run.work.mkdir(parents=True)
    job_load(run)
    for job in JOBS.values():
        job(run)
    edges = Run(work / "posttran-edges", library)
    edges.work.mkdir()
    scenario_posttran_edges(edges)
    types = Run(work / "mnttrdb2", library)
    types.work.mkdir()
    scenario_mnttrdb2(types)
    return {**run.artifacts, **edges.artifacts, **types.artifacts}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--update", action="store_true", help="rewrite golden/ and golden-fixed/ from this run")
    ap.add_argument("--job", action="append", choices=[*JOBS, "POSTTRAN-EDGES", "MNTTRDB2"],
                    help="only compare these jobs (all jobs still run, in order)")
    ap.add_argument("--keep", action="store_true", help="keep the scratch directory and print its path")
    ap.add_argument("--out", type=Path, help="also write this run's artifacts here, in the golden/ layout")
    args = ap.parse_args()
    if shutil.which("cobc") is None:
        sys.exit("cobc not found: install GnuCOBOL 3.x (apt-get install gnucobol3)")
    build()
    work = Path(tempfile.mkdtemp(prefix="carddemo-batch-"))
    selected = lambda arts: {k: v for k, v in arts.items() if not args.job or k.split("/")[0] in args.job}
    artifacts = selected(cycle(work / "legacy", [BUILD]))
    fixed = {k: v for k, v in selected(cycle(work / "fixed", [BUILD_FIXED, BUILD])).items() if artifacts[k] != v}
    if args.update and not args.job:
        for d in (GOLDEN, GOLDEN_FIXED):
            if d.exists():
                shutil.rmtree(d)
    failures = compare(artifacts, GOLDEN, args.update) + compare(fixed, GOLDEN_FIXED, args.update)
    stale = [p for p in GOLDEN_FIXED.rglob("*.txt") if not args.update
             and p.relative_to(GOLDEN_FIXED).with_suffix("").as_posix() in artifacts.keys() - fixed.keys()]
    for p in stale:
        print(f"STALE golden-fixed/{p.relative_to(GOLDEN_FIXED)} (the fixes no longer change it; run with --update)")
    failures += len(stale)
    for name, text in artifacts.items() if args.out else ():
        (args.out / f"{name}.txt").parent.mkdir(parents=True, exist_ok=True)
        (args.out / f"{name}.txt").write_text(text, encoding="latin-1")
    if args.keep:
        print(f"scratch: {work}")
    else:
        shutil.rmtree(work)
    if args.update:
        print(f"wrote {len(artifacts)} golden and {len(fixed)} golden-fixed files")
        return 0
    total = len(artifacts) + len(fixed)
    print(f"{total - failures}/{total} artifacts match golden ({len(fixed)} of them golden-fixed)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
