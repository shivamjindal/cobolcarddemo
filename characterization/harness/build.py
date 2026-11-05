"""Compile the legacy programs for the harness into characterization/.build/.

Online programs go through cicsprep.py and become GnuCOBOL modules loaded by the
cicsrun driver. Batch programs become modules launched by jobrun. Rebuilds only what changed.
"""
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import cicsprep
import sqlprep

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
BUILD = REPO / "characterization" / ".build"
ONLINE = BUILD / "online"
BATCH = BUILD / "batch"
# The optional Db2 transaction-type extension builds into its own libraries, so a case sees
# its programs only when it installs the extension (see online.py and batch.py).
DB2_EXT = REPO / "app" / "app-transaction-type-db2"
ONLINE_DB2 = BUILD / "online-db2"
BATCH_DB2 = BUILD / "batch-db2"
COPY_PATHS = [HERE / "runtime" / "copy", REPO / "app" / "cpy", REPO / "app" / "cpy-bms",
              DB2_EXT / "cpy", DB2_EXT / "cpy-bms"]
COBC = ["cobc", "-std=ibm", "-fsign=EBCDIC", "-fdefaultbyte=0", "-ftab-width=4"] + \
       [f"-I{p}" for p in COPY_PATHS]

ONLINE_PROGRAMS = ["COSGN00C", "COMEN01C", "COADM01C", "COUSR00C", "COUSR01C", "COUSR02C",
                   "COUSR03C", "COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
                   "COBIL00C", "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C"]
# Plain COBOL subprograms CALLed from online programs (no EXEC CICS).
ONLINE_SUBPROGRAMS = ["CSUTLDTC"]
BATCH_PROGRAMS = ["CBACT01C", "CBACT02C", "CBACT03C", "CBACT04C", "CBCUS01C", "CBTRN02C",
                  "CBTRN03C", "CBSTM03A"]
BATCH_SUBPROGRAMS = ["CBSTM03B"]
DB2_ONLINE_PROGRAMS = ["COTRTLIC", "COTRTUPC"]
DB2_BATCH_PROGRAMS = ["COBTUPDT"]
DB2_INCLUDES = [DB2_EXT / "dcl", DB2_EXT / "cpy"]
# Source ranges that cannot run off z/OS, commented out before compiling: (first, last) line text.
# CBSTM03A walks PSA -> TCB -> TIOT from address 0 to DISPLAY the job's DD names.
DISABLED = {"CBSTM03A": ("SET ADDRESS OF PSA-BLOCK   TO PSAPTR.", "DISPLAY ': ' TIOCDDNM ' -- null  UCB'")}


def source(program):
    for d in (REPO / "app" / "cbl", DB2_EXT / "cbl"):
        for ext in (".cbl", ".CBL"):
            p = d / f"{program}{ext}"
            if p.exists():
                return p
    raise FileNotFoundError(program)


def stale(target, *inputs):
    return not target.exists() or any(i.stat().st_mtime > target.stat().st_mtime for i in inputs)


def run(cmd, cwd):
    proc = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if proc.returncode:
        raise RuntimeError(f"{' '.join(map(str, cmd))}\n{proc.stdout}{proc.stderr}")


def build_online_program(program):
    src = source(program)
    out = ONLINE / f"{program}.so"
    translated = ONLINE / "src" / f"{program}.cbl"
    if not stale(out, src, HERE / "cicsprep.py", HERE / "build.py"):
        return
    translated.parent.mkdir(parents=True, exist_ok=True)
    translated.write_text(cicsprep.translate(src.read_text(encoding="latin-1"), program),
                          encoding="latin-1")
    run(COBC + ["-m", "-o", str(out), str(translated)], ONLINE)


def build_sql_program(program, dest, online):
    """EXEC SQL first (sqlprep), then EXEC CICS (cicsprep) for an online program."""
    src = source(program)
    out = dest / f"{program}.so"
    deps = [src, HERE / "sqlprep.py", HERE / "cicsprep.py", HERE / "build.py",
            *[p for d in DB2_INCLUDES for p in d.iterdir()]]
    if not stale(out, *deps):
        return
    translated = dest / "src" / f"{program}.cbl"
    translated.parent.mkdir(parents=True, exist_ok=True)
    cobol, statements = sqlprep.translate(src.read_text(encoding="latin-1"), program, DB2_INCLUDES)
    if online:
        cobol = cicsprep.translate(cobol, program)
    translated.write_text(cobol, encoding="latin-1")
    (dest / f"{program}.sql").write_text(statements, encoding="latin-1")
    run(COBC + ["-m", "-o", str(out), str(translated)], dest)


