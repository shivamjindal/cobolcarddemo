"""Drive CardDemo online programs through pseudo-conversational journeys.

A journey is a list of steps. Each step is what a user does at the terminal:
type a transaction id on a clear screen, or fill fields and press a key. The
driver runs one CICS task per step with the cicsrun stub, carries the commarea
and next transaction id like CICS would, and reports what the user can observe:
the screen, whether the session ended, and the records each step changed.

A suite's "install" adds optional extensions (EXTENSIONS): their CSD transactions and their
program library join the region. A step {"mq": {"queue", "trigger", "messages"}} puts the
messages on the queue and runs the trigger transaction once with an MQTM naming the queue,
like the CICS trigger monitor; it observes the messages programs put per queue, the messages
left unread on the trigger queue, TD writes and record changes, and leaves the terminal alone.
"""
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

import build
import db2
import screen
from datasets import Datasets

CSD = build.REPO / "app" / "csd" / "CARDDEMO.CSD"
FIXED_CLOCK = "2026/11/05 10:00:00.00"
# Optional extensions a suite can install: its CSD group, its program libraries (None when its
# programs live in the base library), and whether it runs on Db2.
EXTENSIONS = {"transaction-type-db2": {"csd": build.DB2_EXT / "csd" / "CRDDEMOD.csd", "db2": True,
                                       "online": build.ONLINE_DB2, "batch": build.BATCH_DB2},
              "pending-authorizations": {"csd": build.AUTH_EXT / "csd" / "CRDDEMO2.csd", "db2": False,
                                         "online": build.ONLINE_AUTH, "batch": None},
              "vsam-mq": {"csd": build.MQ_EXT / "csd" / "CRDDEMOM.csd", "db2": False,
                          "online": build.ONLINE_MQ, "batch": None}}


def transactions(install=()):
    out = {}
    for csd in [CSD] + [EXTENSIONS[x]["csd"] for x in install]:
        text = csd.read_text(encoding="latin-1")
        out.update(re.findall(r"DEFINE TRANSACTION\((\w+)\).*?PROGRAM\((\w+)\)", text, re.S))
    return out


def db2_env(install, kind):
    """Library path additions for the installed extensions, and Db2 settings when one runs on Db2."""
    libs = [str(EXTENSIONS[x][kind]) for x in install if EXTENSIONS[x][kind]]
    sql = [str(EXTENSIONS[x][kind]) for x in install if EXTENSIONS[x]["db2"]]
    if not sql:
        return libs, {}
    return libs, {"CARDDEMO_SQL_DIR": sql[0], "CARDDEMO_DB2_CONNINFO": db2.CONNINFO}


def uses_db2(install):
    return any(EXTENSIONS[x]["db2"] for x in install)


def mqtm(queue):
    """CICS trigger message (MQTM, CMQTML) naming the queue that fired."""
    tm = bytearray(b" " * 684)
    tm[0:4] = b"TM  "
    tm[4:8] = (1).to_bytes(4, "big")
    tm[8:56] = queue.encode("latin-1").ljust(48, b" ")
    tm[168:172] = (0).to_bytes(4, "big")
    return bytes(tm)


