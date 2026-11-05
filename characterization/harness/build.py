"""Compile the legacy programs for the harness into characterization/.build/.

Online programs go through cicsprep.py and become GnuCOBOL modules loaded by the
cicsrun driver. Batch programs become executables. Rebuilds only what changed.
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
COBC = ["cobc", "-std=ibm", "-fsign=EBCDIC", "-fdefaultbyte=0"] + \
       [f"-I{p}" for p in COPY_PATHS]

ONLINE_PROGRAMS = ["COSGN00C", "COMEN01C", "COADM01C", "COUSR00C", "COUSR01C", "COUSR02C",
                   "COUSR03C", "COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
                   "COBIL00C", "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C"]
# Plain COBOL subprograms CALLed from online programs (no EXEC CICS).
ONLINE_SUBPROGRAMS = ["CSUTLDTC"]


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


def build_subprogram(program, dest):
    src = source(program)
    out = dest / f"{program}.so"
    if stale(out, src, HERE / "build.py"):
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
    pass


if __name__ == "__main__":
    build_online()
    print(f"built {len(ONLINE_PROGRAMS)} online programs into {ONLINE}")
