#!/usr/bin/env python3
"""Generate docs/migration/dependency-map.json and docs/migration/inventory.md
from the CardDemo mainframe sources under app/.

Usage: python3 docs/migration/tools/build_inventory.py   (stdlib only, idempotent)
"""
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "app"
OUT = ROOT / "docs" / "migration"

MODULES = {
    "app": "core",
    "app/app-transaction-type-db2": "db2-transaction-type",
    "app/app-authorization-ims-db2-mq": "ims-db2-mq-authorization",
    "app/app-vsam-mq": "vsam-mq",
}
SOURCE_EXT = {".cbl", ".cpy", ".bms", ".jcl", ".prc", ".csd", ".asm", ".mac", ".ddl", ".dcl"}
IBM_RUNTIME = {"CEE3ABD", "CEEDAYS", "CBLTDLI", "MQOPEN", "MQGET", "MQPUT", "MQPUT1", "MQCLOSE",
               "MQCONN", "MQDISC", "MQINQ", "MQCONNX", "DFHCSDUP", "IDCAMS", "IEFBR14", "IEBGENER",
               "SORT", "SDSF", "FTP", "IKJEFT1B", "DSNTIAD", "DSNTEP2", "DSNTIAUL"}


def module_of(path: Path) -> str:
    rel = path.relative_to(ROOT).as_posix()
    for prefix in sorted(MODULES, key=len, reverse=True):
        if rel.startswith(prefix + "/"):
            return MODULES[prefix]
    return "core"


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def cobol_lines(path: Path):
    """Fixed-format COBOL: drop sequence area, comments, and columns 73+."""
    out = []
    for raw in path.read_text(errors="replace").splitlines():
        raw = raw.expandtabs(8)
        if len(raw) < 7 or raw[6] in "*/":
            continue
        out.append(raw[6:72].rstrip())
    return out


def logical(path: Path) -> str:
    return "\n".join(cobol_lines(path))


# ---------------------------------------------------------------- CSD

def parse_csd(path: Path, module: str):
    text = path.read_text(errors="replace")
    out = {"files": [], "transactions": [], "programs": [], "mapsets": [], "tdqueues": []}
    for m in re.finditer(r"DEFINE\s+(FILE|TRANSACTION|PROGRAM|MAPSET|TDQUEUE)\((\w+)\)(.*?)(?=\n\s*DEFINE\s|\Z)",
                         text, re.S):
        kind, name, body = m.groups()
        body = re.sub(r"\s+", " ", body)

        def opt(k):
            mm = re.search(rf"\b{k}\(([^)]*)\)", body)
            return mm.group(1) if mm else None
        base = {"name": name, "module": module, "source": rel(path)}
        if kind == "FILE":
            out["files"].append({**base, "dsname": opt("DSNAME"), "recovery": opt("RECOVERY"),
                                 "ops": {k: opt(k) for k in ("ADD", "BROWSE", "DELETE", "READ", "UPDATE")}})
        elif kind == "TRANSACTION":
            out["transactions"].append({**base, "program": opt("PROGRAM"), "status": opt("STATUS")})
        elif kind == "PROGRAM":
            out["programs"].append({**base, "transid": opt("TRANSID")})
        elif kind == "MAPSET":
            out["mapsets"].append(base)
        else:
            out["tdqueues"].append(base)
    return out


# ---------------------------------------------------------------- LISTCAT

def parse_listcat(path: Path):
    entries, cur = {}, None
    head = re.compile(r"^0?(NONVSAM|CLUSTER|DATA|INDEX|AIX|PATH|GDG)\s+-+\s+(\S+)")
    for line in path.read_text(errors="replace").splitlines():
        m = head.match(line.lstrip("1") if line.startswith("1") else line)
        if line[:1] == "0" and m:
            cur = {"type": m.group(1), "name": m.group(2), "assoc": defaultdict(list)}
            entries[cur["name"]] = cur
            continue
        if cur is None:
            continue
        for key in ("KEYLEN", "AVGLRECL", "MAXLRECL", "RKP", "AXRKP", "REC-TOTAL"):
            mm = re.search(rf"\b{re.escape(key)}-+(\d+)", line)
            if mm and key not in cur:
                cur[key] = int(mm.group(1))
        for kind in ("DATA", "INDEX", "CLUSTER", "AIX", "PATH"):
            mm = re.search(rf"^\s*0?\s*{kind}-+(\S+)", line)
            if mm and mm.group(1) != cur["name"]:
                cur["assoc"][kind].append(mm.group(1))
        if "NONUNIQKEY" in line:
            cur["nonunique"] = True
        elif re.search(r"\bUNIQKEY\b", line):
            cur["nonunique"] = False
    ds = {}
    for name, e in entries.items():
        if e["type"] in ("DATA", "INDEX"):
            continue
        rec = {"name": name, "type": e["type"]}
        if e["type"] == "CLUSTER":
            data = entries.get(e["assoc"]["DATA"][0]) if e["assoc"]["DATA"] else None
            if data:
                rec.update(organization="KSDS", key_length=data.get("KEYLEN"), key_offset=data.get("RKP"),
                           max_lrecl=data.get("MAXLRECL"), records=data.get("REC-TOTAL"))
        elif e["type"] == "AIX":
            data = entries.get(e["assoc"]["DATA"][0]) if e["assoc"]["DATA"] else None
            rec.update(base_cluster=(e["assoc"]["CLUSTER"] or [None])[0], path=(e["assoc"]["PATH"] or [None])[0])
            if data:
                rec.update(key_length=data.get("KEYLEN"), key_offset=data.get("AXRKP"),
                           nonunique=data.get("nonunique"), records=data.get("REC-TOTAL"))
        elif e["type"] == "PATH":
            rec["aix"] = (e["assoc"]["AIX"] or [None])[0]
        ds[name] = rec
    return ds


