#!/usr/bin/env python3
"""Build the CardDemo inventory and dependency graph from the sources under app/.

Writes inventory.json (nodes + typed edges with file:line evidence) and
inventory.md (generated tables and Mermaid diagrams) next to this script.

    python3 migration/inventory/build_inventory.py [--check]

--check exits non-zero when the committed outputs differ from a fresh build.
"""
import bisect
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app"
OUT_DIR = Path(__file__).resolve().parent

EXTERNAL_PROGRAMS = {
    "CEE3ABD": "LE runtime", "CEEDAYS": "LE runtime", "CBLTDLI": "IMS DL/I",
    "MQOPEN": "IBM MQ", "MQGET": "IBM MQ", "MQPUT": "IBM MQ", "MQPUT1": "IBM MQ",
    "MQCLOSE": "IBM MQ", "MQCONN": "IBM MQ", "MQDISC": "IBM MQ",
    "IDCAMS": "z/OS utility", "SORT": "z/OS utility", "IEFBR14": "z/OS utility",
    "IEBGENER": "z/OS utility", "SDSF": "z/OS utility", "IKJEFT01": "TSO batch",
    "IKJEFT1B": "TSO batch", "DFSRRC00": "IMS region controller", "FTP": "z/OS utility",
    "DFHCSDUP": "CICS CSD utility", "DSNTEP4": "Db2 utility", "DSNTIAD": "Db2 utility",
    "DFSURGU0": "IMS utility", "DSNTIAUL": "Db2 utility", "DSNTEP2": "Db2 utility",
}
SYSTEM_COPYBOOKS = {"DFHAID", "DFHBMSCA", "DFHATTR", "SQLCA", "SQLDA"}

# Migration slices from the project plan, in delivery order; slice_rank relies on this order.
SLICES = [
    ("1-signon-user-admin", "Sign-on, menus, user admin",
     ["COSGN00C", "COMEN01C", "COADM01C", "COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C"]),
    ("2-account-card", "Account and card view/update",
     ["COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC"]),
    ("3-transactions-billing", "Transactions, bill payment, report request",
     ["COTRN00C", "COTRN01C", "COTRN02C", "COBIL00C", "CORPT00C", "CSUTLDTC"]),
    ("4-batch", "Batch jobs",
     ["CBACT01C", "CBACT02C", "CBACT03C", "CBACT04C", "CBCUS01C", "CBTRN01C", "CBTRN02C",
      "CBTRN03C", "CBSTM03A", "CBSTM03B", "CBEXPORT", "CBIMPORT", "COBSWAIT", "MVSWAIT",
      "COBDATFT"]),
    ("5a-txn-type-db2", "Optional: transaction types on Db2",
     ["COTRTLIC", "COTRTUPC", "COBTUPDT"]),
    ("5b-authorization-ims-db2-mq", "Optional: pending authorizations on IMS/Db2/MQ",
     ["COPAUA0C", "COPAUS0C", "COPAUS1C", "COPAUS2C", "CBPAUP0C", "DBUNLDGS", "PAUDBLOD",
      "PAUDBUNL"]),
    ("5c-vsam-mq", "Optional: account/date inquiry over MQ", ["COACCT01", "CODATE01"]),
]


class Inventory:
    """Typed nodes keyed by id ("kind:name") and de-duplicated edges with evidence."""

    def __init__(self):
        self.nodes = {}
        self.edges = {}
        self.unresolved = []

    def node(self, kind, name, **attrs):
        nid = f"{kind}:{name}"
        n = self.nodes.setdefault(nid, {"id": nid, "kind": kind, "name": name})
        for k, v in attrs.items():
            if v is not None:
                n[k] = v
        return nid

    def edge(self, src, rel, dst, evidence):
        e = self.edges.setdefault((src, rel, dst), {"from": src, "rel": rel, "to": dst, "evidence": []})
        if evidence not in e["evidence"]:
            e["evidence"].append(evidence)

    def to_json(self):
        for e in self.edges.values():
            e["evidence"].sort()
        return {
            "nodes": sorted(self.nodes.values(), key=lambda n: n["id"]),
            "edges": sorted(self.edges.values(), key=lambda e: (e["from"], e["rel"], e["to"])),
            "unresolved": sorted(self.unresolved, key=lambda u: (u["where"], u["what"])),
        }


def rel(path):
    return str(path.relative_to(ROOT))


def module_of(path):
    part = path.relative_to(APP).parts[0]
    return part if part.startswith("app-") else "core"


def files(*globs):
    out = []
    for g in globs:
        out.extend(p for p in APP.glob(g) if p.is_file())
    return sorted(set(out))


# ---------------------------------------------------------------- COBOL source


class CobolSource:
    """Fixed-format COBOL reduced to columns 8-72, comments dropped, offsets mapped to lines."""

    def __init__(self, path):
        self.path = path
        lines = path.read_text(errors="replace").splitlines()
        kept, starts, numbers, pos = [], [], [], 0
        for i, raw in enumerate(lines, 1):
            raw = raw.expandtabs(8)
            if len(raw) > 6 and raw[6] in "*/":
                continue
            code = raw[7:72].upper() if len(raw) > 7 else ""
            kept.append(code)
            starts.append(pos)
            numbers.append(i)
            pos += len(code) + 1
        self.text = "\n".join(kept)
        self.masked = re.sub(r"'[^'\n]*'|\"[^\"\n]*\"", lambda m: m.group(0)[0] + " " * (len(m.group(0)) - 2) + m.group(0)[0], self.text)
        self._starts, self._numbers = starts, numbers

    def finditer(self, pattern, flags=0):
        """Match statements outside string literals, returning matches against the original text."""
        rx = re.compile(pattern, flags)
        for m in rx.finditer(self.masked):
            real = rx.match(self.text, m.start())
            if real:
                yield real

    def where(self, offset):
        return f"{rel(self.path)}:{self._numbers[bisect.bisect_right(self._starts, offset) - 1]}"


KW = r"(?<![\w-])"
LIT = r"""(?:'([^'\n]*)'|"([^"\n]*)")"""
NAME_RE = r"[A-Z0-9][A-Z0-9-]*"
PROGRAM_NAME = re.compile(r"^[A-Z@#$][A-Z0-9@#$]{1,7}$")
TRANSID = re.compile(r"^[A-Z0-9]{4}$")


def lit(m, i=1):
    return m.group(i) if m.group(i) is not None else m.group(i + 1)


def paren_arg(block, key):
    """Return the balanced (...) argument of KEY in a CICS/DLI block, or None."""
    m = re.search(rf"\b{key}\s*\(", block)
    if not m:
        return None
    depth, i = 1, m.end()
    while i < len(block) and depth:
        depth += {"(": 1, ")": -1}.get(block[i], 0)
        i += 1
    arg = block[m.end(): i - 1].strip()
    while arg.startswith("(") and arg.endswith(")"):
        arg = arg[1:-1].strip()
    return arg