class Session:
    def __init__(self, workdir, setup=None, install=()):
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
        self.install = list(install)
        self.trans = transactions(self.install)
        if uses_db2(self.install):
            db2.reset(self.data, self.dir)
        libs, extra = db2_env(self.install, "online")
        self.env = dict(os.environ, COB_LIBRARY_PATH=":".join(libs + [str(build.ONLINE)]),
                        COB_CURRENT_DATE=FIXED_CLOCK, DLI_PSBLIB=str(build.PSBLIB), **extra)
        self.mq_ids = 0
        self.terminal = screen.Terminal()
        self.next_transid = None
        self.commarea = b""
        self.task = 0

    def run_task(self, lines):
        (self.dir / "events.jsonl").write_text("")
        (self.dir / "task.in").write_text("\n".join(lines) + "\n")
        self.task += 1
        proc = subprocess.run([str(build.ONLINE / "cicsrun"), str(self.dir)], env=self.env,
                              capture_output=True, text=True, timeout=60)
        if uses_db2(self.install):
            db2.dump(self.data, self.dir)
        events = [json.loads(l) for l in (self.dir / "events.jsonl").read_text().splitlines() if l]
        shutil.copy(self.dir / "events.jsonl", self.dir / f"events.{self.task:02d}.jsonl")
        return proc, events

    def queue_file(self, name):
        return self.dir / "mq" / name

    def read_queue(self, name):
        path = self.queue_file(name)
        if not path.exists():
            return []
        return [l.split(" ") for l in path.read_text().splitlines() if l.strip()]

    def mq_step(self, spec):
        """Put the messages on the queue, run the trigger transid once, observe the puts."""
        queue = spec["queue"]
        (self.dir / "mq").mkdir(exist_ok=True)
        lines = [" ".join(m) for m in self.read_queue(queue)]
        for m in spec.get("messages", []):
            self.mq_ids += 1
            msgid = f"HARNESS{self.mq_ids:017d}".encode("latin-1")
            correl = m.get("correl_id", "").encode("latin-1").ljust(24, b" ") if m.get("correl_id") \
                else bytes(24)
            reply = m.get("reply_to", "").encode("latin-1").ljust(48, b" ")
            data = m.get("data", "").encode("latin-1")
            lines.append(f"H {msgid.hex()} {correl.hex()} {reply.hex()} {data.hex() or '-'}")
        self.queue_file(queue).write_text("".join(l + "\n" for l in lines))
        program = self.trans[spec["trigger"]]
        before = self.data.snapshot(self.dir)
        proc, events = self.run_task([f"program={program}", f"trnid={spec['trigger']}",
                                      f"start_data={mqtm(queue).hex()}"])
        obs, trail, puts = {}, [], {}
        for path in sorted((self.dir / "mq").iterdir()):
            kept = []
            for origin, _msgid, correl, _reply, data in self.read_queue(path.name):
                if origin == "P":
                    text = b"" if data == "-" else bytes.fromhex(data)
                    puts.setdefault(path.name, []).append({
                        "data": text.decode("latin-1").rstrip(),
                        "correl_id": bytes.fromhex(correl).decode("latin-1").rstrip(" \0")})
                else:
                    kept.append(" ".join([origin, _msgid, correl, _reply, data]))
            path.write_text("".join(l + "\n" for l in kept))
        obs["mq"] = puts
        unread = len(self.read_queue(queue))
        if unread:
            obs["unread"] = {queue: unread}
        for ev in events:
            if ev["ev"] == "start":
                trail.append(ev["program"])
            elif ev["ev"] == "abend":
                obs["abend"] = ev["code"]
            elif ev["ev"] == "unsupported":
                obs["unsupported"] = ev["command"]
            elif ev["ev"] == "writeq_td":
                text = bytes.fromhex(ev["data"]).decode("latin-1").rstrip()
                obs.setdefault("queues", {}).setdefault(ev["queue"], []).append(text)
        if proc.returncode or not events:
            obs["runtime_error"] = (proc.stderr or proc.stdout).strip()[-2000:]
        changes = self.data.diff(before, self.data.snapshot(self.dir))
        if changes:
            obs["files"] = changes
        obs["_programs"] = trail
        return obs

    def step(self, step):
        if "mq" in step:
            return self.mq_step(step["mq"])
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
        lines = [f"program={program}", f"trnid={trnid}", f"aid={screen.AID[aid].hex()}",
                 f"commarea={commarea.hex()}"]
        if mapbuf is not None:
            lines.append(f"map={mapbuf.hex()}")
        before = self.data.snapshot(self.dir)
        proc, events = self.run_task(lines)
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


def run_journey(steps, workdir, setup=None, install=()):
    s = Session(workdir, setup, install)
    return [s.step(st) for st in steps]