# ---------------------------------------------------------------- COBOL

COPY_RE = re.compile(r"^\s+COPY\s+([A-Z0-9-]+)", re.M)
VALUE_RE = re.compile(r"^\s*(?:\d+)\s+([A-Z0-9-]+)\s+(?:PIC(?:TURE)?\s+\S+\s*)?(?:USAGE\s+\S+\s+)?VALUE\s+(?:IS\s+)?'([^']*)'",
                      re.M)
SQL_TABLE_RE = re.compile(r"\b(?:FROM|JOIN|INTO|UPDATE)\s+([A-Z][A-Z0-9_.]+)", re.I)


def value_table(text: str):
    """Data-name -> literal for single-line and two-line VALUE clauses."""
    flat = re.sub(r"\n\s+(?=VALUE)", " ", text)
    return {m.group(1): m.group(2) for m in VALUE_RE.finditer(flat)}


def cics_statements(text: str):
    for m in re.finditer(r"EXEC\s+CICS\s+(.*?)\s*END-EXEC", text, re.S):
        body = re.sub(r"\s+", " ", m.group(1)).strip()
        verb = body.split(" ", 1)[0]
        if verb in ("SEND", "RECEIVE", "READ", "WRITE", "REWRITE", "DELETE", "START", "RETURN",
                    "READQ", "WRITEQ", "DELETEQ", "SYNCPOINT", "ASSIGN", "ABEND", "HANDLE", "XCTL",
                    "LINK", "STARTBR", "READNEXT", "READPREV", "ENDBR", "RESETBR", "UNLOCK", "INQUIRE",
                    "SET", "ASKTIME", "FORMATTIME", "GETMAIN", "FREEMAIN", "CANCEL", "RETRIEVE", "SEND-TEXT"):
            yield verb, body, m.start()


def cics_opt(body: str, name: str):
    m = re.search(rf"\b{name}\s*\(\s*([^)]*(?:\([^)]*\))?[^)]*)\)", body)
    return m.group(1).strip() if m else None


def unquote(v):
    return v[1:-1].strip() if v and v[0] == "'" and v[-1] == "'" else None


def resolve_name(expr, values, text):
    """Return (literals, how). Resolves quoted literals, VALUE'd data names, and MOVE targets."""
    if expr is None:
        return [], "none"
    lit = unquote(expr)
    if lit is not None:
        return [lit.strip()], "literal"
    base = expr.split("(")[0].strip()
    found = []
    if base in values:
        found.append(values[base].strip())
    for m in re.finditer(r"MOVE\s+('[^']*'|[A-Z0-9-]+)\s+TO\s+" + re.escape(base) + r"\b", text):
        src = m.group(1)
        if src.startswith("'"):
            found.append(src.strip("'").strip())
        elif src in values:
            found.append(values[src].strip())
    return sorted(set(found)), ("resolved" if found else "unresolved")


def parse_fd_map(text: str):
    """record-name -> file-name for batch WRITE statements."""
    fmap, cur = {}, None
    in_fs = False
    for line in text.splitlines():
        if re.match(r"\s*FILE SECTION", line):
            in_fs = True
        elif re.match(r"\s*(WORKING-STORAGE|LINKAGE|LOCAL-STORAGE) SECTION", line):
            in_fs = False
        if not in_fs:
            continue
        m = re.match(r"\s*FD\s+([A-Z0-9-]+)", line)
        if m:
            cur = m.group(1)
            continue
        m = re.match(r"\s*01\s+([A-Z0-9-]+)", line)
        if m and cur:
            fmap[m.group(1)] = cur
    return fmap


