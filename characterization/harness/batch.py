"""Run one CardDemo batch program under GnuCOBOL against the seed datasets.

A case's "job" names the program and binds each DD to one of:
  "DATASET"                              keyed datasets load as indexed files, others as
                                         fixed-length sequential files; changes are diffed
  {"dataset": "X", "as": "sequential"}   a keyed dataset handed over as a flat file
  {"lines": [...], "reclen": n}          an inline sequential input (a PARM card)
  {"output": "NAME", "reclen": n}        a sequential output captured as records
  {"output": "NAME", "recfm": "V"}       a variable-length output
    outputs may add "decode_as": "DATASET" (decode with that record layout) or "hex": true
  {"derive": "creastmt-trxfl"}           the CREASTMT SORT step output (see derive_trxfl)
Observed: return code (or user abend), SYSOUT lines, dataset diffs, output records.
A "job" with "library" instead of "program" runs a utility-only app/jcl member (see library.py).
"""
import os
import re
import subprocess
import zlib
from pathlib import Path

import build
from datasets import Datasets

FIXED_CLOCK = "2026/11/05 10:00:00.00"
LOADER = """       IDENTIFICATION DIVISION.
       PROGRAM-ID. {name}.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT FLAT ASSIGN TO FLATFILE
               ORGANIZATION LINE SEQUENTIAL.
           SELECT KSDS ASSIGN TO KSDSFILE
               ORGANIZATION INDEXED ACCESS DYNAMIC
               RECORD KEY K-PRIME{alt_select}
               FILE STATUS KS.
       DATA DIVISION.
       FILE SECTION.
       FD  FLAT.
       01  FLAT-REC PIC X({reclen}).
       FD  KSDS.
       01  KSDS-REC.
{fields}
       WORKING-STORAGE SECTION.
       01  KS     PIC XX.
       01  MODE-X PIC X(6).
       01  EOF    PIC X VALUE 'N'.
       PROCEDURE DIVISION.
           ACCEPT MODE-X FROM ENVIRONMENT 'KSDSMODE'
           IF MODE-X = 'LOAD'
               OPEN INPUT FLAT OUTPUT KSDS
               PERFORM UNTIL EOF = 'Y'
                   READ FLAT AT END MOVE 'Y' TO EOF
                   NOT AT END
                       MOVE FLAT-REC TO KSDS-REC
                       WRITE KSDS-REC
                       IF KS NOT = '00'
                           DISPLAY 'LOAD STATUS ' KS ' ' FLAT-REC(1:40)
                               UPON SYSERR
                       END-IF
                   END-READ
               END-PERFORM
           ELSE
               OPEN INPUT KSDS OUTPUT FLAT
               PERFORM UNTIL EOF = 'Y'
                   READ KSDS NEXT AT END MOVE 'Y' TO EOF
                   NOT AT END
                       MOVE KSDS-REC TO FLAT-REC
                       WRITE FLAT-REC
                   END-READ
               END-PERFORM
           END-IF
           CLOSE FLAT KSDS
           GOBACK.
"""


def loader_source(name, reclen, key, alt_keys):
    spans = sorted([(key[0], key[1], "K-PRIME")] +
                   [(a[0], a[1], f"K-ALT{i}") for i, a in enumerate(alt_keys)])
    fields, pos = [], 0
    for off, length, label in spans:
        if off > pos:
            fields.append(f"           05  FILLER PIC X({off - pos}).")
        fields.append(f"           05  {label} PIC X({length}).")
        pos = off + length
    if pos < reclen:
        fields.append(f"           05  FILLER PIC X({reclen - pos}).")
    alt = "".join(f"\n               ALTERNATE RECORD KEY K-ALT{i} WITH DUPLICATES"
                  for i in range(len(alt_keys)))
    return LOADER.format(name=name, reclen=reclen, fields="\n".join(fields), alt_select=alt)


def loader(reclen, key, alt_keys):
    sig = "_".join(map(str, [reclen, *key] + [x for a in alt_keys for x in a]))
    name = f"KLOAD{zlib.crc32(sig.encode()) % 10**6:06d}"
    exe = build.BATCH / f"kload_{sig}"
    if not exe.exists():
        src = build.BATCH / "src" / f"kload_{sig}.cbl"
        src.parent.mkdir(parents=True, exist_ok=True)
        src.write_text(loader_source(name, reclen, key, alt_keys))
        build.run(["cobc", "-x", "-o", str(exe), str(src)], build.BATCH)
    return exe


def derive_trxfl(data, run_dir):
    """CREASTMT STEP010: SORT FIELDS=(263,16,CH,A,1,16,CH,A),
    OUTREC FIELDS=(1:263,16,17:1,262,279:279,50) over TRANSACT."""
    out = []
    for r in data.read(run_dir, "TRANSACT"):
        rec = r[262:278] + r[0:262] + r[278:328]
        out.append(rec.ljust(350, b" "))
    out.sort(key=lambda rec: rec[:32])
    return out


