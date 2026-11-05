#!/usr/bin/env python3
"""Derive the CardDemo program inventory and dependency map from the sources under app/.

Reads CSD, JCL/PROC, COBOL, copybooks, BMS, assembler and the IDCAMS LISTCAT, then writes
inventory.json (machine-readable) and inventory-and-dependency-map.md (human report).

    python3 scripts/inventory/build_inventory.py [--app app] [--json out.json] [--md out.md]

Standard library only. Deterministic: rerunning on the same tree produces identical output.
"""
import argparse
import json
import re
from collections import defaultdict
from dataclasses import asdict, dataclass, field
from pathlib import Path

FIGURATIVE = {"SPACE", "SPACES", "LOW-VALUE", "LOW-VALUES", "HIGH-VALUE", "HIGH-VALUES",
              "ZERO", "ZEROS", "ZEROES", "QUOTE", "QUOTES", "NULL", "NULLS"}
LITERAL = r"'(?:[^']|'')*'|\"[^\"]*\""
NAME = r"[A-Z0-9][A-Z0-9-]*"

# Migration slices, in match order. A program lands in the first slice whose pattern matches.
SLICES = [
    ("signon-user-admin", "Sign-on / user admin", r"^(COSGN00C|COUSR\d\dC)$"),
    ("account-card", "Account / card", r"^(COACTVWC|COACTUPC|COCRD\w+)$"),
    ("transactions-billpay", "Transactions / bill pay", r"^(COTRN\d\dC|COBIL00C)$"),
    ("batch", "Batch", r"^(CBACT\w+|CBTRN\w+|CBSTM03\w)$"),
]
OPTIONAL_SLICE = ("optional-db2-ims-mq", "Optional Db2 / IMS / MQ")
UNASSIGNED = ("unassigned", "Does not fit a named slice")

# Judgment calls for programs outside the named slices: where they should travel and why.
SUGGESTED_HOME = {
    "COMEN01C": ("signon-user-admin", "Main menu; XCTL hub that every user-facing slice returns to."),
    "COADM01C": ("signon-user-admin", "Admin menu; only reaches COUSR* (and optional COTRT*)."),
    "CORPT00C": ("transactions-billpay", "Online report request; submits TRANREPT JCL via TDQ JOBS, so it couples online to batch."),
    "CSUTLDTC": ("shared-utility", "Date validation subroutine (wraps LE CEEDAYS) CALLed from three CICS programs; port as a shared Java utility."),
    "CBCUS01C": ("batch", "Customer file dump utility (READCUST); same shape as CBACT02C/03C."),
    "CBEXPORT": ("batch", "Data export job (CBEXPORT); newer addition, reads all master files."),
    "CBIMPORT": ("batch", "Data import job (CBIMPORT); companion of CBEXPORT."),
    "COBSWAIT": ("batch", "Wait step used between batch jobs; replace with scheduler delay, not a Java port."),
    "COBDATFT": ("shared-utility", "Assembler date formatter CALLed by CBACT01C; reimplement with java.time."),
    "MVSWAIT": ("batch", "Assembler timer wait CALLed by COBSWAIT; drop with COBSWAIT."),
    "COCRDSEC": ("account-card", "Defined in CSD (transaction CDV1) but no source exists in the repo."),
}

SYSTEM_PROGRAMS = {"CEE3ABD", "CEEDAYS", "CBLTDLI", "MQOPEN", "MQGET", "MQPUT", "MQPUT1",
                   "MQCLOSE", "MQCONN", "MQDISC", "MQINQ", "MQSET", "DSNTIAUL", "DSNTEP2", "DSNTEP4"}
UTILITY_PGMS = {"IDCAMS", "IEFBR14", "IEBGENER", "SORT", "ICETOOL", "IEBCOPY", "IKJEFT01",
                "IKJEFT1B", "DFSRRC00", "FTP", "SDSF", "IEBEDIT", "TXT2PDF", "DSNUTILB", "DFHCSDUP"}


@dataclass
class Call:
    kind: str            # XCTL | LINK | CALL | START
    target: str
    resolution: str      # literal | value | move | table | unresolved


@dataclass
class Access:
    kind: str            # vsam-file | seq-file | tdq | tsq | db2-table | ims-segment | ims-pcb | mq-queue
    name: str            # CICS FILE, DD name, table, segment, queue
    via: str             # CICS | COBOL-IO | SQL | DLI | CBLTDLI | MQI
    modes: list = field(default_factory=list)
    dsns: list = field(default_factory=list)
    record_copybooks: list = field(default_factory=list)
    organization: str = ""


@dataclass
class JclStep:
    job: str
    file: str
    step: str
    pgm: str
    proc: str = ""
    procstep: str = ""
    app_program: str = ""
    dds: dict = field(default_factory=dict)   # DD name -> [DSN, ...]
    disp: dict = field(default_factory=dict)  # DD name -> DISP text
    sysin_dsns: list = field(default_factory=list)  # DSNs named inside instream control cards


@dataclass
class Program:
    name: str
    source: str
    module: str
    type: str = ""
    transactions: list = field(default_factory=list)
    mapsets: list = field(default_factory=list)
    calls: list = field(default_factory=list)
    called_by: list = field(default_factory=list)
    copybooks: list = field(default_factory=list)
    missing_copybooks: list = field(default_factory=list)
    accesses: list = field(default_factory=list)
    jcl_steps: list = field(default_factory=list)
    slice: str = ""
    suggested_slice: str = ""
    notes: list = field(default_factory=list)


@dataclass
class Dataset:
    dsn: str
    kind: str = "UNCATALOGED"   # KSDS | ESDS | RRDS | AIX | PATH | GDG | NONVSAM | UNCATALOGED
    sources: list = field(default_factory=list)
    key_length: int = None
    key_offset: int = None
    avg_lrecl: int = None
    max_lrecl: int = None
    base: str = ""
    paths: list = field(default_factory=list)
    cics_files: list = field(default_factory=list)
    copybooks: list = field(default_factory=list)
    programs: dict = field(default_factory=dict)  # program -> sorted modes
    utility_steps: list = field(default_factory=list)  # JOB.STEP(PGM) for IDCAMS/SORT/etc.



def code_lines(path):
    lines = []
    for raw in path.read_text(errors="replace").splitlines():
        raw = raw.expandtabs(8)
        if len(raw) < 8 or raw[6] in "*/D":
            continue
        code = raw[7:72].upper()
        if raw[6] == "-" and lines:
            cont = code.lstrip()
            lines[-1] = lines[-1].ljust(65) + (cont[1:] if cont[:1] in "'\"" else " " + cont)
            continue
        lines.append(code)
    return lines


def split_sentences(text):
    out, cur, quote = [], [], None
    for i, ch in enumerate(text):
        cur.append(ch)
        if quote:
            if ch == quote:
                quote = None
        elif ch in "'\"":
            quote = ch
        elif ch == "." and (i + 1 == len(text) or text[i + 1] == " "):
            out.append("".join(cur[:-1]).strip())
            cur = []
    tail = "".join(cur).strip()
    if tail:
        out.append(tail)
    return [s for s in out if s]


