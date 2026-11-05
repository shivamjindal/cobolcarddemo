"""Drive CardDemo online programs through pseudo-conversational journeys.

A journey is a list of steps. Each step is what a user does at the terminal:
type a transaction id on a clear screen, or fill fields and press a key. The
driver runs one CICS task per step with the cicsrun stub, carries the commarea
and next transaction id like CICS would, and reports what the user can observe:
the screen, whether the session ended, and the records each step changed.
"""
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

import build
import screen
from datasets import Datasets

CSD = build.REPO / "app" / "csd" / "CARDDEMO.CSD"
FIXED_CLOCK = "2026/11/05 10:00:00"


def transactions():
    text = CSD.read_text(encoding="latin-1")
    return dict(re.findall(r"DEFINE TRANSACTION\((\w+)\).*?PROGRAM\((\w+)\)", text, re.S))


class Session:
    def __init__(self, workdir, setup=None):
        self.dir = Path(workdir)
        if self.dir.exists():
            shutil.rmtree(self.dir)
        self.dir.mkdir(parents=True)
        self.data = Datasets()
        self.data.materialize(self.dir, setup or {})
        cfg = []
        for name, spec in self.data.cics_files.items():
            ds = self.data.datasets[spec["dataset"]]
            key = spec.get("alt_key", ds["key"])
            line = f"{name} {spec['dataset']}.dat {ds['reclen']} {key[0]} {key[1]}"
            if "alt_key" in spec:
                line += f" {ds['key'][0]} {ds['key'][1]}"
            cfg.append(line)
        (self.dir / "files.cfg").write_text("\n".join(cfg) + "\n")
        self.trans = transactions()
        self.terminal = screen.Terminal()
        self.next_transid = None
        self.commarea = b""
        self.task = 0

    def step(self, step):
        aid = step.get("aid", "ENTER")
        if aid not in screen.AID:
            raise ValueError(f"unknown key {aid}")
        if "transid" in step:
            trnid, commarea, mapbuf = step["transid"], b"", None
            self.terminal = screen.Terminal()
        else:
            if not self.next_transid:
                raise ValueError("session has ended; start the step with a transid")
            trnid, commarea = self.next_transid, self.commarea
            mapbuf = self.terminal.type_and_send(aid, step.get("fields"))
        program = self.trans[trnid]
        self.task += 1
        task_dir = self.dir
        (task_dir / "events.jsonl").write_text("")
        lines = [f"program={program}", f"trnid={trnid}", f"aid={screen.AID[aid].hex()}",
                 f"commarea={commarea.hex()}"]
        if mapbuf is not None:
            lines.append(f"map={mapbuf.hex()}")
        (task_dir / "task.in").write_text("\n".join(lines) + "\n")
        before = self.data.snapshot(self.dir)
        env = dict(os.environ, COB_LIBRARY_PATH=str(build.ONLINE), COB_CURRENT_DATE=FIXED_CLOCK)
        proc = subprocess.run([str(build.ONLINE / "cicsrun"), str(self.dir)], env=env,
                              capture_output=True, text=True, timeout=60)
        events = [json.loads(l) for l in (task_dir / "events.jsonl").read_text().splitlines() if l]
        shutil.copy(task_dir / "events.jsonl", task_dir / f"events.{self.task:02d}.jsonl")
        obs, trail = {}, []
        self.next_transid = None
        for ev in events:
            kind = ev["ev"]
            if kind == "start":
                trail.append(ev["program"])
            elif kind == "send_map":
                self.terminal.send_map(ev["mapset"], ev["map"], ev["flags"], bytes.fromhex(ev["data"]))
            elif kind == "send_text":
                self.terminal.send_text(bytes.fromhex(ev["data"]).decode("latin-1"))
            elif kind == "return":
                self.next_transid = ev["transid"] or None
                self.commarea = bytes.fromhex(ev["data"])
            elif kind == "abend":
                obs["abend"] = ev["code"]
            elif kind == "unsupported":
                obs["unsupported"] = ev["command"]
            elif kind == "writeq_td":
                text = bytes.fromhex(ev["data"]).decode("latin-1").rstrip()
                obs.setdefault("queues", {}).setdefault(ev["queue"], []).append(text)
        if proc.returncode or not events:
            obs["runtime_error"] = (proc.stderr or proc.stdout).strip()[-2000:]
        obs.update(self.terminal.snapshot())
        if not self.next_transid and "abend" not in obs:
            obs["session"] = "ended"
        changes = self.data.diff(before, self.data.snapshot(self.dir))
        if changes:
            obs["files"] = changes
        obs["_programs"] = trail
        return obs


def run_journey(steps, workdir, setup=None):
    s = Session(workdir, setup)
    return [s.step(st) for st in steps]