def analyse_program(path: Path, known_programs, csd_files, copybook_values):
    text = logical(path)
    name_m = re.search(r"PROGRAM-ID\.\s*([A-Z0-9-]+)", text)
    name = (name_m.group(1) if name_m else path.stem).upper()
    values = dict(copybook_values)
    values.update(value_table(text))
    p = {"name": name, "module": module_of(path), "source": rel(path), "copybooks": sorted(set(COPY_RE.findall(text))),
         "edges": [], "files": [], "mapsets": set(), "transids": [], "sql_tables": [], "uses_mq": False,
         "uses_ims": False, "cics": False, "loc": len(cobol_lines(path))}
    p["cics"] = bool(re.search(r"EXEC\s+CICS", text))
    if re.search(r"EXEC\s+SQL", text):
        tables = set()
        for sm in re.finditer(r"EXEC\s+SQL\s+(.*?)\s*END-EXEC", text, re.S):
            body = re.sub(r"\s+", " ", sm.group(1))
            if body.upper().startswith(("INCLUDE", "DECLARE", "WHENEVER")) and "TABLE" not in body.upper():
                continue
            tables.update(t.upper() for t in SQL_TABLE_RE.findall(body) if t.upper() not in ("SQLCA",))
        p["sql_tables"] = sorted(tables)
    p["uses_mq"] = bool(re.search(r"CALL\s+'MQ(OPEN|GET|PUT|PUT1|CLOSE|CONN|CONNX|DISC|INQ)'", text))
    p["uses_ims"] = bool(re.search(r"CALL\s+'CBLTDLI'|EXEC\s+DLI", text))

    def add_edge(kind, target, how, via=None, line=None):
        p["edges"].append({"kind": kind, "target": target, "how": how, **({"via": via} if via else {})})

    for verb, body, _pos in cics_statements(text):
        if verb in ("XCTL", "LINK"):
            expr = cics_opt(body, "PROGRAM")
            lits, how = resolve_name(expr, values, text)
            if how == "literal":
                add_edge(verb.lower(), lits[0], "static")
            elif lits:
                for l in lits:
                    if l in known_programs:
                        add_edge(verb.lower(), l, "resolved", via=expr)
                if not any(e.get("via") == expr for e in p["edges"]):
                    add_edge(verb.lower(), None, "dynamic", via=expr)
            else:
                add_edge(verb.lower(), None, "dynamic", via=expr)
        elif verb in ("SEND", "RECEIVE"):
            ms = cics_opt(body, "MAPSET")
            if ms:
                for l in resolve_name(ms, values, text)[0]:
                    p["mapsets"].add(l)
        elif verb == "RETURN":
            tid = cics_opt(body, "TRANSID")
            if tid:
                for l in resolve_name(tid, values, text)[0]:
                    p["transids"].append(l)
        elif verb in ("READ", "WRITE", "REWRITE", "DELETE", "STARTBR", "READNEXT", "READPREV", "ENDBR",
                      "RESETBR", "UNLOCK"):
            expr = cics_opt(body, "FILE") or cics_opt(body, "DATASET")
            if expr is None and verb in ("READQ", "WRITEQ"):
                continue
            if expr:
                for l in resolve_name(expr, values, text)[0] or [expr]:
                    op = {"READ": "read", "STARTBR": "browse", "READNEXT": "browse", "READPREV": "browse",
                          "ENDBR": "browse", "RESETBR": "browse", "WRITE": "insert", "REWRITE": "update",
                          "DELETE": "delete", "UNLOCK": "read"}[verb]
                    if verb == "READ" and re.search(r"\bUPDATE\b", body):
                        op = "read-for-update"
                    p["files"].append({"file": l.strip(), "op": op, "channel": "cics"})
        elif verb in ("WRITEQ", "READQ", "DELETEQ"):
            q = cics_opt(body, "QUEUE")
            kind = "td" if re.search(r"\bTD\b", body) else "ts"
            for l in resolve_name(q, values, text)[0] or ([q] if q else []):
                p["files"].append({"file": f"{kind.upper()}:{l.strip()}", "op": verb.lower(), "channel": "cics-queue"})

    for m in re.finditer(r"\bCALL\s+('[A-Z0-9-]+'|\"[A-Z0-9-]+\"|[A-Z0-9-]+(?=\s+USING))", text):
        tgt = m.group(1).strip("'\"")
        if m.group(1)[0] in "'\"":
            add_edge("call", tgt, "static")
        else:
            lits, how = resolve_name(m.group(1), values, text)
            for l in lits:
                add_edge("call", l, "resolved", via=m.group(1))
            if not lits:
                add_edge("call", None, "dynamic", via=m.group(1))

    if re.search(r"^\s+SELECT\s", text, re.M):
        sel = {}
        for m in re.finditer(r"SELECT\s+(?:OPTIONAL\s+)?([A-Z0-9-]+)\s+ASSIGN\s+TO\s+([A-Z0-9-]+)(.*?)\.\s*$", text,
                             re.S | re.M):
            org = re.search(r"ORGANIZATION\s+IS\s+(\w+)", m.group(3))
            sel[m.group(1)] = {"dd": m.group(2), "organization": org.group(1) if org else "SEQUENTIAL"}
        fmap = parse_fd_map(text)
        ops = defaultdict(set)
        for m in re.finditer(r"OPEN\s+(INPUT|OUTPUT|I-O|EXTEND)\s+([A-Z0-9-]+)", text):
            ops[m.group(2)].add({"INPUT": "read", "OUTPUT": "insert", "I-O": "read-update", "EXTEND": "append"}[m.group(1)])
        for m in re.finditer(r"\b(REWRITE|DELETE)\s+([A-Z0-9-]+)", text):
            f = fmap.get(m.group(2), m.group(2))
            if f in sel:
                ops[f].add(m.group(1).lower() if m.group(1) == "DELETE" else "update")
        for m in re.finditer(r"\bWRITE\s+([A-Z0-9-]+)", text):
            f = fmap.get(m.group(1))
            if f in sel:
                ops[f].add("write")
        for f, info in sel.items():
            p["files"].append({"file": f, "dd": info["dd"], "organization": info["organization"],
                               "op": "/".join(sorted(ops[f])) or "open", "channel": "batch"})
    p["mapsets"] = sorted(p["mapsets"])
    return p


# ---------------------------------------------------------------- JCL

def jcl_logical(path: Path):
    lines, buf = [], ""
    for raw in path.read_text(errors="replace").splitlines():
        raw = raw[:72]
        if raw.startswith("//*") or not raw.strip():
            continue
        if raw.startswith("//") and re.match(r"//\s", raw) and buf.rstrip().endswith(","):
            buf += " " + raw[2:].strip()
        elif raw.startswith("//"):
            if buf:
                lines.append(buf)
            buf = raw.rstrip()
        elif buf and raw.strip():
            buf += " " + raw.strip()
        else:
            lines.append(raw.rstrip())
    if buf:
        lines.append(buf)
    return lines


