"""Run an app/jcl member that calls only IBM utilities, under a reference model of those utilities.

GnuCOBOL has no IEFBR14, IDCAMS or DFSORT, so these members cannot run as legacy code. This module models
the five members the reference chains need (TRANTYPE, TRANCATG, TCATBALF, DISCGRP, PRTCATBL) step by step
from their JCL and the IBM documentation of each utility. It is a model, not an execution of the shipped
system; the rules it applies are listed with each step function.

A case's "job" is:
  {"library": MEMBER,
   "datasets": {DSN: {"layout": DATASET, "seed": true?, "records": [record fields]}
                     | {"lines": [text], "reclen": n}},     cataloged before the job; order is kept
   "observe":  {DSN: {"reclen": n, "decode_as": DATASET?}}}  datasets read after the job; (0) names allowed
Observed: the job completion, each step's completion, keyed dataset diffs, and the observed datasets.
"""
from pathlib import Path

from datasets import Datasets

QUALIFIER = "AWS.M2.CARDDEMO."
ERROR_LIMIT = 4
POS_OVERPUNCH = "{ABCDEFGHI"
NEG_OVERPUNCH = "}JKLMNOPQR"
RELOADS = {
    "TRANTYPE": "STEP15",
    "TRANCATG": "STEP15",
    "TCATBALF": "STEP15",
    "DISCGRP": "STEP15",
}


class JclError(Exception):
    pass


class Catalog:
    """Sequential data sets and GDG generations by name. Keyed clusters live in the run directory's .dat files."""

    def __init__(self):
        self.named = {}
        self.generations = {}

    def resolve(self, dsn, new_generations):
        if dsn.endswith(")"):
            base, rel = dsn[:-1].split("(")
            rel = int(rel)
            if rel == 1:
                return ("gen-new", base)
            gens = self.generations.get(base, [])
            if -rel >= len(gens):
                raise JclError(f"{dsn} not cataloged")
            return ("gen", base, len(gens) - 1 + rel)
        return ("named", dsn)

    def read(self, dsn, new_generations):
        ref = self.resolve(dsn, new_generations)
        if ref[0] == "gen-new":
            if ref[1] not in new_generations:
                raise JclError(f"{dsn} not cataloged")
            return new_generations[ref[1]]
        if ref[0] == "gen":
            return self.generations[ref[1]][ref[2]]
        if dsn not in self.named:
            raise JclError(f"{dsn} not cataloged")
        return self.named[dsn]


def zoned(raw):
    """DFSORT ZD: the digits of every byte and the sign from the last byte's zone."""
    text = raw.decode("latin-1")
    last = text[-1]
    sign = 1
    if last in POS_OVERPUNCH:
        last = str(POS_OVERPUNCH.index(last))
    elif last in NEG_OVERPUNCH:
        sign, last = -1, str(NEG_OVERPUNCH.index(last))
    digits = text[:-1] + last
    if not digits.isdigit():
        raise ValueError(f"not zoned decimal: {text!r}")
    return sign, digits


def edit_t(raw, mask):
    """EDIT=(mask) of a ZD field with T digits and literals. With no SIGNS, the sign is not printed."""
    _, digits = zoned(raw)
    count = mask.count("T")
    digits = digits.rjust(count, "0")[-count:]
    out, i = [], 0
    for ch in mask:
        if ch == "T":
            out.append(digits[i])
            i += 1
        else:
            out.append(ch)
    return "".join(out).encode("latin-1")


def zd_key(raw):
    sign, digits = zoned(raw)
    return sign * int(digits)


def run_library(case, run_dir):
    run_dir = Path(run_dir)
    if run_dir.exists():
        import shutil
        shutil.rmtree(run_dir)
    run_dir.mkdir(parents=True)
    data = Datasets()
    data.materialize(run_dir, case.get("setup", {}))
    job = case["job"]
    catalog = Catalog()
    for dsn, spec in job.get("datasets", {}).items():
        if "lines" in spec:
            catalog.named[dsn] = [l.encode("latin-1").ljust(spec["reclen"], b" ")[:spec["reclen"]]
                                  for l in spec["lines"]]
        else:
            layout = spec["layout"]
            recs = list(data.seed_records(layout)) if spec.get("seed") else []
            recs += [data.encode(layout, r) for r in spec.get("records", [])]
            catalog.named[dsn] = recs
    before = data.snapshot(run_dir)
    member = job["library"]
    if member in RELOADS:
        steps = reload_job(member, data, run_dir, catalog)
    elif member == "PRTCATBL":
        steps = prtcatbl(data, run_dir, catalog)
    else:
        raise ValueError(f"no model for member {member}")
    obs = {"completion": completion(steps), "steps": dict(steps)}
    changes = data.diff(before, data.snapshot(run_dir))
    if changes:
        obs["files"] = changes
    for dsn, spec in job.get("observe", {}).items():
        try:
            recs = catalog.read(dsn, {})
        except JclError:
            obs.setdefault("outputs", {})[dsn] = None
            continue
        if "decode_as" in spec:
            rows = [data.decode(spec["decode_as"], r) for r in recs]
        else:
            rows = [r.decode("latin-1").rstrip() for r in recs]
        obs.setdefault("outputs", {})[dsn] = rows
    return obs