def unquote(lit):
    return lit[1:-1].replace("\x01", " ").replace("''", "'").rstrip()


class CopybookLibrary:
    def __init__(self, app):
        self.index = {}
        dirs = [app / "cpy", app / "cpy-bms"]
        for mod in sorted(app.glob("app-*")):
            dirs += [mod / "cpy", mod / "cpy-bms", mod / "dcl"]
        for d in dirs:
            for f in sorted(d.glob("[!.]*")) if d.is_dir() else []:
                self.index.setdefault(f.stem.upper(), f)
        self.used = defaultdict(set)

    def expand(self, path, owner, origin=None, stack=()):
        """Return [(line, origin_copybook)], recording copybook use against owner."""
        out, missing = [], set()
        for line in code_lines(path):
            m = re.search(r"\bCOPY\s+['\"]?([A-Z0-9@#$-]+)", line) or \
                re.search(r"\bEXEC\s+SQL\s+INCLUDE\s+([A-Z0-9@#$-]+)", line) or \
                (re.match(r"\s*INCLUDE\s+([A-Z0-9@#$-]+)", line) if out and "EXEC SQL" in out[-1][0] else None)
            if not m:
                out.append((line, origin))
                continue
            name = m.group(1)
            if name in self.index and name not in stack:
                self.used[name].add(owner)
                sub, sub_missing = self.expand(self.index[name], owner, name, stack + (name,))
                out += sub
                missing |= sub_missing
            elif name not in ("SQLCA", "REPLACING") and not name.startswith(("DFH", "CMQ")):
                missing.add(name)
        return out, missing


class Symbols:
    """VALUE clauses and MOVE edges, used to resolve names like LIT-ACCTFILENAME to 'ACCTDAT'."""

    def __init__(self, expanded, data_sentences, proc_text):
        self.origin, self.values, self.moves = {}, defaultdict(set), defaultdict(set)
        self.copybook_literals = defaultdict(set)
        for line, origin in expanded:
            m = re.match(rf"\s*(\d\d?)\s+({NAME})", line)
            if m and m.group(1) != "88":
                self.origin.setdefault(m.group(2), origin)
            if origin:
                for lit in re.findall(r"'([A-Z][A-Z0-9]{3,7})\s*'", line):
                    self.copybook_literals[origin].add(lit)
        for s in data_sentences:
            m = re.match(rf"(\d\d?)\s+({NAME})\b.*?\bVALUES?\s+(?:IS\s+|ARE\s+)?({LITERAL})", s)
            if m and m.group(1) != "88":
                self.values[m.group(2)].add(unquote(m.group(3)))
        for src, dst in re.findall(
                rf"(?<![\w-])MOVE\s+(?:ALL\s+)?({LITERAL}|{NAME}(?:\s*\([^)]*\))?(?:\s+(?:OF|IN)\s+{NAME})?)\s+TO\s+({NAME})",
                proc_text):
            self.moves[dst].add(src)

    @staticmethod
    def base(expr):
        return re.split(r"\s*\(|\s+(?:OF|IN)\s+", expr.strip())[0]

    def resolve(self, expr, seen=None):
        """Return [(value, resolution)] for a literal or data name."""
        expr = expr.strip()
        if re.fullmatch(LITERAL, expr):
            return [(unquote(expr), "literal")]
        seen = seen or set()
        name = self.base(expr)
        if name in seen or name in FIGURATIVE:
            return []
        seen.add(name)
        found = [(v, "value") for v in sorted(self.values.get(name, ()))]
        for src in sorted(self.moves.get(name, ())):
            for v, how in self.resolve(src, seen):
                found.append((v, "move" if how != "unresolved" else how))
        if not found and "(" in expr and self.origin.get(name):
            found = [(v, "table") for v in sorted(self.copybook_literals[self.origin[name]])]
        return found or [(name, "unresolved")]


def parse_options(body):
    tokens = re.findall(r"([A-Z][A-Z0-9-]*)\s*(\((?:[^()]|\([^()]*\))*\))?", body)
    words = [t[0] for t in tokens]
    opts = {k: v[1:-1].strip() if v else True for k, v in tokens}
    return words, opts


CICS_FILE_MODES = {"READ": "read", "WRITE": "write", "REWRITE": "rewrite", "DELETE": "delete",
                   "STARTBR": "browse", "READNEXT": "browse", "READPREV": "browse", "RESETBR": "browse"}
DLI_MODES = {"GU": "read", "GN": "read", "GNP": "read", "GHU": "read-update", "GHN": "read-update",
             "GHNP": "read-update", "ISRT": "insert", "REPL": "update", "DLET": "delete"}
OPEN_MODES = {"INPUT": "read", "OUTPUT": "write", "I-O": "read-write", "EXTEND": "append"}


