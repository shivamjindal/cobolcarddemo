#!/usr/bin/env python3
"""Check that every online spec is grounded in the CardDemo source. See SCHEMA.md."""
import re
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
SPECS = Path(__file__).resolve().parent / "specs"
TOP_KEYS = ("program", "transaction", "evidence", "sources", "files", "navigation", "cases")
FILE_OPS = {"READ", "READ-UPDATE", "WRITE", "REWRITE", "DELETE", "STARTBR", "READNEXT", "READPREV", "ENDBR"}
VIAS = {"XCTL", "LINK", "RETURN-TRANSID", "SEND-TEXT"}
AIDS = {"ENTER", "CLEAR", "OTHER", "PA1", "PA2", "PA3"} | {f"PF{n}" for n in range(1, 25)}
RESPS = {"NOTFND", "DUPREC", "DUPKEY", "IOERR", "NOTOPEN", "DISABLED", "LENGERR", "INVREQ", "OTHER"}
ENTRIES = {"first-time", "reenter", "xctl-from"}


def cobol_text(path):
    """Source text in columns 8-72 with continued literals joined, whitespace collapsed."""
    out = []
    for raw in path.read_text(errors="replace").splitlines():
        line = raw.ljust(72)
        if line[6] in "*/":
            continue
        body = line[7:72]
        if line[6] == "-" and out:
            stripped = body.lstrip()
            out[-1] += stripped[1:] if stripped[:1] in "'\"" else stripped
        else:
            out.append(body)
    return re.sub(r"\s+", " ", " ".join(out))


def csd_names():
    text = (REPO / "app/csd/CARDDEMO.CSD").read_text(errors="replace")
    find = lambda kind: set(re.findall(rf"DEFINE {kind}\((\w+)\)", text))
    return find("FILE"), find("TRANSACTION")


def bms_fields(mapset):
    cpy = REPO / "app/cpy-bms" / f"{mapset}.CPY"
    return set(re.findall(r"^\s+\d+\s+([A-Z0-9-]+)\s", cpy.read_text(errors="replace"), re.M)) if cpy.exists() else None


def fragments(message):
    return [f.strip() for f in re.split(r"\{[^}]*\}", message) if len(f.strip()) >= 3]


def validate(path, csd_files, csd_trans, programs):
    errors = []
    err = lambda msg: errors.append(f"{path.name}: {msg}")
    spec = yaml.safe_load(path.read_text())
    if not isinstance(spec, dict):
        return [f"{path.name}: not a mapping"]
    for key in TOP_KEYS:
        if key not in spec:
            err(f"missing top-level key '{key}'")
    if errors:
        return errors
    prog = spec["program"]
    if path.stem != prog:
        err(f"file name does not match program {prog}")
    if spec["evidence"] != "derived":
        err("evidence must be 'derived'")
    if spec["transaction"] not in csd_trans:
        err(f"transaction {spec['transaction']} not in CSD")

    sources = {}
    for src in spec["sources"]:
        p = REPO / src
        if not p.exists():
            err(f"source {src} does not exist")
        else:
            sources[src] = p
    corpus = " ".join(cobol_text(p) for p in sources.values())
    fields = None
    if spec.get("screen"):
        fields = bms_fields(spec["screen"]["mapset"])
        if fields is None:
            err(f"no BMS copybook for mapset {spec['screen']['mapset']}")

    known_targets = programs | csd_trans
    for f in spec["files"]:
        if f["name"] not in csd_files:
            err(f"file {f['name']} not defined in CSD")
        for op in f.get("ops", []):
            if op not in FILE_OPS:
                err(f"file {f['name']} has unknown op {op}")
    for nav in spec["navigation"]:
        if nav.get("via") not in VIAS:
            err(f"navigation via {nav.get('via')} not one of {sorted(VIAS)}")
        if nav.get("via") != "SEND-TEXT" and nav.get("to") not in known_targets:
            err(f"navigation target {nav.get('to')} is not a program or transid")

    seen = set()
    for case in spec["cases"]:
        cid = case.get("id", "<no id>")
        where = f"case {cid}"
        if cid in seen:
            err(f"{where}: duplicate id")
        seen.add(cid)
        if not str(cid).startswith(prog):
            err(f"{where}: id must start with {prog}")
        for key in ("summary", "given", "when", "then", "source"):
            if key not in case:
                err(f"{where}: missing '{key}'")
        if any(k not in case for k in ("given", "when", "then", "source")):
            continue
        given, when, then, source = case["given"], case["when"], case["then"], case["source"]

        if given.get("entry") not in ENTRIES:
            err(f"{where}: given.entry must be one of {sorted(ENTRIES)}")
        for fname in (given.get("files") or {}):
            if fname not in csd_files:
                err(f"{where}: given.files {fname} not in CSD")
        for cond in given.get("file_conditions") or []:
            if cond.get("file") not in csd_files:
                err(f"{where}: file_conditions file {cond.get('file')} not in CSD")
            if cond.get("op") not in FILE_OPS:
                err(f"{where}: file_conditions op {cond.get('op')} unknown")
            if cond.get("resp") not in RESPS:
                err(f"{where}: file_conditions resp {cond.get('resp')} not one of {sorted(RESPS)}")
        if when.get("aid") not in AIDS:
            err(f"{where}: when.aid {when.get('aid')} unknown")

        sfile = source.get("file")
        lines = source.get("lines") or []
        if sfile not in sources:
            err(f"{where}: source.file {sfile} not listed in sources")
        elif len(lines) != 2 or not (1 <= lines[0] <= lines[1] <= len(sources[sfile].read_text(errors='replace').splitlines())):
            err(f"{where}: source.lines {lines} outside {sfile}")

        msg = then.get("message")
        if msg is not None:
            norm = lambda s: re.sub(r"\s+", " ", s)
            for frag in fragments(str(msg)):
                if norm(frag) not in corpus:
                    err(f"{where}: message fragment {frag!r} not found in sources")

        if fields is not None:
            names = list((when.get("fields") or {}).keys()) + list((then.get("fields") or {}).keys())
            names += [n for n in (then.get("cursor"), then.get("message_field")) if n]
            for name in names:
                if name not in fields:
                    err(f"{where}: field {name} not in {spec['screen']['mapset']}.CPY")

        transfer = then.get("transfer")
        if transfer:
            if transfer.get("via") not in VIAS:
                err(f"{where}: transfer.via {transfer.get('via')} unknown")
            if transfer.get("via") != "SEND-TEXT" and transfer.get("to") not in known_targets:
                err(f"{where}: transfer.to {transfer.get('to')} is not a program or transid")
        for eff in then.get("file_effects") or []:
            if eff.get("file") not in csd_files:
                err(f"{where}: file_effects file {eff.get('file')} not in CSD")
            if eff.get("op") not in FILE_OPS:
                err(f"{where}: file_effects op {eff.get('op')} unknown")
    return errors


def main(argv):
    csd_files, csd_trans = csd_names()
    programs = {p.stem.upper() for p in (REPO / "app/cbl").iterdir()}
    paths = [Path(a) for a in argv] or sorted(SPECS.glob("*.yaml"))
    if not paths:
        print("no specs found")
        return 1
    errors, cases = [], 0
    for p in paths:
        errors += validate(p, csd_files, csd_trans, programs)
        cases += len((yaml.safe_load(p.read_text()) or {}).get("cases") or [])
    for e in errors:
        print(e)
    print(f"{len(paths)} specs, {cases} cases, {len(errors)} errors")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
