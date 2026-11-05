#!/usr/bin/env python3
"""Run CardDemo characterization cases against a target and compare with the goldens.

  python3 characterization/run.py                       # every case, legacy harness
  python3 characterization/run.py --feature signon      # one feature
  python3 characterization/run.py --case signon-admin   # cases whose id contains this
  python3 characterization/run.py --target java         # a target from targets.json
  python3 characterization/run.py --record --case X     # rewrite goldens from the legacy run

Exit status is 0 only when every selected case that the target supports passes.
"""
import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "harness"))

CASES = ROOT / "cases"
TARGETS = ROOT / "targets.json"
RUNS = ROOT / ".build" / "runs"


def load_suites(features, case_filter):
    for path in sorted(CASES.rglob("*.json")):
        suite = json.loads(path.read_text())
        if features and suite["feature"] not in features:
            continue
        cases = [c for c in suite["cases"] if not case_filter or case_filter in c["id"]]
        if cases:
            yield path, suite, cases


def public(obs):
    if isinstance(obs, dict):
        return {k: public(v) for k, v in obs.items() if not k.startswith("_")}
    if isinstance(obs, list):
        return [public(v) for v in obs]
    return obs


def compare(expected, actual, subset, path="$"):
    diffs = []
    if isinstance(expected, dict) and isinstance(actual, dict):
        keys = expected.keys() if subset else expected.keys() | actual.keys()
        for k in sorted(keys):
            if k not in actual:
                diffs.append(f"{path}.{k}: expected {json.dumps(expected[k])}, missing")
            elif k not in expected:
                diffs.append(f"{path}.{k}: unexpected {json.dumps(actual[k])}")
            else:
                diffs += compare(expected[k], actual[k], subset, f"{path}.{k}")
    elif expected != actual:
        diffs.append(f"{path}: expected {json.dumps(expected)}, got {json.dumps(actual)}")
    return diffs


def run_legacy(suite, case, workdir):
    install = suite.get("install", [])
    if suite["kind"] == "online":
        import online
        return online.run_journey([s["do"] for s in case["steps"]], workdir, case.get("setup"), install)
    import batch
    return [batch.run_job(case, workdir, install)]


def run_external(target, suite, case):
    request = {"kind": suite["kind"], "feature": suite["feature"],
               "datasets": str(ROOT / "fixtures" / "datasets.json"),
               "case": {k: case[k] for k in ("id", "setup", "steps", "job") if k in case}}
    if suite.get("install"):
        request["case"]["install"] = suite["install"]
    if "steps" in request["case"]:
        request["case"]["steps"] = [{"do": s["do"]} for s in case["steps"]]
    proc = subprocess.run(target["command"], input=json.dumps(request), capture_output=True,
                          text=True, cwd=ROOT.parent, timeout=600)
    if proc.returncode:
        raise RuntimeError(proc.stderr[-2000:])
    return json.loads(proc.stdout)["steps"]


def expectations(suite, case):
    if suite["kind"] == "online":
        return [s.get("expect") for s in case["steps"]], [s.get("match") == "subset" for s in case["steps"]]
    return [case.get("expect")], [case.get("match") == "subset"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", default="legacy")
    ap.add_argument("--feature", action="append")
    ap.add_argument("--case")
    ap.add_argument("--record", action="store_true", help="write legacy observations into the goldens")
    ap.add_argument("--evidence", help="directory for actual observations and the report")
    args = ap.parse_args()

    targets = json.loads(TARGETS.read_text())["targets"]
    if args.target not in targets:
        sys.exit(f"unknown target {args.target}; known: {', '.join(targets)}")
    target = targets[args.target]
    if args.record and args.target != "legacy":
        sys.exit("--record only captures goldens from the legacy target")
    evidence = Path(args.evidence or ROOT / ".evidence" / f"{time.strftime('%Y%m%d-%H%M%S')}-{args.target}")
    evidence.mkdir(parents=True, exist_ok=True)

    if args.target == "legacy":
        import build
        build.build_online()
        build.build_batch()

    results = []
    for path, suite, cases in load_suites(args.feature, args.case):
        supported = target.get("features", [])
        for case in cases:
            row = {"id": case["id"], "feature": suite["feature"], "provenance": case["provenance"]}
            results.append(row)
            if args.target == "legacy" and case["provenance"] == "source-derived":
                row["status"] = "NOT-RUN"
                row["why"] = "source-derived: the legacy harness cannot execute this path"
                continue
            if args.target != "legacy" and supported != "*" and suite["feature"] not in supported:
                row["status"] = "SKIP"
                row["why"] = f"feature {suite['feature']} not ported to {args.target}"
                continue
            workdir = RUNS / case["id"]
            try:
                actual = run_legacy(suite, case, workdir) if args.target == "legacy" \
                    else run_external(target, suite, case)
            except Exception as exc:  # report and keep going; one broken case must not hide the rest
                row["status"], row["why"] = "ERROR", str(exc)[-2000:]
                continue
            (evidence / f"{case['id']}.json").write_text(json.dumps(actual, indent=1))
            if workdir.exists():
                shutil.copytree(workdir, evidence / "runs" / case["id"], dirs_exist_ok=True)
            if args.record:
                for step, obs in zip(case.get("steps", [case]), actual):
                    obs = public(obs)
                    if step.get("match") == "subset" and step.get("expect"):
                        obs = {k: obs.get(k) for k in step["expect"]}
                    step["expect"] = obs
                row["status"] = "RECORDED"
                continue
            diffs = []
            exp, subset = expectations(suite, case)
            if all(e is None for e in exp):
                diffs.append("no golden recorded")
            for i, (e, s, a) in enumerate(zip(exp, subset, actual)):
                if e is not None:
                    diffs += [f"step {i + 1}: {d}" for d in compare(e, public(a), s)]
            row["status"] = "FAIL" if diffs else "PASS"
            if diffs:
                row["diffs"] = diffs
        if args.record:
            path.write_text(json.dumps(suite, indent=2, ensure_ascii=False) + "\n")

    counts = {}
    for r in results:
        counts[r["status"]] = counts.get(r["status"], 0) + 1
    (evidence / "report.json").write_text(json.dumps({"target": args.target, "counts": counts,
                                                      "results": results}, indent=1))
    for r in results:
        if r["status"] not in ("PASS", "RECORDED"):
            print(f"{r['status']:8} {r['id']}: {r.get('why', '')}")
            for d in r.get("diffs", [])[:20]:
                print(f"           {d}")
    print(f"{args.target}: " + ", ".join(f"{v} {k}" for k, v in sorted(counts.items())))
    print(f"evidence: {evidence}")
    sys.exit(1 if counts.get("FAIL") or counts.get("ERROR") else 0)


if __name__ == "__main__":
    main()