def analyze_cobol(path, module, lib):
    name = path.stem.upper()
    expanded, missing = lib.expand(path, name)
    text = re.sub(r"\s+", " ", " ".join(l for l, _ in expanded))
    # Spaces inside literals become \x01 so verbs inside DISPLAY strings ('GNP CALL FAILED') never match.
    text = re.sub(LITERAL, lambda m: m.group(0).replace(" ", "\x01"), text)
    pd = re.search(r"\bPROCEDURE DIVISION\b", text)
    data_text, proc_text = (text[:pd.start()], text[pd.start():]) if pd else (text, "")
    data_sentences = split_sentences(data_text)
    execs = re.findall(r"\bEXEC\s+(CICS|SQL|DLI)\b(.*?)\bEND-EXEC\b", proc_text)
    plain = re.sub(r"\bEXEC\s+(CICS|SQL|DLI)\b.*?\bEND-EXEC\b", " ", proc_text)
    sym = Symbols(expanded, data_sentences, plain)

    prog = Program(name=name, source=str(path), module=module)
    prog.copybooks = sorted(c for c, owners in lib.used.items() if name in owners)
    prog.missing_copybooks = sorted(missing)
    accesses = {}

    def access(kind, rname, via, mode=None, record=None, org=""):
        a = accesses.setdefault((kind, rname, via), Access(kind=kind, name=rname, via=via, organization=org))
        if mode and mode not in a.modes:
            a.modes.append(mode)
        cb = sym.origin.get(Symbols.base(record)) if record else None
        if cb and cb not in a.record_copybooks:
            a.record_copybooks.append(cb)

    calls, mapsets, transids = [], set(), set()
    for kind, body in execs:
        body = body.strip()
        words, opts = parse_options(body)
        verb = words[0] if words else ""
        if kind == "CICS":
            if verb in ("XCTL", "LINK") and "PROGRAM" in opts:
                calls += [Call(verb, v, how) for v, how in sym.resolve(opts["PROGRAM"])]
            elif verb == "START" and "TRANSID" in opts:
                calls += [Call("START", v, how) for v, how in sym.resolve(opts["TRANSID"])]
            elif verb in ("SEND", "RECEIVE") and "MAPSET" in opts:
                mapsets |= {v for v, how in sym.resolve(opts["MAPSET"]) if how != "unresolved"}
            elif verb == "RETURN" and "TRANSID" in opts:
                transids |= {v for v, how in sym.resolve(opts["TRANSID"]) if how != "unresolved"}
            elif verb in CICS_FILE_MODES and ("DATASET" in opts or "FILE" in opts):
                mode = "read-update" if verb == "READ" and opts.get("UPDATE") is True else CICS_FILE_MODES[verb]
                record = opts.get("INTO") or opts.get("FROM")
                for v, _ in sym.resolve(opts.get("DATASET") or opts.get("FILE")):
                    access("vsam-file", v, "CICS", mode, record if isinstance(record, str) else None)
            elif verb in ("WRITEQ", "READQ", "DELETEQ"):
                qkind = "tsq" if "TS" in words[:3] else "tdq"
                q = opts.get("QUEUE") or opts.get("QNAME")
                if isinstance(q, str):
                    mode = {"WRITEQ": "write", "READQ": "read", "DELETEQ": "delete"}[verb]
                    for v, _ in sym.resolve(q):
                        access(qkind, v, "CICS", mode, opts.get("FROM") or opts.get("INTO"))
        elif kind == "SQL":
            mode = {"SELECT": "read", "DECLARE": "read", "INSERT": "insert",
                    "UPDATE": "update", "DELETE": "delete"}.get(verb)
            for table in re.findall(r"\b(?:FROM|INTO|UPDATE|JOIN)\s+([A-Z][A-Z0-9_]*\.[A-Z][A-Z0-9_]*)", body):
                access("db2-table", table, "SQL", mode)
        elif kind == "DLI":
            if verb in DLI_MODES:
                for seg in re.findall(r"\bSEGMENT\s*\(\s*\(?([A-Z0-9-]+)", body):
                    access("ims-segment", seg, "DLI", DLI_MODES[verb], opts.get("INTO") or opts.get("FROM"))
            elif verb == "SCHD" and "PSB" in opts:
                prog.notes += [f"schedules IMS PSB {v}" for v, how in sym.resolve(opts["PSB"].strip("()"))
                               if f"schedules IMS PSB {v}" not in prog.notes]

    for lit in re.findall(r"'(//[^']*)'|\"(//[^\"]*)\"", proc_text + " " + data_text):
        for m in re.finditer(r"EXEC\x01+(?:PROC=)?([A-Z0-9#@$=]+)", "".join(lit)):
            if not m.group(1).startswith("PGM="):
                calls.append(Call("SUBMIT-JCL", m.group(1), "literal"))

    mq_verbs = set()
    for m in re.finditer(rf"(?<![\w-])CALL\s+({LITERAL}|{NAME})(?:\s+USING\s+(.{{0,200}}))?", plain):
        target, using = m.group(1), m.group(2) or ""
        args = [a for a in re.findall(NAME, using.split(" END-CALL")[0])
                if a not in ("BY", "REFERENCE", "CONTENT", "VALUE")]
        for v, how in sym.resolve(target):
            if v.startswith("MQ"):
                mq_verbs.add(v)
            elif v == "CBLTDLI" and len(args) >= 2:
                func = sym.resolve(args[0])[0][0].strip()
                access("ims-pcb", args[1], "CBLTDLI", DLI_MODES.get(func, func.lower()), args[2] if len(args) > 2 else None)
            calls.append(Call("CALL", v, how))
    if mq_verbs:
        modes = sorted({"read" if v == "MQGET" else "write" for v in mq_verbs if v in ("MQGET", "MQPUT", "MQPUT1")})
        for q, how in sym.resolve("MQOD-OBJECTNAME"):
            qname = q if how != "unresolved" else f"runtime:{q}"
            for md in modes:
                access("mq-queue", qname, "MQI", md)

    selects = {}
    for m in re.finditer(rf"\bSELECT\s+(?:OPTIONAL\s+)?({NAME})\s+ASSIGN\s+(?:TO\s+)?({NAME})(.*?)(?=\bSELECT\b|\bDATA DIVISION\b|$)", data_text):
        org = re.search(r"ORGANIZATION\s+(?:IS\s+)?(LINE SEQUENTIAL|INDEXED|SEQUENTIAL|RELATIVE)", m.group(3))
        selects[m.group(1)] = (m.group(2), org.group(1) if org else "SEQUENTIAL")
    fd_records, current_fd = {}, None
    for s in data_sentences:
        if m := re.match(rf"FD\s+({NAME})", s):
            current_fd = m.group(1)
        elif re.match(r"(WORKING-STORAGE|LOCAL-STORAGE|LINKAGE)\s+SECTION|SD\s", s):
            current_fd = None
        elif current_fd and (m := re.match(rf"01\s+({NAME})", s)):
            fd_records[m.group(1)] = current_fd

    def file_access(fname, mode, record=None):
        if fname not in selects:
            return
        dd, org = selects[fname]
        access("vsam-file" if org in ("INDEXED", "RELATIVE") else "seq-file", dd, "COBOL-IO", mode, record, org)

    for m in re.finditer(r"(?<![\w-])OPEN\s+((?:(?:INPUT|OUTPUT|I-O|EXTEND)(?:\s+[A-Z0-9-]+)+\s*)+)", plain):
        mode = None
        for tok in m.group(1).split():
            if tok in OPEN_MODES:
                mode = OPEN_MODES[tok]
            elif tok in selects:
                file_access(tok, f"open-{mode}")
    for verb, f, rec in re.findall(rf"(?<![\w-])(READ|DELETE|START)\s+({NAME})(?:\s+NEXT)?(?:\s+RECORD)?(?:\s+INTO\s+({NAME}))?", plain):
        file_access(f, verb.lower(), rec or None)
    for verb, rec, src in re.findall(rf"(?<![\w-])(WRITE|REWRITE)\s+({NAME})(?:\s+FROM\s+({NAME}))?", plain):
        if rec in fd_records:
            file_access(fd_records[rec], verb.lower(), src or rec)
    for rec, f in fd_records.items():
        if sym.origin.get(rec) and f in selects:
            access(*(("vsam-file" if selects[f][1] in ("INDEXED", "RELATIVE") else "seq-file"),
                     selects[f][0], "COBOL-IO"), record=rec)

    seen = set()
    for c in calls:
        key = (c.kind, c.target)
        if c.target != name and key not in seen:
            seen.add(key)
            prog.calls.append(c)
    prog.mapsets = sorted(mapsets)
    prog.transactions = sorted(transids)
    prog.accesses = sorted(accesses.values(), key=lambda a: (a.kind, a.name))
    prog.type = "online-cics" if any(k == "CICS" for k, _ in execs) else "batch"
    return prog



def parse_csd(path):
    text = re.sub(r"\s+", " ", path.read_text(errors="replace").upper())
    out = []
    for m in re.finditer(r"DEFINE (\w+)\(([^)]*)\)(.*?)(?=DEFINE \w+\(|$)", text):
        out.append((m.group(1), m.group(2).strip(), dict(re.findall(r"([A-Z0-9]+)\(([^)]*)\)", m.group(3)))))
    return out


