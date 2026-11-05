#!/usr/bin/env python3
"""Reference target adapter: answers run.py's external-target protocol with the legacy harness.

It proves the protocol end to end (python3 characterization/run.py --target legacy-adapter) and is
the template a Java adapter follows: read one request from stdin, run the case on a fresh copy of
the seed data, write {"steps": [observation, ...]} to stdout.
"""
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "harness"))


def main():
    request = json.load(sys.stdin)
    case = request["case"]
    with tempfile.TemporaryDirectory(prefix="carddemo-adapter-") as tmp:
        workdir = Path(tmp) / case["id"]
        if request["kind"] == "online":
            import online
            steps = online.run_journey([s["do"] for s in case["steps"]], workdir, case.get("setup"))
        else:
            import batch
            steps = [batch.run_job(case, workdir)]
    json.dump({"steps": steps}, sys.stdout)


if __name__ == "__main__":
    main()
