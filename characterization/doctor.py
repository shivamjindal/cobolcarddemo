#!/usr/bin/env python3
"""Read-only check that the legacy harness is worth driving. Prints one line per check."""
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "harness"))


def check(label, ok, detail=""):
    print(f"{'ok  ' if ok else 'FAIL'} {label}{': ' + detail if detail else ''}")
    return ok


def main():
    results = []
    cobc = shutil.which("cobc")
    version = subprocess.run(["cobc", "--version"], capture_output=True, text=True).stdout.split("\n")[0] if cobc else ""
    results.append(check("GnuCOBOL 3.x on PATH", "3." in version, version or "apt-get install -y gnucobol"))
    results.append(check("gcc and cob-config", bool(shutil.which("gcc") and shutil.which("cob-config"))))
    import build
    built = [p for p in build.ONLINE_PROGRAMS if (build.ONLINE / f"{p}.so").exists()]
    results.append(check("online modules built", len(built) == len(build.ONLINE_PROGRAMS),
                         f"{len(built)}/{len(build.ONLINE_PROGRAMS)} in {build.ONLINE} (run.py builds them)"))
    results.append(check("cicsrun driver built", (build.ONLINE / "cicsrun").exists()))
    batch_built = [p for p in build.BATCH_PROGRAMS if (build.BATCH / f"{p}.so").exists()]
    results.append(check("batch programs built", len(batch_built) == len(build.BATCH_PROGRAMS),
                         f"{len(batch_built)}/{len(build.BATCH_PROGRAMS)} in {build.BATCH}"))
    if all(results):
        import online
        obs = online.run_journey([{"transid": "CC00"}], ROOT / ".build" / "runs" / "_doctor")[0]
        results.append(check("CC00 shows the sign-on map", obs.get("map") == "COSGN0A", str(obs.get("map"))))
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