class CopybookIndex:
    def __init__(self):
        self.by_name = defaultdict(list)
        for p in files("**/cpy/*", "**/cpy-bms/*", "**/dcl/*"):
            self.by_name[p.stem.upper()].append(p)
        self._parsed = {}

    def find(self, name, module):
        cands = self.by_name.get(name, [])
        for p in cands:
            if module_of(p) == module:
                return p
        return cands[0] if cands else None

    def source(self, path):
        if path not in self._parsed:
            self._parsed[path] = CobolSource(path)
        return self._parsed[path]


def copy_refs(src):
    refs = [m.group(1) for m in src.finditer(rf"{KW}COPY\s+['\"]?([A-Z0-9@#$-]+)")]
    refs += [m.group(1) for m in src.finditer(rf"{KW}EXEC\s+SQL\s+INCLUDE\s+([A-Z0-9@#$]+)")]
    return refs


def value_table(sources):
    """Map data names to literal values from VALUE clauses and MOVE statements, resolved to a fixpoint."""
    lits, aliases = defaultdict(set), defaultdict(set)
    for src in sources:
        for m in src.finditer(rf"{KW}(\d\d)\s+({NAME_RE})\s(?:(?!\.\s)[^'\"])*?\bVALUE\s+(?:IS\s+)?{LIT}"):
            if m.group(1) != "88":
                lits[m.group(2)].add(lit(m, 3).strip())
        for m in src.finditer(rf"{KW}MOVE\s+(?:{LIT}|({NAME_RE}))\s+TO\s+({NAME_RE})"):
            if m.group(3):
                aliases[m.group(4)].add(m.group(3))
            else:
                lits[m.group(4)].add(lit(m).strip())
    changed = True
    while changed:
        changed = False
        for dst, srcs in aliases.items():
            for s in srcs:
                if not lits[s] <= lits[dst]:
                    lits[dst] |= lits[s]
                    changed = True
    return lits, aliases


class Program:
    def __init__(self, path, copybooks):
        self.path = path
        self.name = path.stem.upper()
        self.module = module_of(path)
        self.src = CobolSource(path)
        self.copybooks = {}
        self.missing_copybooks = set()
        self._collect_copybooks(self.src, copybooks)
        self.values, self.aliases = value_table([self.src] + [copybooks.source(p) for p in self.copybooks.values()])
        self.cpy_literals = {
            name: {lit(m).strip() for m in re.finditer(LIT, copybooks.source(p).text)}
            for name, p in self.copybooks.items()
        }
        self.cpy_defines = {
            name: set(re.findall(rf"\b\d\d\s+({NAME_RE})", copybooks.source(p).text))
            for name, p in self.copybooks.items()
        }

    def _collect_copybooks(self, src, index):
        for ref in copy_refs(src):
            if ref in self.copybooks or ref in self.missing_copybooks:
                continue
            path = index.find(ref, self.module)
            if path:
                self.copybooks[ref] = path
                self._collect_copybooks(index.source(path), index)
            else:
                self.missing_copybooks.add(ref)

    def resolve(self, expr, valid=PROGRAM_NAME, known=()):
        """Resolve a literal, data name, or subscripted table reference to candidate values."""
        if expr is None:
            return set()
        m = re.fullmatch(LIT, expr)
        if m:
            v = lit(m).strip()
            return {v} if valid.match(v) else set()
        base = expr.split("(")[0].split()[0]
        vals = {v for v in self.values.get(base, ()) if valid.match(v)}
        if not vals and "(" in expr:
            for cpy, names in self.cpy_defines.items():
                if base in names:
                    vals |= {v for v in self.cpy_literals[cpy] if v in known}
        return vals

    def execs(self, kind):
        for m in self.src.finditer(rf"{KW}EXEC\s+{kind}\b(.*?)\bEND-EXEC", re.S):
            yield m.group(1).strip(), self.src.where(m.start())


def self_or_caller(prog, expr):
    """True when a program-name variable can carry the caller's name from the COMMAREA."""
    seen, todo = set(), [expr.split("(")[0].strip()]
    while todo:
        name = todo.pop()
        if name in seen:
            continue
        seen.add(name)
        if "FROM-PROG" in name:
            return True
        todo.extend(prog.aliases.get(name, ()))
    return False


CICS_FILE_OPS = {"READ": "read", "READNEXT": "read", "READPREV": "read", "STARTBR": "read",
                 "WRITE": "write", "REWRITE": "update", "DELETE": "delete"}
DLI_OPS = {"GU": "read", "GN": "read", "GNP": "read", "GHU": "read", "GHN": "read",
           "GHNP": "read", "ISRT": "write", "REPL": "update", "DLET": "delete"}
SQL_OPS = {"SELECT": "read", "DECLARE": "read", "FETCH": "read", "INSERT": "write",
           "UPDATE": "update", "DELETE": "delete"}
OPEN_OPS = {"INPUT": "read", "OUTPUT": "write", "EXTEND": "write", "I-O": "update"}