def parse_bms(path):
    mapset, maps = None, []
    for line in path.read_text(errors="replace").upper().splitlines():
        if m := re.match(r"([A-Z0-9#@$]+)\s+DFHMSD\b", line):
            mapset = mapset or m.group(1)
        elif m := re.match(r"([A-Z0-9#@$]+)\s+DFHMDI\b", line):
            maps.append(m.group(1))
    return mapset or path.stem.upper(), maps


def parse_listcat(path):
    entries, cur = [], None
    for line in path.read_text(errors="replace").splitlines():
        if m := re.match(r"0(CLUSTER|DATA|INDEX|AIX|PATH|GDG BASE|NONVSAM)\s*-+\s*(\S+)", line):
            cur = {"type": m.group(1), "name": m.group(2), "text": []}
            entries.append(cur)
        elif cur:
            cur["text"].append(line)
    datasets = {}

    def num(text, key):
        m = re.search(rf"{key}-+(\d+)", text)
        return int(m.group(1)) if m else None

    for e in entries:
        t = "\n".join(e["text"])
        assoc = dict((k, v) for k, v in re.findall(r"^\s+(CLUSTER|AIX|PATH|DATA|INDEX)-+(\S+)", t, re.M)
                     if k in ("CLUSTER", "AIX"))
        if e["type"] in ("NONVSAM", "GDG BASE"):
            if not re.search(r"\.G\d{4}V\d\d$", e["name"]):
                datasets[e["name"]] = Dataset(dsn=e["name"], kind="GDG" if e["type"] == "GDG BASE" else "NONVSAM",
                                              sources=["listcat"])
        elif e["type"] == "CLUSTER":
            datasets.setdefault(e["name"], Dataset(dsn=e["name"], sources=["listcat"])).kind = "KSDS"
        elif e["type"] == "AIX":
            d = datasets.setdefault(e["name"], Dataset(dsn=e["name"], sources=["listcat"]))
            d.kind, d.base = "AIX", assoc.get("CLUSTER", "")
        elif e["type"] == "PATH":
            d = datasets.setdefault(e["name"], Dataset(dsn=e["name"], sources=["listcat"]))
            d.kind, d.base = "PATH", assoc.get("AIX", "")
        elif e["type"] == "DATA":
            owner = assoc.get("CLUSTER") or assoc.get("AIX")
            d = datasets.setdefault(owner, Dataset(dsn=owner, sources=["listcat"]))
            d.key_length, d.key_offset = num(t, "KEYLEN"), num(t, "RKP")
            d.avg_lrecl, d.max_lrecl = num(t, "AVGLRECL"), num(t, "MAXLRECL")
            if d.kind != "AIX":
                d.kind = "ESDS" if "NONINDEXED" in t else "RRDS" if "NUMBERED" in t else "KSDS"
    return datasets


def parse_idcams_defines(text, datasets, origin):
    text = re.sub(r"-\s*\n", " ", text.upper())
    for m in re.finditer(r"\bDEFINE\s+(CLUSTER|AIX|ALTERNATEINDEX|PATH)\s*\((.*?)(?=\bDEFINE\b|\bREPRO\b|\bBLDINDEX\b|\bDELETE\b|\bIF\b|\Z)", text, re.S):
        body = m.group(2)
        nm = re.search(r"NAME\s*\(\s*([A-Z0-9.#@$]+)", body)
        if not nm:
            continue
        d = datasets.setdefault(nm.group(1), Dataset(dsn=nm.group(1)))
        if origin not in d.sources:
            d.sources.append(origin)
        if "listcat" in d.sources:
            continue
        kind = m.group(1)
        if kind == "CLUSTER":
            d.kind = "ESDS" if "NONINDEXED" in body else "RRDS" if "NUMBERED" in body else \
                "LDS" if "LINEAR" in body else "KSDS"
        else:
            d.kind = "PATH" if kind == "PATH" else "AIX"
            rel = re.search(r"(?:RELATE|PATHENTRY)\s*\(\s*([A-Z0-9.#@$]+)", body)
            d.base = rel.group(1) if rel else ""
        if k := re.search(r"KEYS\s*\(\s*(\d+)\s+(\d+)", body):
            d.key_length, d.key_offset = int(k.group(1)), int(k.group(2))
        if r := re.search(r"RECORDSIZE\s*\(\s*(\d+)\s+(\d+)", body):
            d.avg_lrecl, d.max_lrecl = int(r.group(1)), int(r.group(2))



def split_operands(s):
    out, cur, depth, quote = [], "", 0, False
    for ch in s:
        if ch == "'":
            quote = not quote
        elif not quote and ch == "(":
            depth += 1
        elif not quote and ch == ")":
            depth -= 1
        if ch == "," and depth == 0 and not quote:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    return out + ([cur] if cur else [])


def first_field(s):
    quote, depth = False, 0
    for i, ch in enumerate(s):
        if ch == "'":
            quote = not quote
        elif not quote and ch in "()":
            depth += 1 if ch == "(" else -1
        elif ch == " " and not quote and depth <= 0:
            return s[:i]
    return s


def jcl_statements(path):
    stmts, instream = [], False
    for raw in path.read_text(errors="replace").upper().splitlines():
        line = raw[:72].rstrip()
        if line.startswith("//*"):
            continue
        if line.startswith("/*") or (instream and line.startswith("//")):
            instream = False
            if line.startswith("/*"):
                continue
        if instream:
            stmts[-1]["instream"].append(line)
            continue
        if not line.startswith("//"):
            continue
        body = line[2:]
        name = "" if body.startswith(" ") else body.split(" ", 1)[0]
        rest = body[len(name):].strip()
        prev = stmts[-1] if stmts else None
        if not name and prev and prev["operands"].endswith(","):
            prev["operands"] += first_field(rest)
            continue
        if not rest:
            continue
        op, _, operands = rest.partition(" ")
        stmts.append({"name": name, "op": op, "operands": first_field(operands.strip()), "instream": []})
        if op == "DD" and re.match(r"(\*|DATA)(,|$)", stmts[-1]["operands"]):
            instream = True
    return stmts


def substitute(value, symbols):
    if value.startswith("&&"):
        return value
    return re.sub(r"&([A-Z0-9#@$]+)\.?", lambda m: symbols.get(m.group(1), m.group(0)), value)


def kv(operands):
    pos, kw = [], {}
    for part in split_operands(operands):
        k, eq, v = part.partition("=")
        if eq:
            kw[k] = v
        else:
            pos.append(part)
    return pos, kw


PROC_LABELS = {}


def parse_procs(paths):
    procs = {}
    for path in paths:
        name, steps, defaults = None, [], {}
        for st in jcl_statements(path):
            if st["op"] == "PROC":
                # JCLLIB resolves procs by member name, not by the PROC label.
                name, defaults = path.stem.upper(), kv(st["operands"])[1]
                PROC_LABELS[name] = st["name"]
            elif st["op"] == "EXEC" and name:
                steps.append({"stmt": st, "dds": []})
            elif st["op"] == "DD" and steps:
                steps[-1]["dds"].append(st)
            elif st["op"] == "PEND" and name:
                procs[name] = (steps, defaults)
                name, steps = None, []
        if name:
            procs[name] = (steps, defaults)
    return procs


