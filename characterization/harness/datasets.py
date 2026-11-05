"""Seed datasets: copy them into a run directory, snapshot them, and diff them by record."""
import json
from pathlib import Path

from copybook import load_layout

REPO = Path(__file__).resolve().parents[2]
REGISTRY = REPO / "characterization" / "fixtures" / "datasets.json"


def _value(v):
    return v.rstrip() if isinstance(v, str) else v


class Datasets:
    def __init__(self):
        reg = json.loads(REGISTRY.read_text())
        self.datasets = reg["datasets"]
        self.cics_files = reg["cics_files"]
        self._layouts = {}

    def layout(self, name):
        if name not in self._layouts:
            self._layouts[name] = load_layout(REPO / self.datasets[name]["copybook"])
        return self._layouts[name]

    def decode(self, name, raw):
        return {k: _value(v) for k, v in self.layout(name).decode(raw).items()
                if "FILLER" not in k}

    def encode(self, name, values):
        lay = self.layout(name)
        return lay.encode(values)[:self.datasets[name]["reclen"]]

    def seed_records(self, name):
        ds = self.datasets[name]
        out = []
        for line in (REPO / ds["source"]).read_bytes().split(b"\n"):
            line = line.rstrip(b"\r")
            if line.strip():
                out.append(line[:ds["reclen"]].ljust(ds["reclen"], b" "))
        return out

    def materialize(self, run_dir, setup, names=None):
        """Write <DATASET>.dat files: seed records, then the case's setup edits.

        setup = {DATASET: {"upsert": [record fields], "delete": [keys], "replace": [record fields]}}
        An upsert of an existing key changes only the fields it names.
        """
        for name in names or self.datasets:
            ds = self.datasets[name]
            recs = self.seed_records(name)
            edit = setup.get(name, {})
            if "replace" in edit:
                recs = [self.encode(name, r) for r in edit["replace"]]
            if ds["key"]:
                k0, kl = ds["key"]
                by_key = {r[k0:k0 + kl]: r for r in recs}
                for r in edit.get("upsert", []):
                    key = self.encode(name, r)[k0:k0 + kl]
                    base = by_key.get(key)
                    by_key[key] = self.layout(name).encode(r, base)[:ds["reclen"]] if base else self.encode(name, r)
                for key in edit.get("delete", []):
                    by_key.pop(key.encode("latin-1").ljust(kl, b" "), None)
                recs = [by_key[k] for k in sorted(by_key)]
            else:
                recs += [self.encode(name, r) for r in edit.get("append", [])]
            (Path(run_dir) / f"{name}.dat").write_bytes(b"".join(r.rstrip(b" ") + b"\n" for r in recs))

    def read(self, run_dir, name):
        path = Path(run_dir) / f"{name}.dat"
        if not path.exists():
            return None
        reclen = self.datasets[name]["reclen"]
        return [l.rstrip(b"\r")[:reclen].ljust(reclen, b" ")
                for l in path.read_bytes().split(b"\n") if l.strip()]

    def snapshot(self, run_dir, names=None):
        out = {}
        for name in names or self.datasets:
            ds = self.datasets[name]
            recs = self.read(run_dir, name)
            if recs is None or not ds["key"]:
                continue
            k0, kl = ds["key"]
            out[name] = {r[k0:k0 + kl]: r for r in recs}
        return out

    def diff(self, before, after):
        changes = {}
        for name in after:
            b, a = before.get(name, {}), after[name]
            inserted = [self.decode(name, a[k]) for k in sorted(a.keys() - b.keys())]
            deleted = [k.decode("latin-1").rstrip() for k in sorted(b.keys() - a.keys())]
            updated = []
            for k in sorted(a.keys() & b.keys()):
                if a[k] != b[k]:
                    old, new = self.decode(name, b[k]), self.decode(name, a[k])
                    updated.append({"key": k.decode("latin-1").rstrip(),
                                    "changed": {f: [old[f], new[f]] for f in new if old[f] != new[f]}})
            entry = {k: v for k, v in (("inserted", inserted), ("updated", updated), ("deleted", deleted)) if v}
            if entry:
                changes[name] = entry
        return changes