def scan_program(inv, prog, ctx):
    pid = f"program:{prog.name}"
    text = prog.src.text
    for name, path in sorted(prog.copybooks.items()):
        cid = inv.node("copybook", name, path=rel(path), module=module_of(path))
        inv.edge(pid, "copies", cid, rel(prog.path))
    for name in sorted(prog.missing_copybooks):
        inv.node("copybook", name, external=name in SYSTEM_COPYBOOKS or name.startswith("CMQ"))
        inv.edge(pid, "copies", f"copybook:{name}", rel(prog.path))

    has_cics = False
    for block, where in prog.execs("CICS"):
        has_cics = True
        words = block.split()
        verb = words[0] if words else ""
        if verb in ("XCTL", "LINK"):
            expr = paren_arg(block, "PROGRAM")
            targets = prog.resolve(expr, known=ctx["program_names"]) - {prog.name}
            for t in sorted(targets):
                inv.edge(pid, verb.lower(), f"program:{t}", where)
            if self_or_caller(prog, expr):
                inv.unresolved.append({"where": where, "what": f"{verb} PROGRAM({expr})",
                                       "note": "returns to the calling program named in the COMMAREA"})
            elif not targets:
                inv.unresolved.append({"where": where, "what": f"{verb} PROGRAM({expr})",
                                       "note": "target set at runtime"})
        elif verb in ("RETURN", "START") and "TRANSID" in block:
            expr = paren_arg(block, "TRANSID")
            for t in sorted(prog.resolve(expr, TRANSID)):
                inv.edge(pid, "return-transid" if verb == "RETURN" else "start", f"transaction:{t}", where)
        elif verb in ("SEND", "RECEIVE") and "MAPSET" in block:
            for ms in sorted(prog.resolve(paren_arg(block, "MAPSET"))):
                inv.edge(pid, "uses-map", f"mapset:{ms}", where)
        elif verb in CICS_FILE_OPS and ("FILE" in block or "DATASET" in block):
            expr = paren_arg(block, "FILE") or paren_arg(block, "DATASET")
            for f in sorted(prog.resolve(expr)):
                inv.edge(pid, CICS_FILE_OPS[verb], f"cics-file:{f}", where)
                rec = paren_arg(block, "INTO") or paren_arg(block, "FROM")
                ctx["record_refs"].append((prog, f"cics-file:{f}", rec))
        elif verb == "WRITEQ" and len(words) > 1 and words[1] == "TD":
            for q in sorted(prog.resolve(paren_arg(block, "QUEUE"), re.compile(r"^[A-Z0-9]{1,4}$"))):
                inv.edge(pid, "write", f"tdqueue:{q}", where)
        elif verb == "RETRIEVE" and "MQTM" in block:
            inv.edge(pid, "triggered-by", "mq-queue:(trigger queue)", where)

    for block, where in prog.execs("SQL"):
        verb = block.split()[0] if block.split() else ""
        if verb in SQL_OPS:
            for t in ctx["db2_tables"]:
                if re.search(rf"(?<![\w.]){re.escape(t)}\b|\b\w+\.{re.escape(t)}\b", block):
                    inv.edge(pid, SQL_OPS[verb], f"db2-table:{t}", where)

    for block, where in prog.execs("DLI"):
        verb = block.split()[0] if block.split() else ""
        if verb == "SCHD":
            for psb in sorted(prog.resolve(paren_arg(block, "PSB"))):
                inv.edge(pid, "schedules", f"psb:{psb}", where)
        elif verb in DLI_OPS:
            seg = paren_arg(block, "SEGMENT")
            for s in sorted(prog.resolve(seg) | ({seg} if seg in ctx["ims_segments"] else set())):
                inv.edge(pid, DLI_OPS[verb], f"ims-segment:{s}", where)

    calls = set()
    for m in prog.src.finditer(rf"{KW}CALL\s+(?:{LIT}|({NAME_RE}))"):
        where = prog.src.where(m.start())
        targets = {lit(m).strip()} if m.group(3) is None else prog.resolve(m.group(3))
        for t in sorted(targets):
            if t == "CBLTDLI":
                for s in sorted(ctx["ims_segments"]):
                    if re.search(rf"['\"]{s}\b", text):
                        inv.edge(pid, "uses", f"ims-segment:{s}", where)
            if t.startswith("MQ"):
                calls.add(t)
            inv.edge(pid, "call", f"program:{t}", where)
        if not targets:
            inv.unresolved.append({"where": where, "what": f"CALL {m.group(3)}", "note": "dynamic CALL"})
    if calls:
        for name, vals in sorted(prog.values.items()):
            if "QNAME" in name or "QUEUE-NAME" in name:
                for q in sorted(v for v in vals if re.fullmatch(r"[A-Z0-9]+(\.[A-Z0-9]+)+", v)):
                    inv.edge(pid, "uses", f"mq-queue:{q}", rel(prog.path))

    for m in re.finditer(r"\bEXEC\s+PROC=([A-Z0-9@#$]+)", text):
        inv.edge(pid, "submits", f"proc:{m.group(1)}", prog.src.where(m.start()))

    selects = {m.group(1): m.group(2).split("-")[-1]
               for m in prog.src.finditer(rf"{KW}SELECT\s+(?:OPTIONAL\s+)?({NAME_RE})\s+ASSIGN\s+(?:TO\s+)?({NAME_RE})")}
    dd_access = defaultdict(set)
    for m in prog.src.finditer(rf"{KW}OPEN\s+([A-Z0-9\s-]+)"):
        mode = None
        for tok in m.group(1).split():
            if tok in OPEN_OPS:
                mode = OPEN_OPS[tok]
            elif tok in selects and mode:
                dd_access[selects[tok]].add(mode)
            elif mode:
                break
    for dd in selects.values():
        dd_access.setdefault(dd, set())
    fd_records = {}
    for m in prog.src.finditer(rf"{KW}FD\s+({NAME_RE})(?:(?!{KW}FD\s).)*?\b01\s+({NAME_RE})", re.S):
        fd_records[m.group(2)] = m.group(1)
    for m in prog.src.finditer(rf"{KW}READ\s+({NAME_RE})(?:\s+NEXT)?(?:\s+RECORD)?\s+INTO\s+({NAME_RE})"):
        if m.group(1) in selects:
            ctx["record_refs"].append((prog, ("dd", selects[m.group(1)]), m.group(2)))
    for m in prog.src.finditer(rf"{KW}(?:WRITE|REWRITE)\s+({NAME_RE})\s+FROM\s+({NAME_RE})"):
        f = fd_records.get(m.group(1))
        if f in selects:
            ctx["record_refs"].append((prog, ("dd", selects[f]), m.group(2)))

    kind = "cics" if has_cics else "batch"
    inv.node("program", prog.name, language="COBOL", path=rel(prog.path), module=prog.module, type=kind,
             mq_calls=sorted(calls) or None, dd_access={dd: sorted(v) for dd, v in sorted(dd_access.items())} or None)


# ---------------------------------------------------------------- CSD / BMS / ASM


def parse_csd_text(text):
    defs = []
    for block in re.split(r"(?m)^\s*(?=DEFINE\s)", text):
        m = re.match(r"DEFINE\s+(\w+)\(([^)]*)\)", block.strip())
        if m:
            attrs = dict(re.findall(r"\b([A-Z0-9]+)\(([^()]*)\)", block[m.end():]))
            defs.append((m.group(1), m.group(2).strip(), attrs))
    return defs


def scan_csd(inv):
    for path in files("csd/*", "app-*/csd/*"):
        mod = module_of(path)
        for kind, name, a in parse_csd_text(path.read_text(errors="replace")):
            ev = rel(path)
            if kind == "TRANSACTION":
                tid = inv.node("transaction", name, module=mod, description=a.get("DESCRIPTION"),
                               csd=ev)
                if "PROGRAM" in a:
                    inv.edge(tid, "runs", f"program:{a['PROGRAM']}", ev)
            elif kind == "PROGRAM":
                inv.node("program", name, csd=ev, module=mod)
            elif kind == "MAPSET":
                inv.node("mapset", name, csd=ev, module=mod)
            elif kind == "FILE":
                fid = inv.node("cics-file", name, module=mod, dsn=a.get("DSNAME"),
                               description=a.get("DESCRIPTION"),
                               ops=sorted(k for k in ("ADD", "BROWSE", "DELETE", "READ", "UPDATE")
                                          if a.get(k) == "YES"))
                if a.get("DSNAME"):
                    inv.edge(fid, "dataset", f"dataset:{a['DSNAME']}", ev)
            elif kind == "TDQUEUE":
                inv.node("tdqueue", name, module=mod, ddname=a.get("DDNAME"),
                         description=a.get("DESCRIPTION"))
            elif kind in ("DB2CONN", "DB2ENTRY", "MQCONN", "LIBRARY"):
                inv.node(f"cics-{kind.lower()}", name, module=mod, csd=ev,
                         attrs={k: v for k, v in a.items() if k in ("PLAN", "DB2ID", "MQNAME", "DSNAME01", "TRANSID")})


def scan_bms(inv):
    for path in files("**/bms/*.bms"):
        text = path.read_text(errors="replace")
        mapset = re.search(r"(?m)^([A-Z0-9@#$]+)\s+DFHMSD\b", text)
        name = mapset.group(1) if mapset else path.stem.upper()
        maps = re.findall(r"(?m)^([A-Z0-9@#$]+)\s+DFHMDI\b", text)
        fields = len(re.findall(r"(?m)^[A-Z0-9@#$]+\s+DFHMDF\b", text))
        inv.node("mapset", name, path=rel(path), module=module_of(path), maps=maps, named_fields=fields)


