"""Translate a CICS COBOL program into plain GnuCOBOL that calls the harness stub.

Each EXEC CICS block becomes MOVEs into CICS-REQ plus CALL "CICSAPI". Name options
(MAP, FILE, PROGRAM, ...) go into the request block, data options (INTO, FROM,
RIDFLD, COMMAREA, ...) are passed by reference, everything else is a flag.
"""
import re
import sys

RESP_CODES = {
    "NORMAL": 0, "ERROR": 1, "EOF": 4, "DSIDERR": 12, "FILENOTFOUND": 12, "NOTFND": 13,
    "DUPREC": 14, "DUPKEY": 15, "INVREQ": 16, "IOERR": 17, "NOSPACE": 18, "NOTOPEN": 19,
    "ENDFILE": 20, "ILLOGIC": 21, "LENGERR": 22, "PGMIDERR": 27, "TRANSIDERR": 28,
    "MAPFAIL": 36, "QIDERR": 44, "DISABLED": 84,
}
NAME_OPTS = {"MAP": "CICS-NAME", "FILE": "CICS-NAME", "DATASET": "CICS-NAME",
             "PROGRAM": "CICS-NAME", "QUEUE": "CICS-NAME", "ABCODE": "CICS-NAME",
             "MAPSET": "CICS-MAPSET", "TRANSID": "CICS-TRANSID"}
NUM_OPTS = {"LENGTH": "CICS-LEN", "FLENGTH": "CICS-LEN", "KEYLENGTH": "CICS-KEYLEN"}
IGNORED_ARG_OPTS = {"LABEL", "CURSOR", "REQID"}
DATA_OPTS = {"INTO", "FROM", "RIDFLD", "COMMAREA", "APPLID", "SYSID", "ABSTIME",
             "YYYYMMDD", "MMDDYYYY", "MMDDYY", "DDMMYY", "YYMMDD", "DATE", "TIME",
             "DATESEP", "TIMESEP", "TEXT", "USERID", "TERMID", "OPID"}
TWO_WORD_VERBS = {"SEND", "RECEIVE", "HANDLE", "IGNORE"}


def code_area(line):
    return line[7:72] if len(line) > 7 else ""


def is_comment(line):
    return len(line) > 6 and line[6] in "*/"


def scan_options(body):
    """Yield (KEYWORD, arg-or-None) pairs from the text between EXEC CICS and END-EXEC."""
    i, n = 0, len(body)
    while i < n:
        if body[i].isspace():
            i += 1
            continue
        m = re.match(r"[A-Z0-9-]+", body[i:], re.I)
        if not m:
            raise ValueError(f"cannot parse CICS options at: {body[i:]!r}")
        word = m.group(0).upper()
        i += len(word)
        while i < n and body[i] == " ":
            i += 1
        arg = None
        if i < n and body[i] == "(":
            depth, j, quote = 0, i, None
            while j < n:
                c = body[j]
                if quote:
                    if c == quote:
                        quote = None
                elif c in "'\"":
                    quote = c
                elif c == "(":
                    depth += 1
                elif c == ")":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            arg = body[i + 1:j].strip()
            i = j + 1
        yield word, arg


def wrap(stmts, indent=11):
    """Lay statements out in fixed-format columns 12-72, breaking between tokens."""
    out = []
    for s in stmts:
        tokens = re.findall(r"'[^']*'|\"[^\"]*\"|\S+", s)
        line = " " * indent
        for t in tokens:
            if len(line) + len(t) + 1 > 72 and line.strip():
                out.append(line.rstrip())
                line = " " * (indent + 4)
            line += t + " "
        out.append(line.rstrip())
    return ["       " + l[7:] if len(l) > 7 else l for l in out]


