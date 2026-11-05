"""Translate embedded static SQL into GnuCOBOL calls on the harness SQL runtime.

EXEC SQL INCLUDE expands the member inline (SQLCA becomes COPY SQLCA). DECLARE TABLE and
DECLARE CURSOR vanish; a cursor's SELECT is kept for its OPEN. Every executable statement
becomes CALL "SQLEXEC" USING SQLCA, the statement id, then its host variables in order:
input host variables first, then INTO targets. The statement text, with inputs rewritten as
$1..$n, goes to <program>.sql, one statement per line:

    id <TAB> verb <TAB> cursor <TAB> params <TAB> sql

params is a comma list of I or O (input or output) plus V (a level-49 VARCHAR group) or X.
"""
import re

from cicsprep import code_area, is_comment, wrap


def _declarations(lines):
    """(level, name) for every data description entry, in source order."""
    out = []
    for line in lines:
        if is_comment(line):
            continue
        m = re.match(r"\s*(\d\d)\s+([A-Z0-9][A-Z0-9-]*)", code_area(line), re.I)
        if m:
            out.append((int(m.group(1)), m.group(2).upper()))
    return out


def _varchar_groups(decls):
    return {name for (lvl, name), nxt in zip(decls, decls[1:] + [(0, "")]) if nxt[0] == 49 and lvl != 49}


class Translator:
    def __init__(self, program, include_dirs):
        self.program = program
        self.include_dirs = include_dirs
        self.cursors = {}
        self.statements = []
        self.varchars = set()

    def include(self, member):
        for d in self.include_dirs:
            for ext in ("", ".cpy", ".dcl", ".CPY"):
                p = d / f"{member}{ext}"
                if p.exists():
                    return p.read_text(encoding="latin-1").splitlines()
        raise FileNotFoundError(member)

    def expand(self, lines):
        """Inline EXEC SQL INCLUDE members so their DECLAREs and host variables are visible."""
        out, i = [], 0
        while i < len(lines):
            line, j = lines[i], i
            text = "" if is_comment(line) else code_area(line)
            if re.search(r"\bEXEC\s+SQL\b", text, re.I):
                while not re.search(r"\bEND-EXEC\b", text, re.I):
                    j += 1
                    text += " " + code_area(lines[j])
            m = re.search(r"\bEXEC\s+SQL\s+INCLUDE\s+([A-Z0-9]+)\s+END-EXEC", text, re.I)
            if not m:
                out.append(line)
            elif m.group(1).upper() == "SQLCA":
                out.append("       COPY SQLCA.")
                i = j
            else:
                out += self.expand(self.include(m.group(1).upper()))
                i = j
            i += 1
        return out

    def host_vars(self, sql):
        return [h.upper() for h in re.findall(r":([A-Z0-9][A-Z0-9-]*)", sql, re.I)]

    def param(self, role, hv):
        return role + ("V" if hv in self.varchars else "X")

    def emit(self, verb, cursor, inputs, outputs, sql):
        n = len(self.statements) + 1
        for i, _ in enumerate(inputs, 1):
            sql = re.sub(r":[A-Z0-9][A-Z0-9-]*", f"${i}", sql, count=1, flags=re.I)
        params = [self.param("I", h) for h in inputs] + [self.param("O", h) for h in outputs]
        self.statements.append("\t".join([str(n), verb, cursor or "-", ",".join(params) or "-",
                                          " ".join(sql.split())]))
        using = ["SQLCA", f"BY CONTENT '{self.program}:{n:04d}'"] + \
                [f"BY REFERENCE {h}" for h in inputs + outputs]
        return ["MOVE 0 TO SQLCODE", f"CALL 'SQLEXEC' USING {' '.join(using)} END-CALL"]

    def translate_block(self, body):
        sql = " ".join(body.split())
        upper = sql.upper()
        if re.match(r"DECLARE\s+\S+\s+TABLE\b", upper):
            return []
        m = re.match(r"DECLARE\s+(\S+)\s+CURSOR\s+(?:WITH\s+HOLD\s+)?FOR\s+(.*)$", sql, re.I | re.S)
        if m:
            self.cursors[m.group(1).upper()] = m.group(2)
            return []
        m = re.match(r"OPEN\s+(\S+)$", sql, re.I)
        if m:
            name = m.group(1).upper()
            select = self.cursors[name]
            return self.emit("OPEN", name, self.host_vars(select), [], select)
        m = re.match(r"FETCH\s+(?:NEXT\s+)?(?:FROM\s+)?(\S+)\s+INTO\s+(.*)$", sql, re.I)
        if m:
            return self.emit("FETCH", m.group(1).upper(), [], self.host_vars(m.group(2)), "")
        m = re.match(r"CLOSE\s+(\S+)$", sql, re.I)
        if m:
            return self.emit("CLOSE", m.group(1).upper(), [], [], "")
        m = re.match(r"(SELECT\s+.*?)\s+INTO\s+(.*?)\s+(FROM\s+.*)$", sql, re.I | re.S)
        if m:
            rest = m.group(1) + " " + m.group(3)
            return self.emit("SELECT", None, self.host_vars(rest), self.host_vars(m.group(2)), rest)
        verb = upper.split()[0]
        if verb in ("INSERT", "UPDATE", "DELETE", "COMMIT", "ROLLBACK"):
            return self.emit(verb, None, self.host_vars(sql), [], sql)
        raise ValueError(f"{self.program}: unsupported SQL: {sql[:80]}")

    def translate(self, src_text):
        lines = self.expand([l.rstrip("\n\r") for l in src_text.splitlines()])
        self.varchars = _varchar_groups(_declarations(lines))
        out, i = [], 0
        while i < len(lines):
            line = lines[i]
            code = code_area(line)
            m = None if is_comment(line) else re.search(r"\bEXEC\s+SQL\b", code, re.I)
            if not m:
                out.append(line[:72])
                i += 1
                continue
            prefix, body, start = code[:m.start()], code[m.end():], i
            while not re.search(r"\bEND-EXEC\b", body, re.I):
                i += 1
                if not is_comment(lines[i]):
                    body += " " + code_area(lines[i])
            e = re.search(r"\bEND-EXEC\b", body, re.I)
            suffix = body[e.end():].strip()
            if prefix.strip():
                out.append(lines[start][:7] + prefix)
            stmts = self.translate_block(body[:e.start()])
            out += wrap(stmts)
            if suffix and (stmts or suffix != "."):
                out.append("           " + suffix)
            i += 1
        return "\n".join(out) + "\n"


def translate(src_text, program, include_dirs):
    """Return (cobol source, statement table text)."""
    tr = Translator(program, include_dirs)
    cobol = tr.translate(src_text)
    return cobol, "\n".join(tr.statements) + "\n"