def _records(raw, reclen, recfm):
    if recfm == "V":
        out, i = [], 0
        while i + 4 <= len(raw):
            n = int.from_bytes(raw[i:i + 2], "big")
            out.append(raw[i + 4:i + 4 + n])
            i += 4 + n
        return out
    return [raw[i:i + reclen] for i in range(0, len(raw), reclen)]


def run_job(case, run_dir):
    if "library" in case["job"]:
        import library
        return library.run_library(case, run_dir)
    run_dir = Path(run_dir)
    if run_dir.exists():
        import shutil
        shutil.rmtree(run_dir)
    (run_dir / "dd").mkdir(parents=True)
    data = Datasets()
    data.materialize(run_dir, case.get("setup", {}))
    job = case["job"]
    env = dict(os.environ, COB_LIBRARY_PATH=str(build.BATCH), COB_CURRENT_DATE=FIXED_CLOCK,
               COB_VARSEQ_FORMAT="0")
    before = data.snapshot(run_dir)
    keyed, outputs = {}, {}
    for dd, spec in job["dd"].items():
        path = run_dir / "dd" / dd
        env[f"DD_{dd}"] = str(path)
        if isinstance(spec, str):
            ds = data.datasets[spec]
            if ds["key"]:
                alt = ds.get("alt_keys", [])
                exe = loader(ds["reclen"], ds["key"], alt)
                proc = subprocess.run([str(exe)], env=dict(env, KSDSMODE="LOAD",
                                      DD_FLATFILE=str(run_dir / f"{spec}.dat"), DD_KSDSFILE=str(path)),
                                      capture_output=True, text=True)
                bad = [line for line in proc.stderr.splitlines()
                       if line.strip() and not line.startswith("LOAD STATUS 02 ")]
                if proc.returncode or bad:
                    raise RuntimeError(f"loading {spec}: {proc.stdout}{proc.stderr}")
                keyed[dd] = (spec, exe)
            else:
                path.write_bytes(b"".join(data.read(run_dir, spec)))
        elif "dataset" in spec:
            path.write_bytes(b"".join(data.read(run_dir, spec["dataset"])))
        elif "lines" in spec:
            path.write_bytes(b"".join(l.encode("latin-1").ljust(spec["reclen"], b" ")
                                      for l in spec["lines"]))
        elif spec.get("derive") == "creastmt-trxfl":
            flat = run_dir / "TRXFL.dat"
            flat.write_bytes(b"".join(r.rstrip(b" ") + b"\n" for r in derive_trxfl(data, run_dir)))
            exe = loader(350, [0, 32], [])
            subprocess.run([str(exe)], env=dict(env, KSDSMODE="LOAD", DD_FLATFILE=str(flat),
                           DD_KSDSFILE=str(path)), check=True)
        elif "output" in spec:
            outputs[dd] = spec
    proc = subprocess.run([str(build.BATCH / "jobrun"), job["program"], job.get("parm", "")], env=env, cwd=run_dir,
                          capture_output=True, timeout=300)
    stderr = proc.stderr.decode("latin-1")
    abend = re.search(r"HARNESS-ABEND (\S+)", stderr)
    obs = {"completion": abend.group(1) if abend else f"RC={proc.returncode}",
           "sysout": [l.rstrip() for l in proc.stdout.decode("latin-1").splitlines()]}
    if not abend and proc.returncode not in (0, 4, 8, 12, 16):
        obs["_stderr"] = stderr[-2000:]
    for dd, (spec, exe) in keyed.items():
        flat = run_dir / f"{spec}.dat"
        subprocess.run([str(exe)], env=dict(env, KSDSMODE="UNLOAD", DD_FLATFILE=str(flat),
                       DD_KSDSFILE=str(run_dir / "dd" / dd)), check=True, capture_output=True)
    changes = data.diff(before, data.snapshot(run_dir))
    if changes:
        obs["files"] = changes
    for dd, spec in outputs.items():
        path = run_dir / "dd" / dd
        raw = path.read_bytes() if path.exists() else b""
        recs = _records(raw, spec.get("reclen", 0), spec.get("recfm", "F"))
        if "decode_as" in spec:
            rows = [data.decode(spec["decode_as"], r.ljust(data.datasets[spec["decode_as"]]["reclen"], b" "))
                    for r in recs]
        elif spec.get("hex"):
            rows = [r.hex() for r in recs]
        else:
            rows = [r.decode("latin-1").rstrip() for r in recs]
        obs.setdefault("outputs", {})[spec["output"]] = rows
    return obs