def app_program_for(pgm, parm, dds_instream):
    if pgm == "DFSRRC00" and parm:
        parts = [p.strip("'() ") for p in parm.strip("'()").split(",")]
        if len(parts) > 1 and parts[0] in ("DLI", "BMP", "DBB"):
            return parts[1]
    if pgm in ("IKJEFT01", "IKJEFT1B"):
        m = re.search(r"\bRUN\s+PROGRAM\s*\(\s*([A-Z0-9#@$]+)", " ".join(dds_instream.get("SYSTSIN", [])))
        if m:
            return m.group(1)
    return pgm


def parse_jcl(path, procs, datasets):
    steps, job, symbols = [], path.stem.upper(), {}

    def build_step(stmt, dd_stmts, proc="", procstep="", overrides=None, syms=None):
        pos, kw = kv(stmt["operands"])
        pgm = kw.get("PGM", "")
        step = JclStep(job=job, file=str(path), step=stmt["name"], pgm=pgm, proc=proc, procstep=procstep)
        instream, last = defaultdict(list), None
        for dd in list(dd_stmts) + list((overrides or {}).get(procstep or stmt["name"], [])):
            ddname = dd["name"].split(".")[-1] if dd["name"] else last
            if not ddname:
                continue
            if dd["name"]:
                if dd in (overrides or {}).get(procstep, []) or ddname not in step.dds:
                    step.dds[ddname] = []
                last = ddname
            _, ddkw = kv(dd["operands"])
            dsn = ddkw.get("DSN") or ddkw.get("DSNAME")
            if dsn:
                step.dds[ddname].append(substitute(dsn, syms or symbols))
            if "DISP" in ddkw and dd["name"]:
                step.disp[ddname] = ddkw["DISP"]
            instream[ddname] += dd["instream"]
        step.dds = {k: v for k, v in step.dds.items() if v}
        step.app_program = app_program_for(pgm, kw.get("PARM", ""), instream)
        step.sysin_dsns = sorted(set(re.findall(r"\b(AWS\.M2\.CARDDEMO(?:\.[A-Z0-9#@$]+)+)", " ".join(sum(instream.values(), [])))))
        if pgm == "IDCAMS":
            parse_idcams_defines("\n".join(instream.get("SYSIN", [])), datasets, f"jcl:{job}.{stmt['name']}")
        return step

    stmts = jcl_statements(path)
    i = 0
    while i < len(stmts):
        st = stmts[i]
        if st["op"] == "JOB":
            job = st["name"] or job
        elif st["op"] == "SET":
            symbols.update(kv(st["operands"])[1])
        elif st["op"] == "EXEC":
            dds = []
            while i + 1 < len(stmts) and stmts[i + 1]["op"] == "DD":
                i += 1
                dds.append(stmts[i])
            pos, kw = kv(st["operands"])
            procname = kw.get("PROC") or (pos[0] if pos and "PGM" not in kw else "")
            if procname and procname in procs:
                proc_steps, defaults = procs[procname]
                syms = {**symbols, **defaults, **{k: v for k, v in kw.items() if k != "PROC"}}
                overrides = defaultdict(list)
                for dd in dds:
                    overrides[dd["name"].split(".")[0] if "." in dd["name"] else ""].append(dd)
                for ps in proc_steps:
                    s = build_step(ps["stmt"], ps["dds"], procname, ps["stmt"]["name"], overrides, syms)
                    s.step = st["name"]
                    steps.append(s)
            elif procname:
                steps.append(JclStep(job=job, file=str(path), step=st["name"], pgm="", proc=procname))
            else:
                steps.append(build_step(st, dds))
        i += 1
    return steps



def gdg_base(dsn):
    return re.sub(r"\((?:[+-]?\d+|[A-Z0-9#@$]+)\)$", "", dsn)