def scan_asm(inv):
    macros = {p.stem.upper(): p for p in files("maclib/*")}
    for path in files("asm/*"):
        name = path.stem.upper()
        pid = inv.node("program", name, language="Assembler", path=rel(path), module="core",
                       type="subroutine")
        text = path.read_text(errors="replace").upper()
        for mac, mpath in macros.items():
            if re.search(rf"\b{mac}\b", text):
                inv.node("macro", mac, path=rel(mpath))
                inv.edge(pid, "uses-macro", f"macro:{mac}", rel(path))


# ---------------------------------------------------------------- Data definitions


def scan_listcat(inv):
    path = APP / "catlg" / "LISTCAT.txt"
    entry, component = None, False
    for line in path.read_text(errors="replace").splitlines():
        m = re.match(r"^0(CLUSTER|AIX|PATH|NONVSAM|GDG BASE|DATA|INDEX)\s*-+\s*(\S+)", line)
        if m:
            typ, dsn = m.groups()
            component = typ in ("DATA", "INDEX")
            if component:
                continue
            kind = {"CLUSTER": "KSDS", "GDG BASE": "GDG", "NONVSAM": "PS"}.get(typ, typ)
            entry = inv.node("dataset", dsn, type=kind, cataloged=True)
            continue
        if entry is None:
            continue
        n = inv.nodes[entry]
        for key, attr in (("KEYLEN", "keylen"), ("RKP", "rkp"), ("AVGLRECL", "avglrecl"),
                          ("MAXLRECL", "maxlrecl"), ("REC-TOTAL", "rec_total")):
            mm = re.search(rf"\b{key}-+(\d+)", line)
            if mm and attr not in n:
                n[attr] = int(mm.group(1))
        if n["type"] == "KSDS" and re.search(r"\bNONINDEXED\b", line):
            n["type"] = "ESDS"
        elif n["type"] == "KSDS" and re.search(r"\bNUMBERED\b", line):
            n["type"] = "RRDS"
        mm = re.match(r"^\s+(CLUSTER|AIX)-+(\S+)", line)
        if mm and not component and n["type"] in ("AIX", "PATH"):
            inv.edge(entry, "base", f"dataset:{mm.group(2)}", rel(path))


def scan_db2(inv):
    tables = set()
    for path in files("**/ddl/*"):
        for m in re.finditer(r"CREATE\s+TABLE\s+([A-Z0-9_.]+)", path.read_text(errors="replace").upper()):
            full = m.group(1)
            tables.add(full.split(".")[-1])
            inv.node("db2-table", full.split(".")[-1], qualified=full, ddl=rel(path), module=module_of(path))
    return tables


def scan_ims(inv):
    segments = set()
    for path in files("**/ims/*"):
        text = path.read_text(errors="replace").upper()
        mod = module_of(path)
        dbd = re.search(r"\bDBD\s+NAME=([A-Z0-9@#$]+)(?:,ACCESS=\(?([A-Z,]+))?", text)
        if dbd:
            did = inv.node("dbd", dbd.group(1), path=rel(path), module=mod, access=dbd.group(2))
            for dd in re.findall(r"\bDD1=([A-Z0-9@#$]+)", text):
                inv.nodes[did].setdefault("ddnames", []).append(dd)
            for seg in re.findall(r"\bSEGM\s+NAME=([A-Z0-9@#$]+)", text):
                segments.add(seg)
                inv.node("ims-segment", seg, module=mod)
                inv.edge(f"ims-segment:{seg}", "in-dbd", did, rel(path))
        psb = re.search(r"\bPSBNAME=([A-Z0-9@#$]+)", text)
        if psb:
            sid = inv.node("psb", psb.group(1), path=rel(path), module=mod)
            for db in re.findall(r"\bDBDNAME=([A-Z0-9@#$]+)", text):
                inv.edge(sid, "pcb", f"dbd:{db}", rel(path))
            for seg in re.findall(r"\bSENSEG\s+NAME=([A-Z0-9@#$]+)", text):
                inv.edge(sid, "senseg", f"ims-segment:{seg}", rel(path))
    return segments


# ---------------------------------------------------------------- JCL


def jcl_statements(text):
    """Return JCL statements as {line, name, op, operands, data} with continuations joined."""
    stmts, cur = [], None
    instream = None
    for i, raw in enumerate(text.splitlines(), 1):
        line = raw[:72].rstrip()
        if line.startswith("//*"):
            continue
        if not line.startswith("//") and not line.startswith("/*"):
            if instream is not None:
                instream.append(line)
            continue
        if line.startswith("/*"):
            instream = None
            continue
        body = line[2:]
        if cur and cur["operands"].endswith(",") and body.startswith(" "):
            cur["operands"] += body.strip().split(" ")[0]
            continue
        m = re.match(r"(\S*)\s+(\S+)\s*(.*)", body)
        if not m:
            continue
        name, op, rest = m.groups()
        cur = {"line": i, "name": name, "op": op, "operands": operand_token(rest), "data": []}
        stmts.append(cur)
        instream = cur["data"] if op == "DD" and re.match(r"(\*|DATA)(,|$)", cur["operands"]) else None
    return stmts


def operand_token(rest):
    out, quote = [], False
    for ch in rest:
        if ch == "'":
            quote = not quote
        if ch == " " and not quote:
            break
        out.append(ch)
    return "".join(out)


def kv(operands):
    pairs, depth, cur = [], 0, ""
    quote = False
    for ch in operands:
        if ch == "'":
            quote = not quote
        depth += {"(": 1, ")": -1}.get(ch, 0)
        if ch == "," and depth == 0 and not quote:
            pairs.append(cur)
            cur = ""
        else:
            cur += ch
    pairs.append(cur)
    out = {}
    for p in pairs:
        if "=" in p:
            k, v = p.split("=", 1)
            out[k] = v
        elif p:
            out.setdefault("_pos", []).append(p)
    return out


def substitute(value, symbols):
    for k, v in sorted(symbols.items(), key=lambda kv_: -len(kv_[0])):
        value = value.replace(f"&{k}.", v).replace(f"&{k}", v)
    return value