def completion(steps):
    worst = 0
    for _, label in steps:
        if not label.startswith("RC="):
            return label
        worst = max(worst, int(label[3:]))
    return f"RC={worst}"


def write_cluster(run_dir, name, recs):
    (Path(run_dir) / f"{name}.dat").write_bytes(b"".join(r.rstrip(b" ") + b"\n" for r in recs))


def reload_job(member, data, run_dir, catalog):
    """STEP05 IDCAMS DELETE <cluster> CLUSTER then SET MAXCC = 0: condition code 0 whether or not it existed.
    STEP10 IDCAMS DEFINE CLUSTER: a new empty cluster, condition code 0.
    STEP15 IDCAMS REPRO INFILE(<member>.PS) OUTFILE(<cluster>): the input DD is DISP=SHR, so an uncataloged
    input fails allocation and the step ends JCL ERROR after STEP05 and STEP10 have emptied the cluster.
    A REPRO into an empty KSDS loads in ascending key order: a record whose key is not above the last one
    written is rejected (duplicate or out of sequence) and counts toward ERRORLIMIT, default 4. The fourth
    rejection ends the command with condition code 12; fewer end it with 8.
    """
    ds = data.datasets[member]
    k0, kl = ds["key"]
    steps = [("STEP05", "RC=0")]
    write_cluster(run_dir, member, [])
    steps.append(("STEP10", "RC=0"))
    try:
        source = catalog.read(QUALIFIER + member + ".PS", {})
    except JclError:
        steps.append((RELOADS[member], "JCL ERROR"))
        return steps
    loaded, errors, rc, last = [], 0, 0, None
    for rec in source:
        key = rec[k0:k0 + kl]
        if last is not None and key <= last:
            errors += 1
            if errors >= ERROR_LIMIT:
                rc = 12
                break
            rc = 8
            continue
        loaded.append(rec)
        last = key
    write_cluster(run_dir, member, loaded)
    steps.append((RELOADS[member], f"RC={rc}"))
    return steps


def prtcatbl(data, run_dir, catalog):
    """DELDEF IEFBR14 with TCATBALF.REPT DISP=(MOD,DELETE): the report data set is gone, RC 0.
    STEP05R.PRC001 (REPROC) IDCAMS REPRO of the TCATBALF cluster, in key order, to TCATBALF.BKUP(+1), RC 0.
    STEP10R SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A) with the ZD symbols compared as
    signed numbers and CH as bytes, then OUTREC acct-id, X, type, X, cd, X, the balance as EDIT=(TTTTTTTTT.TT),
    9X. A ZD symbol where only p,m is allowed copies the bytes. The 41-byte record is truncated to SORTOUT's
    LRECL 40 and the step ends RC 0 (DFSORT's TRUNC=RC0 default). The edit mask has no sign.
    """
    steps = [("DELDEF", "RC=0")]
    catalog.named.pop(QUALIFIER + "TCATBALF.REPT", None)
    cluster = data.read(run_dir, "TCATBALF") or []
    new_generations = {QUALIFIER + "TCATBALF.BKUP": [r.ljust(50, b" ")[:50] for r in cluster]}
    steps.append(("STEP05R.PRC001", "RC=0"))
    records = sorted(new_generations[QUALIFIER + "TCATBALF.BKUP"],
                     key=lambda r: (zd_key(r[0:11]), r[11:13], zd_key(r[13:17])))
    out = []
    for r in records:
        rec = r[0:11] + b" " + r[11:13] + b" " + r[13:17] + b" " + edit_t(r[17:28], "TTTTTTTTT.TT") + b" " * 9
        out.append(rec[:40])
    for base, recs in new_generations.items():
        catalog.generations.setdefault(base, []).append(recs)
        catalog.generations[base] = catalog.generations[base][-5:]
    catalog.named[QUALIFIER + "TCATBALF.REPT"] = out
    steps.append(("STEP10R", "RC=0"))
    return steps