def build(app):
    lib = CopybookLibrary(app)
    datasets = parse_listcat(app / "catlg" / "LISTCAT.txt")

    modules = [("core", app)] + [(p.name, p) for p in sorted(app.glob("app-*"))]
    programs = {}
    for module, root in modules:
        for f in sorted((root / "cbl").glob("*")):
            if f.suffix.lower() in (".cbl", ".cob"):
                programs[f.stem.upper()] = analyze_cobol(f, module, lib)
    for f in sorted((app / "asm").glob("*.asm")):
        programs[f.stem.upper()] = Program(name=f.stem.upper(), source=str(f), module="core", type="assembler-utility")

    mapsets = {}
    for module, root in modules:
        for f in sorted((root / "bms").glob("*.bms")):
            ms, maps = parse_bms(f)
            mapsets[ms] = {"file": str(f), "module": module, "maps": maps}

    csd_files, csd_programs, transactions, csd_other = {}, {}, {}, []
    for module, root in modules:
        for f in sorted((root / "csd").glob("*")):
            for kind, rname, attrs in parse_csd(f):
                if kind == "FILE":
                    csd_files[rname] = attrs.get("DSNAME", "")
                elif kind == "PROGRAM":
                    csd_programs[rname] = module
                elif kind == "TRANSACTION":
                    transactions[rname] = {"program": attrs.get("PROGRAM", ""), "module": module}
                elif kind in ("TDQUEUE", "TSMODEL", "DB2ENTRY", "DB2TRAN"):
                    csd_other.append({"kind": kind, "name": rname, "module": module,
                                      **{k: v for k, v in attrs.items() if k in ("PLAN", "ENTRY", "TRANSID", "TYPE", "DDNAME", "DSNAME")}})
    for pname, module in csd_programs.items():
        if pname not in programs:
            programs[pname] = Program(name=pname, source="", module=module, type="online-cics",
                                      notes=["defined in CSD but no source in repo"])
    for tid, t in transactions.items():
        if t["program"] in programs:
            p = programs[t["program"]]
            if tid not in p.transactions:
                p.transactions.append(tid)
            p.transactions.sort()

    procs = parse_procs(sorted((app / "proc").glob("*")))
    steps = []
    for module, root in modules:
        for f in sorted((root / "jcl").glob("*")):
            steps += parse_jcl(f, procs, datasets)

    for p in programs.values():
        submitted = []
        for c in p.calls:
            if c.kind == "SUBMIT-JCL" and c.target in procs:
                submitted += [Call("SUBMIT-JCL", kv(ps["stmt"]["operands"])[1]["PGM"], f"proc {c.target}")
                              for ps in procs[c.target][0] if "PGM" in kv(ps["stmt"]["operands"])[1]]
        p.calls = [c for c in p.calls if c.kind != "SUBMIT-JCL"] + \
            [c for c in submitted if c.target in programs]

    for s in steps:
        if s.app_program in programs:
            programs[s.app_program].jcl_steps.append(f"{s.job}.{s.step}" + (f".{s.procstep}" if s.procstep else ""))

    callers = defaultdict(set)
    for p in programs.values():
        for c in p.calls:
            if c.kind != "START" and c.target in programs:
                callers[c.target].add(p.name)
    for p in programs.values():
        p.called_by = sorted(callers[p.name])
        if p.type == "batch" and not p.jcl_steps and p.called_by:
            p.type = "batch-subroutine" if not any(programs[c].type == "online-cics" for c in p.called_by) \
                else "subroutine-called-from-cics"

    def ds(dsn):
        return datasets.setdefault(dsn, Dataset(dsn=dsn, sources=["jcl-dd"]))

    for fname, dsn in csd_files.items():
        d = ds(dsn)
        if "csd" not in d.sources:
            d.sources.append("csd")
        d.cics_files.append(fname)
    for d in list(datasets.values()):
        if d.kind == "PATH" and d.base in datasets:
            datasets[d.base].paths.append(d.dsn)

    def hosts(name, seen=()):
        """The program plus every batch caller whose JCL step supplies its DDs."""
        out = {name}
        for c in programs[name].called_by:
            if c not in seen and programs[c].type != "online-cics":
                out |= hosts(c, seen + (name,))
        return out

    for p in programs.values():
        host_names = hosts(p.name)
        step_objs = [s for s in steps if s.app_program in host_names]
        for a in p.accesses:
            if a.via == "CICS" and a.kind == "vsam-file":
                a.dsns = [csd_files[a.name]] if a.name in csd_files else []
            elif a.via == "COBOL-IO":
                a.dsns = sorted({gdg_base(x) for s in step_objs for x in s.dds.get(a.name, [])})
            for dsn in a.dsns:
                d = ds(dsn)
                chain = [d]
                if d.kind == "PATH" and d.base in datasets and datasets[d.base].base:
                    chain += [datasets[d.base], ds(datasets[d.base].base)]
                for t in chain:
                    t.programs[p.name] = sorted(set(t.programs.get(p.name, [])) | set(a.modes))
                    for cb in a.record_copybooks:
                        if cb not in t.copybooks:
                            t.copybooks.append(cb)

    for s in steps:
        if s.app_program in programs:
            continue
        label = f"{s.job}.{s.step}" + (f".{s.procstep}" if s.procstep else "") + f"({s.app_program or s.pgm or s.proc})"
        for dsn in sorted({gdg_base(x) for v in s.dds.values() for x in v} | set(s.sysin_dsns)):
            if dsn in datasets and label not in datasets[dsn].utility_steps:
                datasets[dsn].utility_steps.append(label)

    for p in programs.values():
        rx = next((sid for sid, _, pat in SLICES if re.match(pat, p.name)), None)
        if rx:
            p.slice = rx
        elif p.module != "core":
            p.slice = OPTIONAL_SLICE[0]
        else:
            p.slice = UNASSIGNED[0]
            p.suggested_slice = SUGGESTED_HOME.get(p.name, ("", ""))[0]
            if p.name in SUGGESTED_HOME:
                p.notes.append(SUGGESTED_HOME[p.name][1])

    return {
        "programs": dict(sorted(programs.items())),
        "datasets": dict(sorted(datasets.items())),
        "transactions": dict(sorted(transactions.items())),
        "cics_files": dict(sorted(csd_files.items())),
        "csd_other": csd_other,
        "mapsets": dict(sorted(mapsets.items())),
        "jcl_steps": steps,
        "proc_labels": dict(sorted(PROC_LABELS.items())),
        "copybooks": {k: {"file": str(v), "used_by": sorted(lib.used.get(k, ()))} for k, v in sorted(lib.index.items())},
    }



def cross_slice(inv):
    progs = inv["programs"]
    home = {n: (p.slice if p.slice != "unassigned" else p.suggested_slice or "unassigned") for n, p in progs.items()}
    call_edges = sorted({(p.name, home[p.name], c.kind, c.target, home[c.target])
                         for p in progs.values() for c in p.calls
                         if c.target in progs and home[c.target] != home[p.name]})
    shared = []
    for d in inv["datasets"].values():
        if d.kind in ("PATH",):
            continue
        by_slice = defaultdict(list)
        for pn, modes in d.programs.items():
            by_slice[home[pn]].append(f"{pn}({','.join(modes)})")
        if len(by_slice) > 1:
            shared.append((d.dsn, dict(sorted(by_slice.items()))))
    return home, call_edges, shared


def anomalies(inv):
    progs, out = inv["programs"], []
    for n, p in progs.items():
        if not p.source:
            out.append(f"`{n}` is defined in the CSD but has no source file.")
        for c in p.calls:
            if c.resolution == "unresolved":
                out.append(f"`{n}` {c.kind} target `{c.target}` is computed at runtime (not a literal).")
            elif c.target not in progs and c.target not in SYSTEM_PROGRAMS and c.kind != "START":
                out.append(f"`{n}` {c.kind}s `{c.target}`, which has no source or CSD entry.")
        for a in p.accesses:
            if a.via == "CICS" and a.kind == "vsam-file" and a.name not in inv["cics_files"]:
                out.append(f"`{n}` uses CICS file `{a.name}`, which no CSD in the repo defines.")
            if a.via == "COBOL-IO" and p.jcl_steps and not a.dsns:
                out.append(f"`{n}` opens DD `{a.name}` but no JCL step that runs it allocates that DD.")
        if p.type in ("batch",) and not p.jcl_steps and not p.called_by:
            out.append(f"`{n}` is batch COBOL but no JCL step runs it and nothing calls it.")
        if p.type == "online-cics" and p.source and not p.transactions and not p.called_by:
            out.append(f"`{n}` is a CICS program with no transaction and no caller.")
        if p.missing_copybooks:
            out.append(f"`{n}` references copybooks not in the repo: {', '.join(p.missing_copybooks)}.")
    for member, label in inv["proc_labels"].items():
        if label and label != member:
            out.append(f"Cataloged proc member `{member}` declares itself `PROC {label}`; JCLLIB resolves by member name.")
    for n, p in progs.items():
        for a in p.accesses:
            if "read-update" in a.modes and a.via == "CICS" and not {"rewrite", "delete"} & set(a.modes):
                out.append(f"`{n}` reads CICS file `{a.name}` with UPDATE but never rewrites or deletes it.")
            if a.via == "COBOL-IO" and a.organization == "INDEXED" and "open-write" in a.modes:
                live = [d for d in a.dsns if inv["datasets"].get(d) and inv["datasets"][d].cics_files]
                if live:
                    out.append(f"`{n}` opens KSDS DD `{a.name}` OUTPUT (load mode) on `{live[0]}`, which is also CICS file "
                               f"{', '.join(inv['datasets'][live[0]].cics_files)}.")
    for d in inv["datasets"].values():
        if d.kind in ("AIX", "PATH") and not d.programs:
            out.append(f"{d.kind} `{d.dsn}` is defined and rebuilt by JCL but no program reads through it.")
    for cb, info in inv["copybooks"].items():
        if not info["used_by"]:
            out.append(f"Copybook `{cb}` is not included by any program.")
    for s in inv["jcl_steps"]:
        pgm = s.app_program or s.pgm
        if pgm and pgm not in progs and pgm not in UTILITY_PGMS and pgm not in SYSTEM_PROGRAMS and not s.proc:
            out.append(f"JCL step `{s.job}.{s.step}` runs `{pgm}`, which has no source in the repo.")
    for d in inv["datasets"].values():
        if d.kind in ("KSDS", "ESDS", "RRDS") and not d.programs:
            out.append(f"VSAM `{d.dsn}` is not read or written by any application program (utility/JCL only).")
        if len(set(d.copybooks)) > 1 and d.kind in ("KSDS", "ESDS", "RRDS", "AIX"):
            out.append(f"VSAM `{d.dsn}` is read through more than one record copybook: {', '.join(d.copybooks)}.")
    return sorted(set(out))



