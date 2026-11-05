#!/usr/bin/env python3
"""Cross-check inventory.json against raw facts pulled from app/ with plain regexes.

Independent of build_inventory.py's parser, so a parser regression shows up as a failed check.

    python3 scripts/inventory/check_inventory.py inventory.json [--app app]
"""
import argparse
import json
import re
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[2]
    ap = argparse.ArgumentParser()
    ap.add_argument("json", type=Path)
    ap.add_argument("--app", type=Path, default=root / "app")
    args = ap.parse_args()
    inv = json.loads(args.json.read_text())
    progs, failures = inv["programs"], []

    def check(ok, msg):
        if not ok:
            failures.append(msg)

    sources = {p.stem.upper() for p in args.app.rglob("*") if p.suffix.lower() in (".cbl", ".asm")}
    check(sources <= set(progs), f"programs missing from inventory: {sorted(sources - set(progs))}")

    for csd in (p for p in args.app.rglob("*") if p.suffix.lower() == ".csd"):
        text = re.sub(r"\s+", " ", csd.read_text().upper())
        for tid, pgm in re.findall(r"DEFINE TRANSACTION\((\w+)\).*?PROGRAM\((\w+)\)", text):
            check(tid in progs.get(pgm, {}).get("transactions", []), f"{csd.name}: {tid} -> {pgm} not recorded")
        for fname, dsn in re.findall(r"DEFINE FILE\((\w+)\).*?DSNAME\(([^)]+)\)", text):
            check(inv["cics_files"].get(fname) == dsn, f"{csd.name}: FILE {fname} -> {dsn} not recorded")

    for jcl in sorted(args.app.glob("**/jcl/*")):
        for step, pgm in re.findall(r"^//(\S+)\s+EXEC\s+PGM=(\w+)", jcl.read_text().upper(), re.M):
            if pgm in progs:
                check(any(s.startswith(f"{jcl.stem.upper()}.{step}") for s in progs[pgm]["jcl_steps"]),
                      f"{jcl.name}: step {step} running {pgm} not recorded")

    for name, p in progs.items():
        if not p["source"]:
            continue
        text = (root / p["source"]).read_text(errors="replace").upper()
        code = "\n".join(l[6:72] for l in text.splitlines() if len(l) > 6 and l[6] not in "*/")
        for target in re.findall(r"\bCALL\s+'(\w+)'", code):
            check(any(c["target"] == target for c in p["calls"]), f"{name}: CALL '{target}' not recorded")
        for target in re.findall(r"XCTL\s+PROGRAM\s*\(\s*'(\w+)'", code):
            check(any(c["target"] == target for c in p["calls"]), f"{name}: XCTL '{target}' not recorded")
        is_cics = "EXEC CICS" in code
        check(is_cics == (p["type"] == "online-cics"), f"{name}: type {p['type']} but EXEC CICS present={is_cics}")
        for verb in ("WRITE", "REWRITE", "DELETE"):
            if re.search(rf"EXEC CICS {verb}\b(?!Q)", code):
                check(any(verb.lower() in a["modes"] for a in p["accesses"] if a["via"] == "CICS"),
                      f"{name}: EXEC CICS {verb} not reflected in access modes")

    for f in failures:
        print("FAIL", f)
    print(f"{len(failures)} failures")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