def parse_steps(path, procs, symbols=None):
    """Expand a job or proc into steps: {name, program, proc, parm, dds{dd: {dsn, disp, data}}}."""
    symbols = dict(symbols or {})
    steps, step = [], None
    for st in jcl_statements(path.read_text(errors="replace")):
        args = kv(st["operands"])
        if st["op"] == "SET":
            symbols.update({k: v for k, v in args.items() if k != "_pos"})
        elif st["op"] == "PROC":
            for k, v in args.items():
                if k != "_pos":
                    symbols.setdefault(k, v.strip("'"))
        elif st["op"] == "EXEC":
            pos = args.get("_pos", [])
            pgm = args.get("PGM")
            proc = args.get("PROC") or (pos[0] if pos and not pgm else None)
            if proc:
                child = {k: substitute(v.strip("'"), symbols) for k, v in args.items() if k not in ("_pos", "PROC")}
                ppath = procs.get(proc)
                sub = parse_steps(ppath, procs, {**symbols, **child}) if ppath else []
                for s in sub:
                    s["name"] = f"{st['name']}.{s['name']}"
                    s["via_proc"] = proc
                steps.extend(sub)
                step = {"name": st["name"], "proc": proc, "expanded": sub, "line": st["line"]}
                if not ppath:
                    steps.append({"name": st["name"], "proc": proc, "program": None, "dds": {}, "line": st["line"]})
            else:
                step = {"name": st["name"], "program": pgm, "parm": args.get("PARM"), "dds": {},
                        "line": st["line"]}
                steps.append(step)
        elif st["op"] == "DD" and step is not None:
            dd = st["name"]
            target = step
            if "." in dd and step.get("expanded") is not None:
                pstep, dd = dd.split(".", 1)
                target = next((s for s in step["expanded"] if s["name"].endswith(f".{pstep}")), None)
                if target is None:
                    continue
            elif step.get("expanded") is not None:
                continue
            dsn = args.get("DSN") or args.get("DSNAME")
            entry = {"dsn": substitute(dsn, symbols) if dsn else None, "disp": args.get("DISP"),
                     "line": st["line"]}
            if st["data"]:
                entry["data"] = st["data"]
            if dd:
                target["dds"][dd] = entry
            elif target["dds"]:
                last = list(target["dds"].values())[-1]
                last.setdefault("concat", []).append(entry["dsn"])
    return steps


def norm_dsn(dsn):
    if not dsn:
        return None
    dsn = dsn.strip("'")
    gdg = re.match(r"^(.*)\(([+-]?\d+)\)$", dsn)
    return gdg.group(1) if gdg else dsn


def disp_rel(disp):
    first = (disp or "SHR").strip("()").split(",")[0] or "NEW"
    return {"NEW": "write", "MOD": "write", "OLD": "update"}.get(first, "read")


def idcams_ops(lines, dds):
    """Datasets an IDCAMS SYSIN touches: REPRO/PRINT in and out, DEFINE, DELETE."""
    text = " ".join(l.strip() for l in lines).upper().replace("-", " ")
    ops = []
    for cmd in re.split(r"(?=\b(?:REPRO|PRINT|DEFINE|DELETE|BLDINDEX|LISTCAT)\b)", text):
        verb = cmd.split()[0] if cmd.split() else ""
        for key, r in (("INFILE", "read"), ("INDATASET", "read"), ("IDS", "read"),
                       ("OUTFILE", "write"), ("OUTDATASET", "write"), ("ODS", "write")):
            for v in re.findall(rf"\b{key}\s*\(\s*([A-Z0-9.$#@]+)", cmd):
                dsn = (dds.get(v) or {}).get("dsn") if "FILE" in key else v
                if dsn:
                    ops.append((r, norm_dsn(dsn)))
        if verb == "DEFINE":
            head = re.split(r"\b(?:DATA|INDEX)\s*\(", cmd)[0]
            for v in re.findall(r"\bNAME\s*\(\s*([A-Z0-9.$#@]+)", head):
                ops.append(("define", v))
        elif verb == "DELETE":
            for v in re.findall(r"DELETE\s+\(?\s*([A-Z0-9.$#@]+)", cmd):
                ops.append(("delete", v))
    return ops


def scan_jcl(inv, programs):
    procs = {p.stem.upper(): p for p in files("proc/*")}
    ctls = {p.stem.upper(): p for p in files("ctl/*", "app-*/ctl/*")}
    for name, p in procs.items():
        inv.node("proc", name, path=rel(p))
    for path in files("jcl/*", "app-*/jcl/*", "proc/*"):
        is_proc = path.parent.name == "proc"
        jid = f"proc:{path.stem.upper()}" if is_proc else inv.node("job", path.stem.upper(), path=rel(path),
                                                                    module=module_of(path))
        steps = parse_steps(path, procs if not is_proc else {k: v for k, v in procs.items() if v != path})
        inv.nodes[jid]["steps"] = [
            {"step": s["name"], "program": s.get("program"), "proc": s.get("via_proc") or s.get("proc")}
            for s in steps]
        for s in steps:
            ev = f"{rel(path)}:{s['line']}"
            pgm = s.get("program")
            parm = (s.get("parm") or "").strip("'()")
            if pgm == "DFSRRC00" and parm:
                parts = parm.split(",")
                if len(parts) >= 3 and parts[0] in ("BMP", "DLI", "MPP"):
                    inv.edge(jid, "runs", f"program:{parts[1]}", ev)
                    inv.edge(f"program:{parts[1]}", "schedules", f"psb:{parts[2]}", ev)
                    pgm = parts[1]
            elif pgm in ("IKJEFT01", "IKJEFT1B"):
                systsin = " ".join((s["dds"].get("SYSTSIN") or {}).get("data", []))
                for m in re.finditer(r"RUN\s+PROGRAM\(\s*(\w+)\s*\)", systsin):
                    inv.edge(jid, "runs", f"program:{m.group(1)}", ev)
                    pgm = m.group(1)
            if pgm:
                inv.edge(jid, "runs", f"program:{pgm}", ev)
            sysin = s["dds"].get("SYSIN") or {}
            data = sysin.get("data", [])
            member = re.search(r"\((\w+)\)$", sysin.get("dsn") or "")
            if not data and member and member.group(1) in ctls:
                data = ctls[member.group(1)].read_text(errors="replace").splitlines()
                inv.edge(jid, "control-card", f"ctl:{member.group(1)}", ev)
                inv.node("ctl", member.group(1), path=rel(ctls[member.group(1)]))
            if pgm == "IDCAMS":
                for r, dsn in idcams_ops(data, s["dds"]):
                    inv.edge(jid, r, f"dataset:{dsn}", ev)
            if pgm == "SDSF":
                isfin = " ".join((s["dds"].get("ISFIN") or {}).get("data", []))
                for m in re.finditer(r"CEMT\s+SET\s+FIL\(\s*(\w+)\s*\)\s+(CLO|OPE)", isfin):
                    inv.edge(jid, "close-cics-file" if m.group(2) == "CLO" else "open-cics-file",
                             f"cics-file:{m.group(1)}", ev)
            if pgm == "DFHCSDUP":
                inv.nodes[jid]["csd_defines"] = [
                    {"type": k, "name": n} for k, n, _ in parse_csd_text("\n".join(data))]
            readers = closure(pgm, programs) if pgm in programs else []
            for dd, entry in s["dds"].items():
                dsn = norm_dsn(entry.get("dsn"))
                if not dsn or dsn.startswith("&&") or dd in ("STEPLIB", "SYSIN", "SYSTSIN", "JOBLIB"):
                    continue
                if entry.get("dsn", "").endswith(")") and not re.search(r"\([+-]?\d+\)$", entry["dsn"]):
                    continue
                if dsn == "NULLFILE":
                    continue
                inv.node("dataset", dsn)
                used = False
                for prog in readers:
                    access = programs[prog].get("dd_access", {}).get(dd)
                    if access is not None:
                        for r in access or [disp_rel(entry.get("disp"))]:
                            inv.edge(f"program:{prog}", r, f"dataset:{dsn}", ev)
                        programs[prog].setdefault("dd_bindings", {})[dd] = dsn
                        used = True
                if not used and pgm != "IDCAMS":
                    inv.edge(jid, disp_rel(entry.get("disp")), f"dataset:{dsn}", ev)
                for c in entry.get("concat", []):
                    if norm_dsn(c):
                        inv.node("dataset", norm_dsn(c))
                        inv.edge(jid, "read", f"dataset:{norm_dsn(c)}", ev)


