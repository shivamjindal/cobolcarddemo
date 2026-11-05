#!/usr/bin/env python3
"""Check that a target is worth driving. Prints one line per check.

  python3 characterization/doctor.py                # legacy harness (read-only)
  python3 characterization/doctor.py --target java  # Java slice: toolchain, Postgres, and CC00 through the adapter
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "harness"))


def check(label, ok, detail=""):
    print(f"{'ok  ' if ok else 'FAIL'} {label}{': ' + detail if detail else ''}")
    return ok


def first_line(cmd):
    if not shutil.which(cmd[0]):
        return ""
    proc = subprocess.run(cmd, capture_output=True, text=True)
    return (proc.stdout or proc.stderr).split("\n")[0]


def legacy():
    results = []
    version = first_line(["cobc", "--version"])
    results.append(check("GnuCOBOL 3.x on PATH", "3." in version, version or "apt-get install -y gnucobol"))
    results.append(check("gcc and cob-config", bool(shutil.which("gcc") and shutil.which("cob-config"))))
    import build
    built = [p for p in build.ONLINE_PROGRAMS if (build.ONLINE / f"{p}.so").exists()]
    results.append(check("online modules built", len(built) == len(build.ONLINE_PROGRAMS),
                         f"{len(built)}/{len(build.ONLINE_PROGRAMS)} in {build.ONLINE} (run.py builds them)"))
    results.append(check("cicsrun driver built", (build.ONLINE / "cicsrun").exists()))
    results.append(check("jobrun launcher built", (build.BATCH / "jobrun").exists()))
    batch_built = [p for p in build.BATCH_PROGRAMS if (build.BATCH / f"{p}.so").exists()]
    results.append(check("batch programs built", len(batch_built) == len(build.BATCH_PROGRAMS),
                         f"{len(batch_built)}/{len(build.BATCH_PROGRAMS)} in {build.BATCH}"))
    if all(results):
        import online
        obs = online.run_journey([{"transid": "CC00"}], ROOT / ".build" / "runs" / "_doctor")[0]
        results.append(check("CC00 shows the sign-on map", obs.get("map") == "COSGN0A", str(obs.get("map"))))
    return results


def java():
    results = []
    version = first_line(["java", "-version"])
    results.append(check("Java 21+ on PATH", any(f'"{v}' in version for v in range(21, 40)), version or "install a JDK 21"))
    results.append(check("Maven on PATH", bool(shutil.which("mvn")), "see java/README.md"))
    env = dict(os.environ, PGPASSWORD=os.environ.get("CARDDEMO_JDBC_PASSWORD", "carddemo"))
    pg = subprocess.run(["psql", "-h", "localhost", "-U", os.environ.get("CARDDEMO_JDBC_USER", "carddemo"),
                         "-d", "postgres", "-tAc", "SELECT 1"], capture_output=True, text=True, env=env) \
        if shutil.which("psql") else None
    results.append(check("Postgres answers as carddemo", bool(pg and pg.stdout.strip() == "1"),
                         (pg.stderr.strip() if pg else "psql not found") or ""))
    if all(results):
        command = json.loads((ROOT / "targets.json").read_text())["targets"]["java"]["command"]
        request = {"kind": "online", "feature": "signon", "datasets": str(ROOT / "fixtures" / "datasets.json"),
                   "case": {"id": "_doctor", "steps": [{"do": {"transid": "CC00"}}]}}
        proc = subprocess.run(command, input=json.dumps(request), capture_output=True, text=True,
                              cwd=ROOT.parent, timeout=600)
        obs = json.loads(proc.stdout)["steps"][0] if proc.returncode == 0 else {}
        results.append(check("CC00 shows the sign-on map through the java adapter", obs.get("map") == "COSGN0A",
                             str(obs.get("map")) if proc.returncode == 0 else proc.stderr[-500:]))
    return results


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", choices=["legacy", "java"], default="legacy")
    results = java() if ap.parse_args().target == "java" else legacy()
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