def parse_jcl(path: Path, listcat_names):
    is_proc = path.suffix.lower() == ".prc"
    name = path.stem.upper()
    job = {"name": name, "kind": "proc" if is_proc else "job", "module": module_of(path), "source": rel(path),
           "steps": [], "datasets": []}
    step = None
    sysin_cmds = []
    raw_lines = path.read_text(errors="replace").splitlines()
    in_sysin = False
    for raw in raw_lines:
        if raw.startswith("//") and not raw.startswith("//*"):
            in_sysin = False
        if in_sysin and not raw.startswith("/*"):
            sysin_cmds.append(raw[:72])
            continue
        if re.match(r"//\S*\s+DD\s+\*", raw):
            in_sysin = True
    for line in jcl_logical(path):
        m = re.match(r"//([A-Z0-9#@$]*)\s+EXEC\s+(.*)", line)
        if m:
            args = m.group(2)
            pm = re.match(r"(?:PGM=([A-Z0-9#@$]+)|PROC=([A-Z0-9#@$]+)|([A-Z0-9#@$]+))", args)
            step = {"name": m.group(1), "program": pm.group(1), "proc": pm.group(2) or pm.group(3),
                    "parm": (re.search(r"PARM='([^']*)'", args) or [None, None])[1], "dds": []}
            job["steps"].append(step)
            continue
        m = re.match(r"//([A-Z0-9#@$.]*)\s+DD\s+(.*)", line)
        if m and step is not None:
            args = m.group(2)
            dsn = re.search(r"DSN=([A-Z0-9#@$.&()+\-]+)", args)
            disp = re.search(r"DISP=(\([^)]*\)|\w+)", args)
            dd = {"dd": m.group(1), "dsn": dsn.group(1) if dsn else None, "disp": disp.group(1) if disp else None,
                  "sysout": "SYSOUT" in args}
            step["dds"].append(dd)
            if dd["dsn"]:
                job["datasets"].append(dd["dsn"])
    cmds = " ".join(c.strip() for c in sysin_cmds)
    cmds = re.sub(r"\s+-\s+", " ", cmds)
    job["idcams"] = {
        "define_cluster": re.findall(r"DEFINE\s+CLUSTER\s*\(\s*NAME\(([^)\s]+)\)", cmds),
        "repro": [{"in": a, "out": b} for a, b in
                  re.findall(r"REPRO\s+(?:INFILE|IFILE)\(([A-Z0-9]+)\)\s+(?:OUTFILE|OFILE)\(([A-Z0-9]+)\)", cmds)],
        "bldindex": re.findall(r"BLDINDEX\s+INDATASET\(([^)\s]+)\)\s+OUTDATASET\(([^)\s]+)\)", cmds),
        "delete": re.findall(r"DELETE\s+([A-Z0-9.]+)", cmds),
    }
    aix = []
    for m in re.finditer(r"DEFINE\s+ALTERNATEINDEX\s*\(\s*NAME\(([^)\s]+)\)(.*?)(?=DEFINE|BLDINDEX|$)", cmds):
        body = m.group(2)
        rel_ = re.search(r"RELATE\(([^)\s]+)\)", body)
        keys = re.search(r"KEYS\((\d+)[ ,]+(\d+)\)", body)
        aix.append({"name": m.group(1), "relate": rel_.group(1) if rel_ else None,
                    "key_length": int(keys.group(1)) if keys else None,
                    "key_offset": int(keys.group(2)) if keys else None,
                    "nonunique": "NONUNIQUEKEY" in body})
    job["idcams"]["define_aix"] = aix
    cm = re.findall(r"CEMT SET FIL\((\w+)\s*\)\s*(CLO|OPE)", " ".join(raw_lines))
    job["cics_file_toggles"] = [{"file": a, "action": "close" if b == "CLO" else "open"} for a, b in sorted(set(cm))]
    job["datasets"] = sorted(set(job["datasets"]))
    return job


def norm_dsn(dsn: str):
    return re.sub(r"\(.*\)$", "", dsn)


# ---------------------------------------------------------------- scheduler

def parse_scheduler():
    jobs = []
    cm = APP / "scheduler" / "CardDemo.controlm"
    if cm.exists():
        text = cm.read_text(errors="replace")
        for fm in re.finditer(r'<FOLDER FOLDER_NAME="([^"]+)"(.*?)</FOLDER>', text, re.S):
            for jm in re.finditer(r'<JOB [^>]*?JOBNAME="([^"]+)"[^>]*?>(.*?)</JOB>', fm.group(2), re.S):
                body = jm.group(2)
                jobs.append({"scheduler": "control-m", "folder": fm.group(1), "job": jm.group(1),
                             "in": re.findall(r'<INCOND NAME="([^"]+)"', body),
                             "out": re.findall(r'<OUTCOND NAME="([^"]+)"[^>]*SIGN="\+"', body)})
    ca7 = APP / "scheduler" / "CardDemo.ca7"
    if ca7.exists():
        for m in re.finditer(r"^ (\w{3,8})\s+\d+\s+(\w+)\s+CARDDEMO", ca7.read_text(errors="replace"), re.M):
            jobs.append({"scheduler": "ca7", "job": m.group(1), "jcl_member": m.group(2)})
    return jobs


# ---------------------------------------------------------------- main

