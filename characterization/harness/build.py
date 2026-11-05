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

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
BUILD = REPO / "characterization" / ".build"
ONLINE = BUILD / "online"
BATCH = BUILD / "batch"
COPY_PATHS = [HERE / "runtime" / "copy", REPO / "app" / "cpy", REPO / "app" / "cpy-bms"]
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
# Source ranges that cannot run off z/OS, commented out before compiling: (first, last) line text.
# CBSTM03A walks PSA -> TCB -> TIOT from address 0 to DISPLAY the job's DD names.
DISABLED = {"CBSTM03A": ("SET ADDRESS OF PSA-BLOCK   TO PSAPTR.", "DISPLAY ': ' TIOCDDNM ' -- null  UCB'")}


def source(program):
    for ext in (".cbl", ".CBL"):
        p = REPO / "app" / "cbl" / f"{program}{ext}"
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


def build_runtime():
    ONLINE.mkdir(parents=True, exist_ok=True)
    stub = HERE / "runtime" / "cicsstub.c"
    exe = ONLINE / "cicsrun"
    if stale(exe, stub):
        flags = subprocess.check_output(["cob-config", "--cflags"], text=True).split()
        libs = subprocess.check_output(["cob-config", "--libs"], text=True).split()
        run(["gcc", "-O1", "-o", str(exe), str(stub), *flags, *libs, "-rdynamic"], ONLINE)
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


def build_batch():
    BATCH.mkdir(parents=True, exist_ok=True)
    for name, src in (("CEE3ABD", "lestubs.c"), ("COBDATFT", "cobdatft.c")):
        c = HERE / "runtime" / src
        if stale(BATCH / f"{name}.so", c):
            run(["gcc", "-O1", "-shared", "-fPIC", "-o", str(BATCH / f"{name}.so"), str(c)], BATCH)
    for program in BATCH_SUBPROGRAMS:
        build_subprogram(program, BATCH)

    launcher = HERE / "runtime" / "jobrun.c"
    if stale(BATCH / "jobrun", launcher):
        flags = subprocess.check_output(["cob-config", "--cflags"], text=True).split()
        libs = subprocess.check_output(["cob-config", "--libs"], text=True).split()
        run(["gcc", "-O1", "-o", str(BATCH / "jobrun"), str(launcher), *flags, *libs], BATCH)
    with ThreadPoolExecutor(max_workers=os.cpu_count()) as pool:
        list(pool.map(lambda p: build_subprogram(p, BATCH), BATCH_PROGRAMS))


if __name__ == "__main__":
    build_online()
    print(f"built {len(ONLINE_PROGRAMS)} online programs into {ONLINE}")
    build_batch()
    print(f"built {len(BATCH_PROGRAMS)} batch programs into {BATCH}")