def closure(pgm, programs):
    seen, todo = [], [pgm]
    while todo:
        p = todo.pop()
        if p in seen or p not in programs:
            continue
        seen.append(p)
        todo.extend(programs[p].get("_calls", []))
    return seen


def scan_scheduler(inv):
    ctm = APP / "scheduler" / "CardDemo.controlm"
    root = ET.parse(ctm).getroot()
    for folder in root:
        fname = folder.get("FOLDER_NAME") or folder.get("JOBNAME")
        jobs = [j.get("JOBNAME") for j in folder.iter("JOB")]
        inv.node("schedule", f"controlm:{fname}", path=rel(ctm), jobs=jobs)
        produced = {}
        for j in folder.iter("JOB"):
            for oc in j.iter("OUTCOND"):
                if oc.get("SIGN", "+") == "+":
                    produced.setdefault(oc.get("NAME"), j.get("JOBNAME"))
        for j in folder.iter("JOB"):
            for ic in j.iter("INCOND"):
                src = produced.get(ic.get("NAME"))
                if src and src != j.get("JOBNAME"):
                    inv.edge(f"job:{src}", "precedes", f"job:{j.get('JOBNAME')}", f"{rel(ctm)}:{fname}")
    ca7 = APP / "scheduler" / "CardDemo.ca7"
    cur = None
    for line in ca7.read_text(errors="replace").splitlines():
        m = re.search(r"1LJOB,JOB=(\w+)", line)
        if m:
            cur = m.group(1)
            continue
        m = re.match(r"^\s+JOB=(\w+)\s+SCHID=", line)
        if m and cur:
            inv.edge(f"job:{cur}", "triggers", f"job:{m.group(1)}", rel(ca7))


# ---------------------------------------------------------------- Build


def build():
    inv = Inventory()
    scan_csd(inv)
    scan_bms(inv)
    scan_asm(inv)
    scan_listcat(inv)
    db2_tables = scan_db2(inv)
    ims_segments = scan_ims(inv)

    copybooks = CopybookIndex()
    sources = files("**/cbl/*")
    names = {p.stem.upper() for p in sources} | {p.stem.upper() for p in files("asm/*")}
    names |= {n["name"] for n in inv.nodes.values() if n["kind"] == "program"}
    ctx = {"program_names": names, "db2_tables": db2_tables, "ims_segments": ims_segments,
           "record_refs": []}
    progs = [Program(p, copybooks) for p in sources]
    for prog in progs:
        scan_program(inv, prog, ctx)

    called = defaultdict(set)
    for e in inv.edges.values():
        if e["rel"] == "call":
            called[e["from"]].add(e["to"].split(":", 1)[1])
    programs = {}
    for n in inv.nodes.values():
        if n["kind"] == "program":
            n["_calls"] = sorted(called.get(n["id"], ()))
            programs[n["name"]] = n
    scan_jcl(inv, programs)
    scan_scheduler(inv)
    link_records(inv, ctx["record_refs"], programs)
    finish(inv)
    return inv


def link_records(inv, refs, programs):
    """Attach record-layout copybooks to the datasets a program reads or writes through them."""
    for prog, target, rec in refs:
        if not rec:
            continue
        rec = rec.split()[0]
        cpy = next((c for c, defs in prog.cpy_defines.items() if rec in defs), None)
        if not cpy:
            continue
        if isinstance(target, tuple):
            dsn = programs[prog.name].get("dd_bindings", {}).get(target[1])
            if not dsn:
                continue
            target = f"dataset:{dsn}"
        inv.edge(target, "record-layout", f"copybook:{cpy}", rel(prog.path))


def finish(inv):
    for n in inv.nodes.values():
        n.pop("_calls", None)
    for e in list(inv.edges.values()):
        for end in (e["from"], e["to"]):
            if end not in inv.nodes:
                kind, name = end.split(":", 1)
                inv.node(kind, name)
    for n in inv.nodes.values():
        if n["kind"] != "program":
            continue
        if n["name"] in EXTERNAL_PROGRAMS:
            n["type"] = "external"
            n["provider"] = EXTERNAL_PROGRAMS[n["name"]]
        elif "path" not in n:
            n["type"] = "missing-source"
    callers = defaultdict(set)
    for e in inv.edges.values():
        if e["rel"] in ("call", "runs"):
            callers[e["to"]].add(e["from"].split(":", 1)[0])
    for n in inv.nodes.values():
        if n["kind"] == "program" and n.get("type") == "batch" and "job" not in callers[n["id"]] \
                and "program" in callers[n["id"]]:
            n["type"] = "subroutine"
    slice_of = {p: s[0] for s in SLICES for p in s[2]}
    for n in inv.nodes.values():
        if n["kind"] == "program" and n["name"] in slice_of:
            n["slice"] = slice_of[n["name"]]
    module_slice = {"app-transaction-type-db2": "5a-txn-type-db2",
                    "app-authorization-ims-db2-mq": "5b-authorization-ims-db2-mq"}
    for n in inv.nodes.values():
        if n["kind"] != "job":
            continue
        if "path" not in n:
            n["missing_source"] = True
            continue
        out = [e for e in inv.edges.values() if e["from"] == n["id"]]
        ran = [inv.nodes.get(e["to"], {}).get("slice") for e in out if e["rel"] == "runs"]
        ran = [r for r in ran if r]
        if n.get("module") in module_slice:
            n["slice"] = module_slice[n["module"]]
        elif ran:
            n["slice"] = max(ran, key=slice_rank)
        elif any(e["rel"] in ("define", "delete") for e in out) or n.get("csd_defines"):
            n["slice"] = "0-data-layer"
        else:
            n["slice"] = "4-batch"


def slice_rank(s):
    return [x[0] for x in SLICES].index(s) if s in [x[0] for x in SLICES] else -1


# ---------------------------------------------------------------- Markdown