def main():
    csd = {"files": [], "transactions": [], "programs": [], "mapsets": [], "tdqueues": []}
    for p in sorted(APP.rglob("*.csd")) + sorted(APP.rglob("*.CSD")):
        parsed = parse_csd(p, module_of(p))
        for k in csd:
            csd[k].extend(parsed[k])
    listcat = parse_listcat(APP / "catlg" / "LISTCAT.txt")

    cbl_paths = sorted(p for p in APP.rglob("*") if p.suffix.lower() == ".cbl")
    cpy_paths = sorted(p for p in APP.rglob("*") if p.suffix.lower() == ".cpy" and "cpy-bms" not in p.as_posix())
    bms_cpy = sorted(p for p in APP.rglob("*") if p.suffix.lower() == ".cpy" and "cpy-bms" in p.as_posix())
    bms_paths = sorted(APP.rglob("*.bms"))
    jcl_paths = sorted(p for p in APP.rglob("*") if p.suffix.lower() in (".jcl",) and "scheduler" not in p.as_posix())
    prc_paths = sorted(p for p in APP.rglob("*") if p.suffix.lower() == ".prc")

    known = {p.stem.upper() for p in cbl_paths} | {r["name"] for r in csd["programs"]} | {"COBDATFT", "MVSWAIT"}

    cpy_values = {}
    for p in cpy_paths:
        v = value_table(logical(p))
        cpy_values[p.stem.upper()] = v
    all_cpy_values = {}
    for v in cpy_values.values():
        all_cpy_values.update(v)

    nested = {}
    for p in cpy_paths:
        nested[p.stem.upper()] = set(COPY_RE.findall(logical(p))) | set(
            re.findall(r"EXEC\s+SQL\s+INCLUDE\s+([A-Z0-9]+)", logical(p)))

    def closure(names):
        seen, todo = set(), list(names)
        while todo:
            n = todo.pop()
            if n in seen:
                continue
            seen.add(n)
            todo.extend(nested.get(n, ()))
        return seen

    programs = []
    for p in cbl_paths:
        text = logical(p)
        local_cpy = {}
        for c in COPY_RE.findall(text):
            local_cpy.update(cpy_values.get(c.upper(), {}))
        prog = analyse_program(p, known, csd["files"], local_cpy)
        prog["copybooks"] = sorted(closure(set(prog["copybooks"]) | set(
            re.findall(r"EXEC\s+SQL\s+INCLUDE\s+([A-Z0-9]+)", text))))
        # Menu/option tables: literals in COPY'd table copybooks that name a known program
        for e in list(prog["edges"]):
            if e["kind"] in ("xctl", "link") and e["how"] == "dynamic" and e.get("via") and "(" in e["via"]:
                base = e["via"].split("(")[0].strip()
                targets = set()
                for c in prog["copybooks"]:
                    ctext = logical(next(q for q in cpy_paths if q.stem.upper() == c.upper())) if any(
                        q.stem.upper() == c.upper() for q in cpy_paths) else ""
                    if base in ctext or re.search(r"REDEFINES", ctext):
                        for lit in re.findall(r"'([A-Z0-9]{7,8})'", ctext):
                            if lit in known:
                                targets.add(lit)
                if targets:
                    prog["edges"].remove(e)
                    for t in sorted(targets):
                        prog["edges"].append({"kind": e["kind"], "target": t, "how": "table", "via": e["via"]})
        programs.append(prog)
    for name in ("COBDATFT", "MVSWAIT"):
        src = next(APP.rglob(f"{name}.asm"))
        programs.append({"name": name, "module": "core", "source": rel(src), "language": "assembler", "copybooks": [],
                         "edges": [], "files": [], "mapsets": [], "transids": [], "sql_tables": [], "uses_mq": False,
                         "uses_ims": False, "cics": False, "loc": len(src.read_text().splitlines())})
    for pr in programs:
        pr.setdefault("language", "cobol")

    jcls = [parse_jcl(p, listcat) for p in jcl_paths + prc_paths]
    proc_names = {j["name"] for j in jcls if j["kind"] == "proc"}
    sched = parse_scheduler()

    cics_file_map = {f["name"]: f["dsname"] for f in csd["files"]}

    # Batch DD -> dataset from JCL steps running that program
    batch_dd = defaultdict(lambda: defaultdict(set))
    for j in jcls:
        for s in j["steps"]:
            if s["program"]:
                for dd in s["dds"]:
                    if dd["dsn"]:
                        batch_dd[s["program"]][dd["dd"]].add(norm_dsn(dd["dsn"]))

    nodes, edges = {}, []

    def node(kind, name, **attrs):
        key = f"{kind}:{name}"
        nodes.setdefault(key, {"id": key, "kind": kind, "name": name})
        nodes[key].update({k: v for k, v in attrs.items() if v is not None})
        return key

    def edge(src, dst, kind, **attrs):
        edges.append({"from": src, "to": dst, "kind": kind, **attrs})

    for t in csd["transactions"]:
        node("transaction", t["name"], program=t["program"], module=t["module"], source=t["source"])
    for pr in programs:
        node("program", pr["name"], module=pr["module"], source=pr["source"], language=pr["language"], loc=pr["loc"],
             cics=pr["cics"], sql=bool(pr["sql_tables"]), mq=pr["uses_mq"], ims=pr["uses_ims"])
    for c in cpy_paths + bms_cpy:
        node("copybook", c.stem.upper(), module=module_of(c), source=rel(c), bms_symbolic_map=c in bms_cpy)
    for b in bms_paths:
        node("mapset", b.stem.upper(), module=module_of(b), source=rel(b))
    for ms in csd["mapsets"]:
        node("mapset", ms["name"], csd=True)
    for name, d in listcat.items():
        node("dataset", name, **{k: v for k, v in d.items() if k != "name"}, in_listcat=True)
    for f in csd["files"]:
        ds = f["dsname"]
        node("cics_file", f["name"], dsname=ds, module=f["module"], recovery=f["recovery"])
        if ds:
            node("dataset", ds, in_listcat=ds in listcat)
            edge(node("cics_file", f["name"]), f"dataset:{ds}", "maps-to")
    for q in csd["tdqueues"]:
        node("tdqueue", q["name"], module=q["module"])

    for t in csd["transactions"]:
        if t["program"]:
            edge(f"transaction:{t['name']}", node("program", t["program"]), "starts")

    for pr in programs:
        src = f"program:{pr['name']}"
        for c in pr["copybooks"]:
            if f"copybook:{c}" in nodes:
                edge(src, f"copybook:{c}", "copy")
        for e in pr["edges"]:
            if e["target"] is None:
                edge(src, "program:?", e["kind"], how="dynamic", via=e.get("via"))
            else:
                tgt = node("program", e["target"]) if e["target"] in known or e["target"] in IBM_RUNTIME else None
                if tgt is None:
                    tgt = node("program", e["target"], external=True)
                if e["target"] in IBM_RUNTIME and nodes[tgt].get("module") is None:
                    nodes[tgt]["external"] = True
                edge(src, tgt, e["kind"], how=e["how"], **({"via": e["via"]} if e.get("via") else {}))
        for ms in pr["mapsets"]:
            edge(src, node("mapset", ms), "uses-map")
        for tid in sorted(set(pr["transids"])):
            if f"transaction:{tid}" in nodes:
                edge(src, f"transaction:{tid}", "returns-to-transaction")
        for f in pr["files"]:
            if f["channel"] == "cics":
                dsn = cics_file_map.get(f["file"])
                tgt = node("cics_file", f["file"], dsname=dsn)
                edge(src, tgt, "file-access", op=f["op"], channel="cics")
            elif f["channel"] == "cics-queue":
                qname = f["file"].split(":", 1)[1]
                edge(src, node("tdqueue", qname) if f["file"].startswith("TD:") else node("tsqueue", qname),
                     "queue-access", op=f["op"])
            else:
                dsns = sorted(batch_dd.get(pr["name"], {}).get(f["dd"], []))
                tgt = node("batch_ddname", f"{pr['name']}.{f['dd']}", dd=f["dd"], organization=f["organization"])
                edge(src, tgt, "file-access", op=f["op"], channel="batch", organization=f["organization"])
                for d in dsns:
                    node("dataset", d, in_listcat=d in listcat)
                    edge(tgt, f"dataset:{d}", "bound-by-jcl")
        for t in pr["sql_tables"]:
            edge(src, node("db2_table", t), "sql-access")

    for j in jcls:
        jn = node("jcl" if j["kind"] == "job" else "proc", j["name"], module=j["module"], source=j["source"],
                  steps=len(j["steps"]))
        for s in j["steps"]:
            if s["program"]:
                p = s["program"]
                tgt = node("program", p, external=True) if f"program:{p}" not in nodes else f"program:{p}"
                edge(jn, tgt, "executes", step=s["name"], **({"parm": s["parm"]} if s["parm"] else {}))
            if s["proc"] and s["proc"] in proc_names:
                edge(jn, f"proc:{s['proc']}", "invokes-proc", step=s["name"])
            for dd in s["dds"]:
                if dd["dsn"]:
                    d = norm_dsn(dd["dsn"])
                    if d.startswith("&") or "." not in d:
                        continue
                    node("dataset", d, in_listcat=d in listcat)
                    edge(jn, f"dataset:{d}", "dd", step=s["name"], dd=dd["dd"], disp=dd["disp"])
        r = j["idcams"]
        for a in r["define_aix"]:
            edge(jn, node("dataset", a["name"], in_listcat=a["name"] in listcat), "defines-aix", relate=a["relate"],
                 key_length=a["key_length"], key_offset=a["key_offset"], nonunique=a["nonunique"])
        for c in r["define_cluster"]:
            edge(jn, node("dataset", c, in_listcat=c in listcat), "defines-cluster")
        for s in j["steps"]:
            dd_by_name = {dd["dd"]: norm_dsn(dd["dsn"]) for dd in s["dds"] if dd["dsn"]}
            for rp in r["repro"]:
                if rp["in"] in dd_by_name and rp["out"] in dd_by_name:
                    edge(f"dataset:{dd_by_name[rp['in']]}" if f"dataset:{dd_by_name[rp['in']]}" in nodes else jn,
                         f"dataset:{dd_by_name[rp['out']]}", "repro-load", via=jn)
        for t in j["cics_file_toggles"]:
            edge(jn, node("cics_file", t["file"]), f"cics-file-{t['action']}")

    for s in sched:
        if s["scheduler"] == "control-m":
            sn = node("scheduler_job", f"controlm:{s['folder']}:{s['job']}", folder=s["folder"], job=s["job"])
            if f"jcl:{s['job']}" in nodes:
                edge(sn, f"jcl:{s['job']}", "runs")
            for c in s["in"]:
                edge(sn, node("condition", c), "waits-on")
            for c in s["out"]:
                edge(sn, node("condition", c), "posts")
        else:
            sn = node("scheduler_job", f"ca7:{s['job']}", job=s["job"])
            if f"jcl:{s.get('jcl_member')}" in nodes:
                edge(sn, f"jcl:{s['jcl_member']}", "runs")

    edges = [json.loads(x) for x in sorted({json.dumps(e, sort_keys=True) for e in edges})]

    dm = {
        "generated_by": "docs/migration/tools/build_inventory.py",
        "counts": {k: sum(1 for n in nodes.values() if n["kind"] == k) for k in sorted({n["kind"] for n in nodes.values()})},
        "nodes": sorted(nodes.values(), key=lambda n: n["id"]),
        "edges": edges,
        "programs": programs,
        "jcl": jcls,
        "csd": csd,
        "listcat": listcat,
        "scheduler": sched,
    }
    for pr in dm["programs"]:
        pr["mapsets"] = sorted(pr["mapsets"])
    (OUT / "dependency-map.json").write_text(json.dumps(dm, indent=1, sort_keys=False) + "\n")
    write_markdown(dm)
    print("wrote", OUT / "dependency-map.json", "and inventory.md;", len(nodes), "nodes", len(edges), "edges")