def to_json(inv, home, call_edges, shared, notes):
    def plain(x):
        if isinstance(x, dict):
            return {k: plain(v) for k, v in x.items()}
        if isinstance(x, list):
            return [plain(v) for v in x]
        if hasattr(x, "__dataclass_fields__"):
            return plain(asdict(x))
        return x
    return plain({
        "generator": "scripts/inventory/build_inventory.py",
        **inv,
        "slices": {sid: sorted(n for n, h in home.items() if h == sid)
                   for sid in sorted(set(home.values()))},
        "cross_slice_calls": [dict(zip(("from", "from_slice", "kind", "to", "to_slice"), e)) for e in call_edges],
        "shared_datasets": [{"dsn": d, "by_slice": s} for d, s in shared],
        "anomalies": notes,
    })


def mermaid_id(s):
    return re.sub(r"[^A-Za-z0-9_]", "_", s)


def short_dsn(dsn):
    return dsn.replace("AWS.M2.CARDDEMO.", "")


def to_markdown(inv, home, call_edges, shared, notes):
    progs, dsets = inv["programs"], inv["datasets"]
    slice_names = {sid: label for sid, label, _ in SLICES} | {OPTIONAL_SLICE[0]: OPTIONAL_SLICE[1],
                                                             UNASSIGNED[0]: UNASSIGNED[1],
                                                             "shared-utility": "Shared utility"}
    L = ["# CardDemo inventory and dependency map", "",
         "Generated by `scripts/inventory/build_inventory.py` from the sources under `app/`. "
         "Do not edit by hand; rerun the script. Machine-readable twin: `inventory.json`.", "",
         "Access modes: `read`, `read-update` (CICS READ UPDATE or IMS GH*), `browse` (STARTBR/READNEXT/READPREV), "
         "`write`, `rewrite`, `delete`, `insert`/`update` (SQL, DL/I), and `open-<mode>` for COBOL OPEN "
         "(`read`=INPUT, `write`=OUTPUT, `read-write`=I-O, `append`=EXTEND). "
         "Call resolution: `literal` in the statement, `value` from a VALUE clause, `move` from a MOVE into the "
         "variable, `table` from a menu table in a copybook, `unresolved` means computed at runtime. "
         "`SUBMIT-JCL` edges come from JCL text embedded in a program and written to a TD queue. "
         "MQ modes are per program (it both GETs and PUTs), not per queue.", ""]

    counts = defaultdict(int)
    for p in progs.values():
        counts[p.type] += 1
    L += ["## Totals", "", "| Item | Count |", "|---|---|"]
    L += [f"| Programs ({t}) | {c} |" for t, c in sorted(counts.items())]
    L += [f"| CICS transactions | {len(inv['transactions'])} |", f"| BMS mapsets | {len(inv['mapsets'])} |",
          f"| Copybooks | {len(inv['copybooks'])} |", f"| JCL steps (incl. utilities) | {len(inv['jcl_steps'])} |",
          f"| VSAM clusters/AIX/paths | {sum(1 for d in dsets.values() if d.kind in ('KSDS', 'ESDS', 'RRDS', 'AIX', 'PATH'))} |", ""]

    L += ["## Programs", ""]
    for sid in [s[0] for s in SLICES] + [OPTIONAL_SLICE[0], UNASSIGNED[0]]:
        members = [p for p in progs.values() if p.slice == sid]
        if not members:
            continue
        L += [f"### {slice_names[sid]}", ""]
        for p in members:
            L.append(f"#### {p.name}")
            L.append("")
            src = p.source.split("/app/", 1)[-1] if p.source else "(no source)"
            L.append(f"- Type: {p.type}. Module: {p.module}. Source: `app/{src}`" if p.source else
                     f"- Type: {p.type}. Module: {p.module}. Source: none")
            if p.transactions:
                L.append(f"- Transactions: {', '.join(p.transactions)}")
            if p.mapsets:
                L.append(f"- BMS mapsets: {', '.join(f'{m} ({len(inv['mapsets'].get(m, {}).get('maps', []))} maps)' for m in p.mapsets)}")
            if p.calls:
                L.append("- Calls: " + "; ".join(f"{c.kind} {c.target} ({c.resolution})" for c in p.calls))
            if p.called_by:
                L.append(f"- Called by: {', '.join(p.called_by)}")
            if p.copybooks:
                L.append(f"- Copybooks: {', '.join(p.copybooks)}")
            if p.accesses:
                L.append("- Data:")
                for a in p.accesses:
                    dsn = ", ".join(short_dsn(x) for x in a.dsns) or "no DSN bound"
                    rec = f"; record {', '.join(a.record_copybooks)}" if a.record_copybooks else ""
                    L.append(f"  - {a.kind} `{a.name}` via {a.via} [{', '.join(a.modes) or 'declared only'}] -> {dsn}{rec}")
            if p.jcl_steps:
                L.append(f"- JCL steps: {', '.join(p.jcl_steps)}")
            if p.suggested_slice:
                L.append(f"- Suggested home: {slice_names.get(p.suggested_slice, p.suggested_slice)}")
            for n in p.notes:
                L.append(f"- Note: {n}")
            L.append("")

    L += ["## VSAM datasets", "",
          "Key is `length@offset` bytes. Record lengths come from LISTCAT (avg/max) or the IDCAMS DEFINE in JCL. "
          "Programs that reach a base cluster through an AIX path are listed on the path, the AIX and the base. "
          "JCL utility steps are non-application steps (IDCAMS, SORT, IEBGENER, ...) that name the dataset in a DD or in "
          "instream control cards; the batch slice must reproduce these too.", "",
          "| DSN | Kind | Key | LRECL | Base / paths | CICS files | Record copybooks | Programs | JCL utility steps |",
          "|---|---|---|---|---|---|---|---|---|"]
    for d in dsets.values():
        if d.kind not in ("KSDS", "ESDS", "RRDS", "AIX", "PATH", "LDS"):
            continue
        key = f"{d.key_length}@{d.key_offset}" if d.key_length is not None else ""
        lrecl = f"{d.avg_lrecl}/{d.max_lrecl}" if d.max_lrecl else ""
        rel = d.base and f"base {short_dsn(d.base)}" or ", ".join(short_dsn(x) for x in d.paths)
        prg = "<br>".join(f"{n} ({', '.join(m) or '-'})" for n, m in sorted(d.programs.items())) or "none"
        src = "" if "listcat" in d.sources else " (not in LISTCAT)"
        L.append(f"| {short_dsn(d.dsn)}{src} | {d.kind} | {key} | {lrecl} | {rel} | {', '.join(d.cics_files)} | "
                 f"{', '.join(d.copybooks)} | {prg} | {'<br>'.join(d.utility_steps)} |")
    L += ["", "Non-VSAM datasets (sequential files and GDGs) touched by programs:", "",
          "| DSN | Kind | Programs |", "|---|---|---|"]
    for d in dsets.values():
        if d.kind in ("NONVSAM", "GDG", "UNCATALOGED") and d.programs:
            L.append(f"| {short_dsn(d.dsn)} | {d.kind} | " +
                     "<br>".join(f"{n} ({', '.join(m)})" for n, m in sorted(d.programs.items())) + " |")

    L += ["", "## Migration slices", "",
          "| Slice | Programs | Transactions |", "|---|---|---|"]
    for sid in [s[0] for s in SLICES] + [OPTIONAL_SLICE[0], "shared-utility", UNASSIGNED[0]]:
        members = sorted(n for n, h in home.items() if h == sid)
        if members:
            tx = sorted({t for n in members for t in progs[n].transactions})
            L.append(f"| {slice_names[sid]} | {', '.join(members)} | {', '.join(tx)} |")
    L += ["", "Programs outside the named slices, placed in the table above by suggested home:", ""]
    L += [f"- `{p.name}` goes to {slice_names.get(p.suggested_slice, p.suggested_slice) or 'nowhere yet'}. {SUGGESTED_HOME.get(p.name, ('', ''))[1]}"
          for p in progs.values() if p.slice == "unassigned"]
    L += ["", "### Cross-slice calls", "", "| From | Kind | To |", "|---|---|---|"]
    L += [f"| {f} ({slice_names.get(fs, fs)}) | {k} | {t} ({slice_names.get(ts, ts)}) |" for f, fs, k, t, ts in call_edges]
    L += ["", "### Datasets shared across slices", "",
          "These are the coupling points: a slice that writes one of these must keep the record layout "
          "byte-compatible until every other slice touching it has moved.", "",
          "| DSN | Slice: programs (modes) |", "|---|---|"]
    for dsn, by in shared:
        L.append(f"| {short_dsn(dsn)} | " + "<br>".join(f"{slice_names.get(s, s)}: {', '.join(v)}" for s, v in by.items()) + " |")

    L += ["", "## Anomalies the script detected", ""] + [f"- {n}" for n in notes]

    L += ["", "## Call graph", "", "Online navigation (XCTL/LINK) and static CALLs. Dotted edges are runtime-resolved "
          "(menu tables or MOVE into the target).", "", "```mermaid", "flowchart LR"]
    for sid in sorted(set(home.values())):
        members = sorted(n for n, h in home.items() if h == sid)
        L.append(f"  subgraph {mermaid_id(sid)}[\"{slice_names.get(sid, sid)}\"]")
        L += [f"    {mermaid_id(n)}[\"{n}{'<br>' + '/'.join(progs[n].transactions) if progs[n].transactions else ''}\"]" for n in members]
        L.append("  end")
    for p in progs.values():
        for c in p.calls:
            if c.target in progs:
                arrow = "-->" if c.resolution in ("literal", "value") else "-.->"
                L.append(f"  {mermaid_id(p.name)} {arrow}|{c.kind}| {mermaid_id(c.target)}")
    L += ["```", "", "## Data graph", "",
          "Programs to VSAM clusters, Db2 tables, IMS segments, MQ queues and TD queues. Arrow from a store into a "
          "program means read only; from a program into a store means write only; a double arrow means both. "
          "AIX paths are folded into their base cluster. Sequential files and GDGs are omitted for readability; see the JSON.",
          "", "```mermaid", "flowchart LR"]
    writes = {"write", "rewrite", "delete", "insert", "update", "read-update", "open-write", "open-read-write", "open-append"}
    nodes, edges = {}, set()
    for p in progs.values():
        for a in p.accesses:
            if a.kind == "vsam-file":
                targets = []
                for dsn in a.dsns:
                    d = dsets.get(dsn)
                    if d and d.kind == "PATH" and d.base in dsets:
                        dsn = dsets[d.base].base or dsn
                    if dsets.get(dsn) and dsets[dsn].kind in ("KSDS", "ESDS", "RRDS", "AIX"):
                        targets.append(("ds_" + mermaid_id(dsn), f"[({short_dsn(dsn)})]"))
                if not targets and a.via == "CICS":
                    targets.append(("file_" + mermaid_id(a.name), f"[(\"CICS {a.name}\")]"))
            elif a.kind in ("db2-table", "ims-segment", "ims-pcb", "mq-queue", "tdq", "tsq"):
                label = {"db2-table": "Db2", "ims-segment": "IMS", "ims-pcb": "IMS PCB", "mq-queue": "MQ",
                         "tdq": "TDQ", "tsq": "TSQ"}[a.kind]
                targets = [(f"{mermaid_id(a.kind)}_{mermaid_id(a.name)}", f"[/\"{label} {a.name}\"/]")]
            else:
                continue
            reads = {"read", "browse", "read-update", "open-read", "open-read-write"}
            for nid, shape in targets:
                nodes[nid] = shape
                if set(a.modes) & writes and set(a.modes) & reads:
                    edges.add(f"  {mermaid_id(p.name)} <--> {nid}")
                elif set(a.modes) & writes:
                    edges.add(f"  {mermaid_id(p.name)} --> {nid}")
                else:
                    edges.add(f"  {nid} --> {mermaid_id(p.name)}")
    L += [f"  {nid}{shape}" for nid, shape in sorted(nodes.items())] + sorted(edges) + ["```", ""]
    return "\n".join(L)


def main():
    root = Path(__file__).resolve().parents[2]
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--app", type=Path, default=root / "app")
    ap.add_argument("--json", type=Path, default=Path("inventory.json"))
    ap.add_argument("--md", type=Path, default=Path("inventory-and-dependency-map.md"))
    args = ap.parse_args()

    inv = build(args.app.resolve())
    home, call_edges, shared = cross_slice(inv)
    notes = anomalies(inv)
    rel = str(args.app.resolve().parent) + "/"
    args.json.write_text(json.dumps(to_json(inv, home, call_edges, shared, notes), indent=2).replace(rel, "") + "\n")
    args.md.write_text(to_markdown(inv, home, call_edges, shared, notes).replace(rel, ""))
    print(f"{len(inv['programs'])} programs, {len(inv['datasets'])} datasets, {len(inv['jcl_steps'])} JCL steps, "
          f"{len(notes)} anomalies -> {args.json}, {args.md}")


if __name__ == "__main__":
    main()