class Translator:
    def __init__(self, program):
        self.program = program
        self.handlers = {}

    def translate_block(self, body):
        opts = list(scan_options(body))
        verb, rest = opts[0][0], opts[1:]
        if verb in TWO_WORD_VERBS and rest:
            key, arg = rest[0]
            if key == "MAP":
                verb += " MAP"
            elif arg is None:
                verb += " " + key
                rest = rest[1:]
            elif verb == "SEND":
                verb = "SEND FROM"

        if verb == "HANDLE CONDITION":
            for cond, label in rest:
                self.handlers[cond] = label
            return ["CONTINUE"]
        if verb == "IGNORE CONDITION":
            for cond, _ in rest:
                self.handlers.pop(cond, None)
            return ["CONTINUE"]
        if verb == "HANDLE ABEND":
            return ["CONTINUE"]

        stmts = ["INITIALIZE CICS-REQ", f"MOVE '{verb}' TO CICS-FN"]
        flags, args, resp, resp2, nohandle = [], [], None, None, False
        mapname = None
        for key, arg in rest:
            if key in NAME_OPTS and arg is not None:
                stmts.append(f"MOVE {arg} TO {NAME_OPTS[key]}")
                if key == "MAP":
                    mapname = arg.strip("'\"")
            elif key in NUM_OPTS and arg is not None:
                stmts.append(f"MOVE {arg} TO {NUM_OPTS[key]}")
            elif key == "RESP":
                resp = arg
            elif key == "RESP2":
                resp2 = arg
            elif key == "NOHANDLE":
                nohandle = True
            elif key in DATA_OPTS and arg is not None:
                args.append((key, arg))
            elif key in IGNORED_ARG_OPTS:
                flags.append(key)
            elif arg is None:
                flags.append(key)
            else:
                args.append((key, arg))
        if verb == "SEND MAP" and not any(k == "FROM" for k, _ in args) and "MAPONLY" not in flags:
            args.append(("FROM", mapname + "O"))
        if verb == "RECEIVE MAP" and not any(k == "INTO" for k, _ in args):
            args.append(("INTO", mapname + "I"))
        if len(args) > 8:
            raise ValueError(f"too many data options in {verb}")
        if flags:
            stmts.append(f"MOVE ' {' '.join(flags)} ' TO CICS-FLAGS")
        if args:
            stmts.append(f"MOVE ' {' '.join(k for k, _ in args)} ' TO CICS-ARGNAMES")
        using = ["CICS-REQ", "DFHEIBLK"]
        for idx, (key, arg) in enumerate(args, 1):
            literal = arg[0] in "'\""
            if not literal:
                stmts.append(f"MOVE LENGTH OF {arg} TO CICS-ARGLEN({idx})")
            else:
                stmts.append(f"MOVE {len(arg) - 2} TO CICS-ARGLEN({idx})")
            using.append(f"BY CONTENT {arg}" if literal else f"BY REFERENCE {arg}")
        stmts.append(f"CALL 'CICSAPI' USING {' '.join(using)} END-CALL")
        if resp:
            stmts.append(f"MOVE CICS-RESP TO {resp}")
        if resp2:
            stmts.append(f"MOVE CICS-RESP2 TO {resp2}")
        if not resp and not nohandle:
            for cond, label in self.handlers.items():
                if cond in RESP_CODES and label:
                    stmts.append(f"IF CICS-RESP = {RESP_CODES[cond]} GO TO {label} END-IF")
            stmts.append("IF CICS-RESP NOT = 0 CALL 'CICSABND' USING CICS-REQ END-CALL END-IF")
        if verb == "RETURN":
            stmts.append("GOBACK")
        elif verb == "XCTL":
            stmts.append("IF CICS-RESP = 0 GOBACK END-IF")
        return stmts


def replace_dfhresp(code):
    return re.sub(r"DFHRESP\s*\(\s*([A-Z0-9]+)\s*\)",
                  lambda m: str(RESP_CODES[m.group(1).upper()]), code, flags=re.I)


def translate(src_text, program):
    tr = Translator(program)
    lines = [l.rstrip("\n\r") for l in src_text.splitlines()]
    out = []
    i = 0
    saw_link = saw_commarea = False
    while i < len(lines):
        line = lines[i]
        if is_comment(line):
            out.append(line[:72])
            i += 1
            continue
        code = code_area(line)
        upper = code.upper()
        if re.search(r"\bLINKAGE\s+SECTION\b", upper):
            out.append("       COPY CICSREQ.")
            out.append(line[:72])
            out.append("       COPY DFHEIBLK.")
            saw_link = True
            i += 1
            continue
        if re.search(r"\bPROCEDURE\s+DIVISION\b", upper):
            if not saw_link:
                out += ["       COPY CICSREQ.", "       LINKAGE SECTION.", "       COPY DFHEIBLK."]
            if not saw_commarea:
                out.append("       01  DFHCOMMAREA PIC X.")
            if "USING" in upper:
                raise ValueError("PROCEDURE DIVISION USING already present")
            out.append("       PROCEDURE DIVISION USING DFHEIBLK DFHCOMMAREA.")
            i += 1
            continue
        if re.search(r"\b01\s+DFHCOMMAREA\b", upper):
            saw_commarea = True
        m = re.search(r"\bEXEC\s+CICS\b", code, re.I)
        if not m:
            out.append(line[:7] + replace_dfhresp(code) if len(line) > 7 else line)
            i += 1
            continue
        prefix = code[:m.start()]
        body = code[m.end():]
        start = i
        while not re.search(r"\bEND-EXEC\b", body, re.I):
            i += 1
            if not is_comment(lines[i]):
                body += " " + code_area(lines[i])
        e = re.search(r"\bEND-EXEC\b", body, re.I)
        suffix = body[e.end():].strip()
        body = body[:e.start()]
        if prefix.strip():
            out.append(lines[start][:7] + prefix)
        out += wrap(tr.translate_block(body))
        if suffix:
            out.append("           " + suffix)
        i += 1
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, encoding="latin-1") as f:
        text = f.read()
    with open(dst, "w", encoding="latin-1") as f:
        f.write(translate(text, re.sub(r"\..*$", "", src.split("/")[-1])))