# ---------------------------------------------------------------- markdown

def write_markdown(dm):
    nodes = {n["id"]: n for n in dm["nodes"]}
    edges = dm["edges"]
    progs = {p["name"]: p for p in dm["programs"]}
    out = []
    w = out.append
    w("# CardDemo inventory and dependency map\n")
    w("Generated by `docs/migration/tools/build_inventory.py`. Do not hand-edit; rerun the script. "
      "Machine-readable form: `dependency-map.json`.\n")
    w("## Counts\n")
    w("| Kind | Count |\n|---|---|")
    for k, v in dm["counts"].items():
        w(f"| {k} | {v} |")
    w("")

    w("## Transactions and programs (core online)\n")
    w("| Transid | Program | Maps | Reads/writes (CICS file) | Next programs |\n|---|---|---|---|---|")
    core_tx = [t for t in dm["csd"]["transactions"] if t["module"] == "core"]
    for t in sorted(core_tx, key=lambda t: t["name"]):
        p = progs.get(t["program"])
        if not p:
            w(f"| {t['name']} | {t['program']} (no source in repo) | | | |")
            continue
        files = sorted({f"{f['file']}:{f['op']}" for f in p["files"] if f["channel"] in ("cics", "cics-queue")})
        nxt = sorted({e["target"] or "(dynamic)" for e in p["edges"] if e["kind"] in ("xctl", "link")})
        w(f"| {t['name']} | {p['name']} | {', '.join(p['mapsets'])} | {', '.join(files)} | {', '.join(nxt)} |")
    w("")

    w("## Program call graph\n")
    w("`how`: static = literal target; table/resolved = target taken from a menu table or a MOVE/VALUE; "
      "dynamic = target known only at run time (usually the caller recorded in the COMMAREA).\n")
    w("| Caller | Verb | Target | How | Via |\n|---|---|---|---|---|")
    for p in sorted(progs.values(), key=lambda p: p["name"]):
        seen = set()
        for e in p["edges"]:
            key = (e["kind"], e["target"], e["how"], e.get("via"))
            if key in seen or e["target"] in ("CEE3ABD",):
                continue
            seen.add(key)
            w(f"| {p['name']} | {e['kind'].upper()} | {e['target'] or '?'} | {e['how']} | {e.get('via') or ''} |")
    w("")

    w("## Online file access (CICS FILE to dataset)\n")
    w("| CICS file | Dataset | Programs | Operations |\n|---|---|---|---|")
    by_file = defaultdict(lambda: defaultdict(set))
    for p in dm["programs"]:
        for f in p["files"]:
            if f["channel"] == "cics":
                by_file[f["file"]][p["name"]].add(f["op"])
    cf = {f["name"]: f["dsname"] for f in dm["csd"]["files"]}
    for fname in sorted(by_file):
        progs_ops = by_file[fname]
        ops = sorted({o for s in progs_ops.values() for o in s})
        w(f"| {fname} | {cf.get(fname) or 'NOT DEFINED IN CSD'} | {', '.join(sorted(progs_ops))} | {', '.join(ops)} |")
    for fname in sorted(set(cf) - set(by_file)):
        w(f"| {fname} | {cf[fname]} | (none) | |")
    w("")

    w("## Batch programs and their JCL\n")
    w("| Program | JCL jobs | DD to dataset (via JCL) | Access |\n|---|---|---|---|")
    jcl_for = defaultdict(set)
    for e in edges:
        if e["kind"] == "executes" and e["from"].startswith(("jcl:", "proc:")):
            jcl_for[e["to"].split(":", 1)[1]].add(e["from"].split(":", 1)[1])
    dd_ds = defaultdict(set)
    for e in edges:
        if e["kind"] == "bound-by-jcl":
            dd_ds[e["from"]].add(e["to"].split(":", 1)[1].replace("AWS.M2.CARDDEMO.", ""))
    for p in sorted(progs.values(), key=lambda p: p["name"]):
        bf = [f for f in p["files"] if f["channel"] == "batch"]
        if not bf and not (p["name"] in jcl_for and not p["cics"]):
            continue
        if p["cics"]:
            continue
        jobs = ", ".join(sorted(jcl_for.get(p["name"], []))) or "(none found)"
        cells = []
        for f in bf:
            nid = f"batch_ddname:{p['name']}.{f['dd']}"
            ds = ", ".join(sorted(dd_ds.get(nid, []))) or "?"
            cells.append((f"{f['dd']} ({f['organization'][:3]})", ds, f["op"]))
        if not cells:
            w(f"| {p['name']} | {jobs} | | |")
        for i, (dd, ds, op) in enumerate(cells):
            w(f"| {p['name'] if i == 0 else ''} | {jobs if i == 0 else ''} | {dd}: {ds} | {op} |")
    w("")

    w("## JCL\n")
    w("| Member | Kind | Steps (program) | Datasets touched |\n|---|---|---|---|")
    for j in sorted(dm["jcl"], key=lambda j: (j["module"] != "core", j["name"])):
        steps = ", ".join(f"{s['name']}:{s['program'] or s['proc']}" for s in j["steps"])
        ds = [d.replace("AWS.M2.CARDDEMO.", "") for d in j["datasets"]]
        w(f"| {j['name']} | {j['kind']}{'' if j['module']=='core' else ' ('+j['module']+')'} | {steps} | {', '.join(ds[:8])}{' ...' if len(ds) > 8 else ''} |")
    w("")

    w("## Datasets (LISTCAT)\n")
    w("| Dataset | Type | Key len | Key offset | Max LRECL | Records | Notes |\n|---|---|---|---|---|---|---|")
    for name, d in sorted(dm["listcat"].items()):
        if d["type"] == "NONVSAM" and re.search(r"\.G\d{4}V\d{2}$", name):
            continue
        notes = ""
        if d["type"] == "AIX":
            notes = f"alt index on {d.get('base_cluster')}; nonunique={d.get('nonunique')}"
        if d["type"] == "PATH":
            notes = f"path to {d.get('aix')}"
        w(f"| {name.replace('AWS.M2.CARDDEMO.', '')} | {d['type']} | {d.get('key_length', '')} | {d.get('key_offset', '')} | "
          f"{d.get('max_lrecl', '')} | {d.get('records', '')} | {notes} |")
    gens = defaultdict(int)
    for name, d in dm["listcat"].items():
        mm = re.match(r"(.*)\.G\d{4}V\d{2}$", name)
        if mm:
            gens[mm.group(1)] += 1
    w("\nGDG generations cataloged (collapsed above): " + ", ".join(
        f"{k.replace('AWS.M2.CARDDEMO.', '')} x{v}" for k, v in sorted(gens.items())) + "\n")

    w("## Alternate indexes (JCL DEFINE vs LISTCAT)\n")
    w("| AIX | Base | Key length | Offset (0-based) | Nonunique | Source |\n|---|---|---|---|---|---|")
    for j in dm["jcl"]:
        for a in j["idcams"]["define_aix"]:
            lc = dm["listcat"].get(a["name"], {})
            ok = "match" if lc.get("key_length") == a["key_length"] and lc.get("key_offset") == a["key_offset"] else "MISMATCH"
            w(f"| {a['name'].replace('AWS.M2.CARDDEMO.', '')} | {(a['relate'] or '').replace('AWS.M2.CARDDEMO.', '')} | "
              f"{a['key_length']} | {a['key_offset']} | {a['nonunique']} | {j['name']} JCL; LISTCAT {ok} |")
    w("")

    w("## Copybook usage\n")
    w("| Copybook | Used by (programs) |\n|---|---|")
    used = defaultdict(set)
    for p in dm["programs"]:
        for c in p["copybooks"]:
            used[c].add(p["name"])
    for c in sorted(n["name"] for n in nodes.values() if n["kind"] == "copybook" and not n.get("bms_symbolic_map")):
        u = sorted(used.get(c, []))
        w(f"| {c} | {', '.join(u[:12])}{' ... (' + str(len(u)) + ')' if len(u) > 12 else ''}{'**unused**' if not u else ''} |")
    w("")

    w("## Optional modules (not in core slice)\n")
    for mod in ("db2-transaction-type", "ims-db2-mq-authorization", "vsam-mq"):
        ps = [p for p in dm["programs"] if p["module"] == mod]
        w(f"- **{mod}**: " + ", ".join(
            f"{p['name']}{' [SQL:' + ','.join(p['sql_tables']) + ']' if p['sql_tables'] else ''}"
            f"{' [MQ]' if p['uses_mq'] else ''}{' [IMS]' if p['uses_ims'] else ''}" for p in sorted(ps, key=lambda p: p['name'])))
    w("")

    w("## Scheduler\n")
    cm = [s for s in dm["scheduler"] if s["scheduler"] == "control-m"]
    folders = defaultdict(list)
    for s in cm:
        folders[s["folder"]].append(s["job"])
    for f, jobs in folders.items():
        w(f"- Control-M folder `{f}`: {' -> '.join(jobs)}")
    ca7 = [s["job"] for s in dm["scheduler"] if s["scheduler"] == "ca7"]
    w(f"- CA-7 jobs listed: {', '.join(ca7)}\n")

    w("## Gaps the scanner reports\n")
    gaps = []
    for t in dm["csd"]["transactions"]:
        if t["program"] not in progs:
            gaps.append(f"Transaction {t['name']} ({t['module']}) names program {t['program']} with no source in the repo.")
    defined = {p["name"] for p in dm["csd"]["programs"]}
    for name, p in progs.items():
        if p["cics"] and name not in defined and p["module"] == "core":
            gaps.append(f"CICS program {name} has no DEFINE PROGRAM in the core CSD.")
    unresolved = [(p["name"], e["via"]) for p in dm["programs"] for e in p["edges"] if e["how"] == "dynamic"]
    for n, v in sorted(set(unresolved)):
        gaps.append(f"{n}: dynamic transfer target `{v}` cannot be resolved statically.")
    for e in edges:
        if e["kind"] == "file-access" and e.get("channel") == "cics":
            f = nodes.get(e["to"], {})
            if not f.get("dsname") and f["name"] not in {x["name"] for x in dm["csd"]["files"]}:
                gaps.append(f"{e['from'].split(':', 1)[1]} uses CICS file {f['name']} not defined in any CSD.")
    for g in sorted(set(gaps)):
        w(f"- {g}")
    (OUT / "inventory.md").write_text("\n".join(out) + "\n")


if __name__ == "__main__":
    sys.exit(main())