def disable(program, src, dest):
    first, last = DISABLED[program]
    lines = src.read_text(encoding="latin-1").split("\n")
    start = next(i for i, l in enumerate(lines) if first in l)
    end = next(i for i in range(start, len(lines)) if last in lines[i]) + 1
    if lines[end].strip() == "END-IF.":
        end += 1
    for i in range(start, end):
        lines[i] = lines[i][:6].ljust(6) + "*" + lines[i][7:]
    out = dest / "src" / src.name
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines), encoding="latin-1")
    return out


def build_subprogram(program, dest):
    src = source(program)
    out = dest / f"{program}.so"
    if stale(out, src, HERE / "build.py"):
        if program in DISABLED:
            src = disable(program, src, dest)
        run(COBC + ["-m", "-o", str(out), str(src)], dest)


def link(exe, main, cwd):
    """A driver with the SQL runtime linked in, exported so CALL 'SQLEXEC' and 'DSNTIAC' resolve."""
    sql = HERE / "runtime" / "sqlstub.c"
    if stale(exe, main, sql):
        flags = subprocess.check_output(["cob-config", "--cflags"], text=True).split()
        libs = subprocess.check_output(["cob-config", "--libs"], text=True).split()
        pq = subprocess.check_output(["pkg-config", "--cflags", "--libs", "libpq"], text=True).split()
        run(["gcc", "-O1", "-o", str(exe), str(main), str(sql), *flags, *pq, *libs, "-rdynamic"], cwd)


def build_runtime():
    ONLINE.mkdir(parents=True, exist_ok=True)
    link(ONLINE / "cicsrun", HERE / "runtime" / "cicsstub.c", ONLINE)
    le = HERE / "runtime" / "lestubs.c"
    if stale(ONLINE / "CEEDAYS.so", le):
        run(["gcc", "-O1", "-shared", "-fPIC", "-o", str(ONLINE / "CEEDAYS.so"), str(le)], ONLINE)


def build_online():
    if not shutil.which("cobc"):
        sys.exit("cobc not found: install GnuCOBOL 3.x (apt-get install gnucobol)")
    build_runtime()
    with ThreadPoolExecutor(max_workers=os.cpu_count()) as pool:
        list(pool.map(build_online_program, ONLINE_PROGRAMS))
        list(pool.map(lambda p: build_subprogram(p, ONLINE), ONLINE_SUBPROGRAMS))
    ONLINE_DB2.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=os.cpu_count()) as pool:
        list(pool.map(lambda p: build_sql_program(p, ONLINE_DB2, True), DB2_ONLINE_PROGRAMS))


def build_batch():
    BATCH.mkdir(parents=True, exist_ok=True)
    for name, src in (("CEE3ABD", "lestubs.c"), ("COBDATFT", "cobdatft.c")):
        c = HERE / "runtime" / src
        if stale(BATCH / f"{name}.so", c):
            run(["gcc", "-O1", "-shared", "-fPIC", "-o", str(BATCH / f"{name}.so"), str(c)], BATCH)
    for program in BATCH_SUBPROGRAMS:
        build_subprogram(program, BATCH)

    link(BATCH / "jobrun", HERE / "runtime" / "jobrun.c", BATCH)
    with ThreadPoolExecutor(max_workers=os.cpu_count()) as pool:
        list(pool.map(lambda p: build_subprogram(p, BATCH), BATCH_PROGRAMS))
    BATCH_DB2.mkdir(parents=True, exist_ok=True)
    for program in DB2_BATCH_PROGRAMS:
        build_sql_program(program, BATCH_DB2, False)


if __name__ == "__main__":
    build_online()
    print(f"built {len(ONLINE_PROGRAMS)} online programs into {ONLINE}")
    build_batch()
    print(f"built {len(BATCH_PROGRAMS)} batch programs into {BATCH}")
    print(f"built {len(DB2_ONLINE_PROGRAMS) + len(DB2_BATCH_PROGRAMS)} Db2 extension programs "
          f"into {ONLINE_DB2} and {BATCH_DB2}")
