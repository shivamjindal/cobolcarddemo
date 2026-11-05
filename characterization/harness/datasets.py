"""Seed datasets: copy them into a run directory, snapshot them, and diff them by record.

A dataset with "encoding": "hex" stores one record per line as hex (IMS segments hold
packed bytes that can be newlines). "key_fields" names the fields a diff shows for a key,
joined with "/", instead of the raw key bytes.
"""
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

    def is_hex(self, name):
        return self.datasets[name].get("encoding") == "hex"

    def _parse(self, name, data):
        reclen = self.datasets[name]["reclen"]
        out = []
        for line in data.split(b"\n"):
            line = line.rstrip(b"\r")
            if not line.strip():
                continue
            rec = bytes.fromhex(line.decode("ascii")) if self.is_hex(name) else line
            out.append(rec[:reclen].ljust(reclen, b" "))
        return out

    def _format(self, name, recs):
        if self.is_hex(name):
            return b"".join(r.hex().encode("ascii") + b"\n" for r in recs)
        return b"".join(r.rstrip(b" ") + b"\n" for r in recs)

    def write(self, run_dir, name, recs):
        (Path(run_dir) / f"{name}.dat").write_bytes(self._format(name, recs))

    def key_text(self, name, rec):
        ds = self.datasets[name]
        if "key_fields" in ds:
            values = self.layout(name).decode(rec)
            return "/".join(str(values[f]).rstrip() for f in ds["key_fields"])
        k0, kl = ds["key"]
        return rec[k0:k0 + kl].decode("latin-1").rstrip()

    def _key_bytes(self, name, text):
        ds = self.datasets[name]
        k0, kl = ds["key"]
        if "key_fields" in ds:
            values = dict(zip(ds["key_fields"], text.split("/")))
            return self.layout(name).encode(values, b" " * ds["reclen"])[k0:k0 + kl]
        return text.encode("latin-1").ljust(kl, b" ")

    def select(self, name, recs, where):
        def keep(rec):
            values = self.decode(name, rec)
            return all(str(values[f]) in {str(a) for a in allowed} for f, allowed in where.items())
        return [r for r in recs if keep(r)]

    def seed_records(self, name):
        return self._parse(name, (REPO / self.datasets[name]["source"]).read_bytes())

    def materialize(self, run_dir, setup, names=None):
        """Write <DATASET>.dat files: seed records, then the case's setup edits.

        setup = {DATASET: {"where": {FIELD: [values]}, "upsert": [record fields], "delete": [keys],
                           "replace": [record fields]}}
        "where" first keeps only the seed records whose decoded FIELD is one of the values.
        An upsert of an existing key changes only the fields it names. A key is written the
        way a diff reports it.
        """
        for name in names or self.datasets:
            ds = self.datasets[name]
            recs = self.seed_records(name)
            edit = setup.get(name, {})
            if "replace" in edit:
                recs = [self.encode(name, r) for r in edit["replace"]]
            if "where" in edit:
                recs = self.select(name, recs, edit["where"])
            if ds["key"]:
                k0, kl = ds["key"]
                by_key = {r[k0:k0 + kl]: r for r in recs}
                for r in edit.get("upsert", []):
                    key = self.encode(name, r)[k0:k0 + kl]
                    base = by_key.get(key)
                    by_key[key] = self.layout(name).encode(r, base)[:ds["reclen"]] if base else self.encode(name, r)
                for key in edit.get("delete", []):
                    by_key.pop(self._key_bytes(name, key), None)
                recs = [by_key[k] for k in sorted(by_key)]
            else:
                recs += [self.encode(name, r) for r in edit.get("append", [])]
            self.write(run_dir, name, recs)

    def read(self, run_dir, name):
        path = Path(run_dir) / f"{name}.dat"
        if not path.exists():
            return None
        return self._parse(name, path.read_bytes())

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
            deleted = [self.key_text(name, b[k]) for k in sorted(b.keys() - a.keys())]
            updated = []
            for k in sorted(a.keys() & b.keys()):
                if a[k] != b[k]:
                    old, new = self.decode(name, b[k]), self.decode(name, a[k])
                    updated.append({"key": self.key_text(name, a[k]),
                                    "changed": {f: [old[f], new[f]] for f in new if old[f] != new[f]}})
            entry = {k: v for k, v in (("inserted", inserted), ("updated", updated), ("deleted", deleted)) if v}
            if entry:
                changes[name] = entry
        return changes
