"""EXEC SQL stand-in for COPAUS2C only: its INSERT and UPDATE on CARDDEMO.AUTHFRDS.

Not a SQL engine. Each statement becomes COBOL that builds one AUTHFRDS row
(characterization/fixtures/copy/AUTHFRDS.cpy, fields prefixed AFR-) from the host
variables and CALLs runtime/authfrds.c, then copies SQLCODE and SQLSTATE into the
program's SQLCA. INCLUDE SQLCA defines a minimal SQLCA; INCLUDE AUTHFRDS inlines the
DCLGEN host variables. Any other statement or program is refused.
"""
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DCLGEN = REPO / "app" / "app-authorization-ims-db2-mq" / "dcl" / "AUTHFRDS.dcl"
ROW = REPO / "characterization" / "fixtures" / "copy" / "AUTHFRDS.cpy"
PROGRAMS = {"COPAUS2C"}
TS_FORMAT = "'YY-MM-DD HH24.MI.SSNNNNNN'"

SQLCA = """       01  SQLCA.
           05  SQLCAID        PIC X(8) VALUE 'SQLCA'.
           05  SQLCABC        PIC S9(9) COMP-5 VALUE 136.
           05  SQLCODE        PIC S9(9) COMP-5 VALUE 0.
           05  SQLERRM.
               49  SQLERRML   PIC S9(4) COMP-5 VALUE 0.
               49  SQLERRMC   PIC X(70) VALUE SPACES.
           05  SQLERRP        PIC X(8) VALUE SPACES.
           05  SQLERRD        PIC S9(9) COMP-5 OCCURS 6 TIMES VALUE 0.
           05  SQLWARN        PIC X(11) VALUE SPACES.
           05  SQLSTATE       PIC X(5) VALUE '00000'.
       01  AFR-REQUEST.
           05  AFR-OP         PIC X(6).
           05  AFR-SQLCODE    PIC S9(9) COMP-5.
           05  AFR-SQLSTATE   PIC X(5).
           05  AFR-NOW        PIC X(21).
"""


def _row_fields():
    names = []
    for line in ROW.read_text(encoding="latin-1").splitlines():
        m = re.match(r"\s+05\s+([A-Z0-9-]+)\s+PIC", line[7:72] if len(line) > 7 else "")
        if m:
            names.append(m.group(1))
    return names


def _row_definition():
    out = []
    for line in ROW.read_text(encoding="latin-1").splitlines():
        if len(line) > 6 and line[6] == "*":
            continue
        line = re.sub(r"\b01\s+AUTHFRDS-RECORD\b", "01  AFR-RECORD", line)
        line = re.sub(r"(\s05\s+)([A-Z0-9-]+)", lambda m: m.group(1) + "AFR-" + m.group(2), line)
        out.append(line)
    return out


def _dclgen():
    lines = DCLGEN.read_text(encoding="latin-1").splitlines()
    start = next(i for i, l in enumerate(lines) if re.search(r"\b01\s+DCLAUTHFRDS\b", l))
    return [l[:72] for l in lines[start:]]


def _varchar(host):
    return any(re.search(rf"\b{re.escape(host)}-TEXT\b", l) for l in _dclgen())


def _column(name):
    field = "AFR-" + name.replace("_", "-")
    if name.replace("_", "-") not in _row_fields():
        raise ValueError(f"AUTHFRDS has no column {name}")
    return field


def _assign(column, value):
    """COBOL that puts one SQL value expression into the row field for column."""
    field = _column(column)
    value = re.sub(r"\s+", " ", value.strip())
    m = re.fullmatch(r":([A-Z0-9-]+)", value)
    if m:
        host = m.group(1)
        if _varchar(host):
            return [f"MOVE {host}-TEXT(1:{host}-LEN) TO {field}"]
        return [f"MOVE {host} TO {field}"]
    m = re.fullmatch(r"TIMESTAMP_FORMAT ?\( ?:([A-Z0-9-]+) ?, ?('[^']*') ?\)", value)
    if m and m.group(2) == TS_FORMAT and column == "AUTH_TS":
        return [f"MOVE {m.group(1)} TO {field}"]
    if value == "CURRENT DATE":
        return ["MOVE FUNCTION CURRENT-DATE TO AFR-NOW",
                f"STRING AFR-NOW(1:4) '-' AFR-NOW(5:2) '-' AFR-NOW(7:2) DELIMITED BY SIZE INTO {field}"]
    raise ValueError(f"unsupported SQL value {value!r}")


def _split(text):
    parts, depth, cur = [], 0, ""
    for c in text:
        if c == "," and depth == 0:
            parts.append(cur)
            cur = ""
            continue
        depth += c == "("
        depth -= c == ")"
        cur += c
    return parts + [cur]


def _call(op):
    return [f"MOVE '{op}' TO AFR-OP",
            "CALL 'AUTHFRDS' USING AFR-OP AFR-RECORD AFR-SQLCODE AFR-SQLSTATE END-CALL",
            "MOVE AFR-SQLCODE TO SQLCODE", "MOVE AFR-SQLSTATE TO SQLSTATE"]


def translate(body, program):
    """Return ("data", lines) for an INCLUDE or ("code", statements) for a statement."""
    if program not in PROGRAMS:
        raise ValueError(f"EXEC SQL in {program}: the harness only stands in for COPAUS2C's SQL")
    sql = re.sub(r"\s+", " ", body).strip()
    up = sql.upper()
    if up == "INCLUDE SQLCA":
        return "data", SQLCA.rstrip("\n").split("\n") + _row_definition()
    if up == "INCLUDE AUTHFRDS":
        return "data", _dclgen()
    m = re.fullmatch(r"INSERT INTO CARDDEMO\.AUTHFRDS ?\((.*?)\) ?VALUES ?\((.*)\)", sql, re.I)
    if m:
        cols = [c.strip() for c in _split(m.group(1))]
        vals = _split(m.group(2))
        if len(cols) != len(vals):
            raise ValueError("INSERT column and value counts differ")
        stmts = ["MOVE SPACES TO AFR-RECORD"]
        for c, v in zip(cols, vals):
            stmts += _assign(c, v)
        return "code", stmts + _call("INSERT")
    m = re.fullmatch(r"UPDATE CARDDEMO\.AUTHFRDS SET (.*) WHERE (.*)", sql, re.I)
    if m:
        stmts = ["MOVE LOW-VALUES TO AFR-RECORD"]
        for part in _split(m.group(1)) + re.split(r"\s+AND\s+", m.group(2)):
            col, val = part.split("=", 1)
            stmts += _assign(col.strip(), val)
        return "code", stmts + _call("UPDATE")
    raise ValueError(f"unsupported SQL statement in {program}: {sql[:60]}")