def render_markdown(data):
    nodes = {n["id"]: n for n in data["nodes"]}
    out_edges = defaultdict(list)
    in_edges = defaultdict(list)
    for e in data["edges"]:
        out_edges[e["from"]].append(e)
        in_edges[e["to"]].append(e)

    def targets(nid, rels, kind=None):
        return sorted({e["to"].split(":", 1)[1] for e in out_edges[nid]
                       if e["rel"] in rels and (kind is None or e["to"].startswith(kind + ":"))})

    def access(nid):
        acc = defaultdict(set)
        for e in out_edges[nid]:
            if e["rel"] in ("read", "write", "update", "delete", "uses"):
                k, name = e["to"].split(":", 1)
                if k == "cics-file":
                    ds = targets(e["to"], {"dataset"})
                    name = f"{name} ({short_dsn(ds[0])})" if ds else name
                elif k == "dataset":
                    name = short_dsn(name)
                acc[f"{k}:{name}"].add(e["rel"][0].upper() if e["rel"] != "uses" else "U")
        return ", ".join(f"{k.split(':', 1)[1]} [{''.join(sorted(v))}]" for k, v in sorted(acc.items())) or ""

    L = ["<!-- Generated by migration/inventory/build_inventory.py. Do not edit by hand. -->", ""]
    progs = sorted((n for n in data["nodes"] if n["kind"] == "program"), key=lambda n: (n.get("module", ""), n["name"]))
    counts = defaultdict(int)
    for n in data["nodes"]:
        counts[n["kind"]] += 1
    L += ["## Counts", "", "| Kind | Count |", "|---|---|"]
    L += [f"| {k} | {v} |" for k, v in sorted(counts.items())]

    L += ["", "## Slices", "",
          "Resources resolve CICS files to their VSAM dataset. Cross-slice lists XCTL/LINK/CALL/submit edges into other slices.", "",
          "| Slice | Programs | Trans IDs | Mapsets | Reads | Writes | Cross-slice edges |", "|---|---|---|---|---|---|---|"]
    for sid, title, members in SLICES:
        reads, writes, cross, tids, maps = set(), set(), set(), set(), set()
        for p in members:
            pid = f"program:{p}"
            tids |= {e["from"].split(":", 1)[1] for e in in_edges[pid] if e["from"].startswith("transaction:")}
            maps |= set(targets(pid, {"uses-map"}))
            for e in out_edges[pid]:
                k, name = e["to"].split(":", 1)
                if k == "cics-file":
                    ds = targets(e["to"], {"dataset"})
                    k, name = ("dataset", ds[0]) if ds else (k, name)
                label = short_dsn(name)
                if e["rel"] == "read":
                    reads.add(label)
                elif e["rel"] in ("write", "update", "delete", "uses"):
                    writes.add(label)
                elif e["rel"] in ("xctl", "link", "call", "submits"):
                    other = nodes.get(e["to"], {}).get("slice")
                    if other != sid and nodes.get(e["to"], {}).get("type") != "external":
                        cross.add(f"{p} {e['rel']} {name} ({other or 'job'})")
        L.append(f"| {sid}: {title} | {', '.join(members)} | {', '.join(sorted(tids))} | {', '.join(sorted(maps))} | "
                 f"{', '.join(sorted(reads))} | {', '.join(sorted(writes))} | {'<br>'.join(sorted(cross))} |")

    L += ["", "## Online programs (CICS)", "",
          "Access codes: R read, W write, U update, D delete. `uses` edges without a direction show as U.", "",
          "| Program | Module | Slice | Trans IDs | Mapsets | XCTL / LINK to | CALLs | Data access |",
          "|---|---|---|---|---|---|---|---|"]
    for n in progs:
        if n.get("type") != "cics":
            continue
        tids = sorted({e["from"].split(":", 1)[1] for e in in_edges[n["id"]] if e["rel"] == "runs"
                       and e["from"].startswith("transaction:")})
        L.append(f"| {n['name']} | {n.get('module')} | {n.get('slice', '')} | {', '.join(tids)} | "
                 f"{', '.join(targets(n['id'], {'uses-map'}))} | "
                 f"{', '.join(targets(n['id'], {'xctl'}) + [t + ' (LINK)' for t in targets(n['id'], {'link'})])} | "
                 f"{', '.join(targets(n['id'], {'call'}) + ['submits PROC ' + t for t in targets(n['id'], {'submits'})])} | "
                 f"{access(n['id'])} |")

    L += ["", "## Batch programs and subroutines", "",
          "| Program | Type | Module | Slice | Run by jobs | CALLs | Data access |", "|---|---|---|---|---|---|---|"]
    for n in progs:
        if n.get("type") not in ("batch", "subroutine"):
            continue
        jobs = sorted({e["from"].split(":", 1)[1] for e in in_edges[n["id"]] if e["rel"] == "runs"
                       and e["from"].startswith("job:")})
        callers = sorted({e["from"].split(":", 1)[1] for e in in_edges[n["id"]] if e["rel"] == "call"})
        run = ", ".join(jobs) + (f" (called by {', '.join(callers)})" if callers else "")
        extra = ""
        if n.get("dd_access") and not n.get("dd_bindings"):
            extra = " DDs (no JCL binds them): " + ", ".join(
                f"{dd}[{''.join(a[0].upper() for a in acc)}]" for dd, acc in n["dd_access"].items())
        L.append(f"| {n['name']} | {n.get('type')} ({n.get('language')}) | {n.get('module')} | "
                 f"{n.get('slice', '')} | {run} | {', '.join(targets(n['id'], {'call'}))} | "
                 f"{access(n['id'])}{extra} |")

    L += ["", "## Programs without source or outside the repo", "", "| Program | Type | Referenced by |", "|---|---|---|"]
    for n in progs:
        if n.get("type") in ("external", "missing-source"):
            refs = sorted({e["from"].split(":", 1)[1] for e in in_edges[n["id"]]})
            L.append(f"| {n['name']} | {n.get('type')}{' (' + n['provider'] + ')' if n.get('provider') else ''} | {', '.join(refs)} |")

    L += ["", "## Transactions", "", "| Trans ID | Program | Module | Description |", "|---|---|---|---|"]
    for n in sorted((n for n in data["nodes"] if n["kind"] == "transaction"), key=lambda n: n["name"]):
        L.append(f"| {n['name']} | {', '.join(targets(n['id'], {'runs'}))} | {n.get('module', '')} | {n.get('description') or ''} |")

    L += ["", "## BMS mapsets", "", "| Mapset | Maps | Named fields | Used by |", "|---|---|---|---|"]
    for n in sorted((n for n in data["nodes"] if n["kind"] == "mapset"), key=lambda n: n["name"]):
        users = sorted({e["from"].split(":", 1)[1] for e in in_edges[n["id"]] if e["rel"] == "uses-map"})
        L.append(f"| {n['name']} | {', '.join(n.get('maps', []))} | {n.get('named_fields', '')} | {', '.join(users)} |")

    L += ["", "## VSAM datasets and CICS files", "",
          "| Dataset | Type | Key len / offset | Max LRECL | CICS files | Record layouts | Online users | Batch users |",
          "|---|---|---|---|---|---|---|---|"]
    for n in sorted((n for n in data["nodes"] if n["kind"] == "dataset" and n.get("type") in ("KSDS", "ESDS", "RRDS", "AIX", "PATH")),
                    key=lambda n: n["name"]):
        cfiles = sorted({e["from"].split(":", 1)[1] for e in in_edges[n["id"]] if e["rel"] == "dataset"})
        online = sorted({e2["from"].split(":", 1)[1] for f in cfiles for e2 in in_edges[f"cics-file:{f}"]
                         if e2["from"].startswith("program:") and e2["rel"] in ("read", "write", "update", "delete")})
        batch = sorted({e["from"].split(":", 1)[1] for e in in_edges[n["id"]]
                        if e["from"].startswith("program:")})
        layouts = targets(n["id"], {"record-layout"})
        for f in cfiles:
            layouts = sorted(set(layouts) | set(targets(f"cics-file:{f}", {"record-layout"})))
        base = targets(n["id"], {"base"})
        typ = n.get("type") + (f" over {short_dsn(base[0])}" if base else "")
        key = f"{n.get('keylen', '')} / {n.get('rkp', '')}" if "keylen" in n else ""
        L.append(f"| {short_dsn(n['name'])} | {typ} | {key} | {n.get('maxlrecl', '')} | {', '.join(cfiles)} | "
                 f"{', '.join(layouts)} | {', '.join(online)} | {', '.join(batch)} |")

    other = [n for n in data["nodes"] if n["kind"] in ("db2-table", "ims-segment", "psb", "dbd", "mq-queue", "tdqueue")]
    L += ["", "## Db2, IMS, MQ and TD queue resources", "", "| Resource | Kind | Module | Used by (access) |", "|---|---|---|---|"]
    for n in sorted(other, key=lambda n: (n["kind"], n["name"])):
        users = defaultdict(set)
        for e in in_edges[n["id"]]:
            if e["from"].startswith("program:"):
                users[e["from"].split(":", 1)[1]].add(e["rel"])
        L.append(f"| {n['name']} | {n['kind']} | {n.get('module', '')} | "
                 f"{', '.join(f'{u} ({'/'.join(sorted(r))})' for u, r in sorted(users.items()))} |")

    L += ["", "## JCL jobs", "", "| Job | Module | Slice | Steps (program) | Datasets written by the job or its programs |", "|---|---|---|---|---|"]
    for n in sorted((n for n in data["nodes"] if n["kind"] == "job"), key=lambda n: n["name"]):
        steps = ", ".join(f"{s['step']}={s.get('program') or 'PROC ' + str(s.get('proc'))}" for s in n.get("steps", []))
        progs_run = targets(n["id"], {"runs"})
        written = set(short_dsn(d) for d in targets(n["id"], {"write", "update", "define", "delete"}, "dataset"))
        for p in progs_run:
            written |= {short_dsn(d) for d in targets(f"program:{p}", {"write", "update", "delete"}, "dataset")}
        if n.get("missing_source"):
            L.append(f"| {n['name']} | (no JCL in repo; named by the CA-7 schedule) |  |  |  |")
            continue
        L.append(f"| {n['name']} | {n.get('module')} | {n.get('slice')} | {steps} | {', '.join(sorted(written))} |")

    L += ["", "## Schedules", "", "| Schedule | Jobs in order |", "|---|---|"]
    for n in sorted((n for n in data["nodes"] if n["kind"] == "schedule"), key=lambda n: n["name"]):
        L.append(f"| {n['name']} | {' > '.join(n.get('jobs', []))} |")
    triggers = sorted(f"{e['from'].split(':', 1)[1]} > {e['to'].split(':', 1)[1]}" for e in data["edges"] if e["rel"] == "triggers")
    L += ["", f"CA-7 triggers: {', '.join(triggers)}."]
    cemt = defaultdict(set)
    for e in data["edges"]:
        if e["rel"] == "close-cics-file":
            cemt[e["from"].split(":", 1)[1]].add(e["to"].split(":", 1)[1])
    L += ["", "Jobs that close CICS files before batch runs: " +
          "; ".join(f"{j} ({', '.join(sorted(f))})" for j, f in sorted(cemt.items())) + "."]

    L += ["", "## Copybook usage", "", "| Copybook | Used by |", "|---|---|"]
    for n in sorted((n for n in data["nodes"] if n["kind"] == "copybook"), key=lambda n: n["name"]):
        users = sorted({e["from"].split(":", 1)[1] for e in in_edges[n["id"]] if e["rel"] == "copies"})
        L.append(f"| {n['name']}{' (external)' if n.get('external') else ''}{' (not found)' if 'path' not in n and not n.get('external') else ''} | {', '.join(users)} |")

    L += ["", "## Unresolved references", "", "| Where | What | Note |", "|---|---|---|"]
    for u in data["unresolved"]:
        L.append(f"| {u['where']} | `{u['what']}` | {u['note']} |")

    L += ["", "## Online navigation graph", "", "```mermaid", "flowchart LR"]
    for n in sorted((n for n in data["nodes"] if n["kind"] == "transaction"), key=lambda n: n["name"]):
        for p in targets(n["id"], {"runs"}):
            L.append(f"  T_{n['name']}([{n['name']}]) --> {p}")
    for n in progs:
        if n.get("type") == "cics":
            for t in targets(n["id"], {"xctl"}):
                L.append(f"  {n['name']} --> {t}")
            for t in targets(n["id"], {"link"}):
                L.append(f"  {n['name']} -. LINK .-> {t}")
    L += ["```", "", "## Program to data graph", "", "```mermaid", "flowchart LR"]
    for n in progs:
        if n.get("type") not in ("cics", "batch", "subroutine"):
            continue
        for e in out_edges[n["id"]]:
            k, name = e["to"].split(":", 1)
            if e["rel"] not in ("read", "write", "update", "delete", "uses"):
                continue
            if k == "cics-file":
                ds = targets(e["to"], {"dataset"})
                if not ds:
                    continue
                name, k = ds[0], "dataset"
            if k not in ("dataset", "db2-table", "ims-segment", "mq-queue"):
                continue
            dnode = nodes.get(f"{k}:{name}", {})
            if k == "dataset" and dnode.get("type") not in ("KSDS", "AIX", "PATH"):
                continue
            label = short_dsn(name) if k == "dataset" else name
            arrow = "-->" if e["rel"] in ("read", "uses") else "==>"
            if e["rel"] == "read":
                L.append(f"  {mid(k, name)}[({label})] {arrow} {n['name']}")
            else:
                L.append(f"  {n['name']} {arrow} {mid(k, name)}[({label})]")
    L = dedupe(L)
    L += ["```", ""]
    return "\n".join(L)


def mid(kind, name):
    return re.sub(r"[^A-Za-z0-9]", "_", f"{kind}_{short_dsn(name)}")


def short_dsn(dsn):
    return dsn.replace("AWS.M2.CARDDEMO.", "")


def dedupe(lines):
    seen, out = set(), []
    for line in lines:
        if line.startswith("  ") and line in seen:
            continue
        seen.add(line)
        out.append(line)
    return out


def main():
    data = build().to_json()
    outputs = {
        OUT_DIR / "inventory.json": json.dumps(data, indent=1, sort_keys=True) + "\n",
        OUT_DIR / "inventory.md": "# CardDemo inventory (generated)\n\n" + render_markdown(data),
    }
    if "--check" in sys.argv:
        stale = [p.name for p, body in outputs.items() if not p.exists() or p.read_text() != body]
        if stale:
            sys.exit(f"stale: {', '.join(stale)}; rerun build_inventory.py")
        return
    for p, body in outputs.items():
        p.write_text(body)
    print(f"wrote {len(data['nodes'])} nodes, {len(data['edges'])} edges, "
          f"{len(data['unresolved'])} unresolved references")


if __name__ == "__main__":
    main()
