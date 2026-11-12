#!/usr/bin/env python3
"""Run CardDemo batch programs under GnuCOBOL and diff their outputs against golden masters.

    python3 characterization/batch/harness.py            # build, run all jobs, diff against golden/
    python3 characterization/batch/harness.py --update   # rewrite golden/ from this run
    python3 characterization/batch/harness.py --job POSTTRAN --keep

Each job mirrors one JCL member under app/jcl. COBOL programs run unmodified except
CBSTM03A (see PATCHES). IDCAMS REPRO and DFSORT steps are emulated in Python.
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
BUILD = HERE / "build"

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


def build_inputs_hash():
    h = hashlib.sha256()
    inputs = [*sorted((APP / "cbl").iterdir()), *sorted((APP / "cpy").iterdir()),
              *sorted((HERE / "stubs").glob("*.cbl")), Path(__file__)]
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
            pattern, repl = PATCHES[prog]
            text, n = re.subn(pattern, repl, src.read_text(errors="replace"), flags=re.S)
            if n != 1:
                raise RuntimeError(f"patch for {prog} no longer applies")
            src = gen / f"{prog}.cbl"
            src.write_text(text)
        cobc(["-m", *common, "-o", str(BUILD / f"{prog}.so"), str(src)], BUILD)
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

    def __init__(self, work):
        self.work = work
        self.artifacts = {}

    def path(self, name):
        return self.work / name

    def env(self, dds):
        env = dict(os.environ, COB_LIBRARY_PATH=str(BUILD), COB_CURRENT_DATE=CLOCK)
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


def zoned(cents, digits=11):
    """S9(n)V99 DISPLAY with the trailing EBCDIC sign overpunch the fixtures use."""
    text = str(abs(cents)).rjust(digits, "0")
    last = int(text[-1])
    over = ("}JKLMNOPQR" if cents < 0 else "{ABCDEFGHI")[last]
    return (text[:-1] + over).encode()


def daily_tran(tran_id, card, cents, orig_date):
    """A DALYTRAN record cloned from the first fixture row with the fields the edge cases vary."""
    rec = bytearray(fixture_records("dailytran.txt", TRAN_LEN)[0])
    rec[0:16] = tran_id.encode()
    rec[132:143] = zoned(cents)
    rec[262:278] = card.encode()
    rec[278:304] = f"{orig_date} 12:00:00.000000".encode()
    return bytes(rec)


# Fixture accounts: 1 limit 2020.00 exp 2025-05-20 card 9680294154603697; 2 limit 6130.00
# card 0923877193247330; 3 exp 2024-01-10 card 3999169246375885; 4 limit 3503.00 exp 2023-12-16
# card 2988091353094312. Cycle credit/debit start at zero.
POSTTRAN_EDGES = [
    daily_tran("EDGE000000000001", "0000000000000000", 1000, "2022-06-10"),   # 100 card not in xref
    daily_tran("EDGE000000000002", "1111222233334444", 1000, "2022-06-10"),   # 101 xref points at missing account
    daily_tran("EDGE000000000003", "9680294154603697", 202000, "2022-06-10"), # exactly at credit limit
    daily_tran("EDGE000000000004", "0923877193247330", 613001, "2022-06-10"), # 102 one cent over limit
    daily_tran("EDGE000000000005", "3999169246375885", 1000, "2024-01-10"),   # on the expiration date
    daily_tran("EDGE000000000006", "3999169246375885", 1000, "2024-01-11"),   # 103 day after expiration
    daily_tran("EDGE000000000007", "2988091353094312", 999999, "2023-12-17"), # over limit and expired
    daily_tran("EDGE000000000008", "9680294154603697", -5000, "2022-06-10"),  # credit on a maxed account
    daily_tran("EDGE000000000009", "9680294154603697", 5001, "2022-06-10"),   # purchase after that credit
]


def scenario_posttran_edges(run):
    """POSTTRAN against fresh fixtures plus crafted daily transactions at each validation boundary."""
    job_load(run)
    xref = fixture_records("cardxref.txt", 50) + [b"1111222233334444".ljust(16) + b"000000099" + b"99999999999".ljust(25)]
    run.load_ksds("CARDXREF", sorted(xref, key=lambda r: r[0:16]))
    run.write_seq("DALYTRAN.ps", POSTTRAN_EDGES)
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


def compare(artifacts, update):
    failures = 0
    for name, text in sorted(artifacts.items()):
        golden = GOLDEN / f"{name}.txt"
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
            sys.stdout.writelines(list(difflib.unified_diff(
                expected.splitlines(True), text.splitlines(True), f"golden/{name}", f"actual/{name}", n=1))[:40])
    return failures


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--update", action="store_true", help="rewrite golden files from this run")
    ap.add_argument("--job", action="append", choices=[*JOBS, "POSTTRAN-EDGES"], help="only compare these jobs (all jobs still run, in order)")
    ap.add_argument("--keep", action="store_true", help="keep the scratch directory and print its path")
    args = ap.parse_args()
    if shutil.which("cobc") is None:
        sys.exit("cobc not found: install GnuCOBOL 3.x (apt-get install gnucobol3)")
    build()
    work = Path(tempfile.mkdtemp(prefix="carddemo-batch-"))
    run = Run(work / "cycle")
    run.work.mkdir()
    job_load(run)
    for job in JOBS.values():
        job(run)
    edges = Run(work / "posttran-edges")
    edges.work.mkdir()
    scenario_posttran_edges(edges)
    artifacts = {**run.artifacts, **edges.artifacts}
    artifacts = {k: v for k, v in artifacts.items() if not args.job or k.split("/")[0] in args.job}
    if args.update and GOLDEN.exists() and not args.job:
        shutil.rmtree(GOLDEN)
    failures = compare(artifacts, args.update)
    if args.keep:
        print(f"scratch: {work}")
    else:
        shutil.rmtree(work)
    if args.update:
        print(f"wrote {len(artifacts)} golden files to {GOLDEN.relative_to(REPO)}")
        return 0
    print(f"{len(artifacts) - failures}/{len(artifacts)} artifacts match golden")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
