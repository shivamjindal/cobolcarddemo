"""BMS maps and a 3270 terminal model for the journey driver.

The terminal keeps what the user sees per named field and the modified-data tag
(MDT). On input it transmits only fields with MDT on: fields the user typed in,
fields defined FSET in the BMS source, or fields the program gave an attribute
with the MDT bit. That mirrors why CardDemo programs see unchanged FSET fields
again on the next RECEIVE MAP.
"""
import re
from pathlib import Path

from copybook import load_layout

REPO = Path(__file__).resolve().parents[2]
COPY_DIR = Path(__file__).resolve().parent / "runtime" / "copy"
COLOR_NAMES = {"BLUE", "RED", "PINK", "GREEN", "TURQ", "YELLO", "NEUTR", "DFCOL"}
BMS_COLORS = {"BLUE": "BLUE", "RED": "RED", "PINK": "PINK", "GREEN": "GREEN",
              "TURQUOISE": "TURQ", "YELLOW": "YELLO", "NEUTRAL": "NEUTR", "DEFAULT": "DFCOL"}
# Fields that show the environment (clock, region) rather than program behavior.
IGNORED_FIELDS = {"CURDATE", "CURTIME", "APPLID", "SYSID", "TITLE01", "TITLE02"}


def _copy_values(name):
    """Map DFH constant names to their byte value from the harness copybook."""
    text = (COPY_DIR / f"{name}.cpy").read_text()
    out = {}
    for m in re.finditer(r"02\s+(DFH\w+)\s+PIC X VALUE\s+(X'([0-9A-F]{2})'|'(''|[^'])')", text):
        out[m.group(1)] = bytes.fromhex(m.group(3)) if m.group(3) else \
            ("'" if m.group(4) == "''" else m.group(4)).encode("latin-1")
    return out


AID = {k[3:]: v for k, v in _copy_values("DFHAID").items()}
_BMSCA = _copy_values("DFHBMSCA")
COLOR_BY_BYTE = {v: k[3:] for k, v in _BMSCA.items() if k[3:] in COLOR_NAMES and v != b"\x00"}
NO_DATA_AIDS = {"CLEAR", "PA1", "PA2", "PA3"}


def _ebcdic(b):
    return b.decode("latin-1").encode("cp037")[0]


def mdt_on(attr_byte):
    return bool(_ebcdic(attr_byte) & 0x01)


def protected(attr_byte):
    return bool(_ebcdic(attr_byte) & 0x20)


def _bms_statements(text):
    """Join assembler continuation lines (non-blank column 72, resume at column 16)."""
    stmt, continuing = "", False
    for line in text.splitlines():
        if line.startswith("*") and not continuing:
            continue
        body = line[15:71] if continuing else line[:71]
        continuing = len(line) > 71 and line[71] != " "
        in_literal = (stmt + body).count("'") % 2 == 1
        stmt += body if continuing and in_literal else body.rstrip()
        if not continuing:
            if stmt.strip():
                yield stmt
            stmt = ""


def _operands(s):
    """Operand field: everything up to the first blank outside quotes."""
    quote = False
    for i, c in enumerate(s):
        if c == "'":
            quote = not quote
        elif c == " " and not quote:
            return s[:i]
    return s


def _split_params(s):
    parts, depth, quote, cur = [], 0, False, ""
    i = 0
    while i < len(s):
        c = s[i]
        if quote:
            cur += c
            if c == "'":
                if i + 1 < len(s) and s[i + 1] == "'":
                    cur += "'"
                    i += 1
                else:
                    quote = False
        elif c == "'":
            quote, cur = True, cur + c
        elif c == "(":
            depth, cur = depth + 1, cur + c
        elif c == ")":
            depth, cur = depth - 1, cur + c
        elif c == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += c
        i += 1
    if cur:
        parts.append(cur)
    return parts


def parse_bms(path):
    """Return {map_name: [field dict in screen order]} for named DFHMDF fields."""
    maps, current = {}, None
    for st in _bms_statements(Path(path).read_text(encoding="latin-1")):
        m = re.match(r"(\S*)\s+(DFHMSD|DFHMDI|DFHMDF)\s*(.*)$", st)
        if not m:
            continue
        name, macro, params = m.groups()
        if macro == "DFHMDI":
            current = maps.setdefault(name, [])
            continue
        if macro != "DFHMDF" or not name or current is None:
            continue
        field = {"name": name, "attrb": set(), "initial": "", "color": None}
        for p in _split_params(_operands(params.strip())):
            k, _, v = p.strip().partition("=")
            if k == "ATTRB":
                field["attrb"] = set(v.strip("()").split(","))
            elif k == "INITIAL":
                field["initial"] = v.strip()[1:-1].replace("''", "'")
            elif k == "COLOR":
                field["color"] = BMS_COLORS.get(v)
            elif k == "LENGTH":
                field["length"] = int(v)
        current.append(field)
    return maps


class MapDef:
    def __init__(self, mapset, mapname):
        self.mapset, self.name = mapset, mapname
        cpy = REPO / "app" / "cpy-bms" / f"{mapset}.CPY"
        inp = load_layout(cpy, mapname + "I", keep_redefines=True)
        out = load_layout(cpy, mapname + "O", keep_redefines=True)
        self.size = inp.size
        bms = {f["name"]: f for f in parse_bms(REPO / "app" / "bms" / f"{mapset}.bms")[mapname]}
        self.fields = []
        for lf in inp.leaves:
            if not (lf.name.endswith("L") and lf.kind == "comp"):
                continue
            base = lf.name[:-1]
            if base + "I" not in inp.by_name:
                continue
            i = inp.by_name[base + "I"]
            spec = bms.get(base, {"attrb": set(), "initial": "", "color": None})
            self.fields.append({
                "name": base, "l": lf.offset, "a": inp.by_name[base + "A"].offset,
                "c": out.by_name[base + "C"].offset if base + "C" in out.by_name else None,
                "data": i.offset, "len": i.size, "bms": spec,
            })


_MAP_CACHE = {}


def mapdef(mapset, mapname):
    key = (mapset, mapname)
    if key not in _MAP_CACHE:
        _MAP_CACHE[key] = MapDef(mapset, mapname)
    return _MAP_CACHE[key]


class Terminal:
    def __init__(self):
        self.map = None
        self.fields = {}
        self.cursor = None
        self.text = None

    def _reset(self, md):
        self.map = md
        self.fields = {}
        for f in md.fields:
            b = f["bms"]
            self.fields[f["name"]] = {
                "value": b["initial"], "mdt": "FSET" in b["attrb"],
                "protected": bool({"PROT", "ASKIP"} & b["attrb"]),
                "bms_protected": bool({"PROT", "ASKIP"} & b["attrb"]),
                "color": b["color"], "bms_color": b["color"],
            }

    def send_map(self, mapset, mapname, flags, data):
        md = mapdef(mapset, mapname)
        flags = set(flags.split())
        self.text = None
        if "ERASE" in flags or self.map is not md or "MAPONLY" in flags:
            self._reset(md)
        if "MAPONLY" in flags:
            data = b""
        data = data.ljust(md.size, b"\x00")
        cursor = None
        for f in md.fields:
            st = self.fields[f["name"]]
            raw = data[f["data"]:f["data"] + f["len"]]
            if raw.strip(b"\x00"):
                st["value"] = raw.replace(b"\x00", b" ").decode("latin-1").rstrip()
            attr = data[f["a"]:f["a"] + 1]
            if attr != b"\x00":
                st["mdt"] = mdt_on(attr)
                st["protected"] = protected(attr)
            if f["c"] is not None:
                c = data[f["c"]:f["c"] + 1]
                if c in COLOR_BY_BYTE:
                    st["color"] = COLOR_BY_BYTE[c]
            if cursor is None and int.from_bytes(data[f["l"]:f["l"] + 2], "big", signed=True) == -1:
                cursor = f["name"]
        if "CURSOR" in flags and cursor:
            self.cursor = cursor
        elif "ERASE" in flags or "MAPONLY" in flags:
            self.cursor = next((f["name"] for f in md.fields if "IC" in f["bms"]["attrb"]), None)

    def send_text(self, text):
        self.map, self.fields, self.cursor = None, {}, None
        self.text = text.rstrip()

    def type_and_send(self, aid, typed):
        """Apply the user's typing, then return the RECEIVE MAP buffer or None (MAPFAIL)."""
        if self.map is None:
            if typed:
                raise ValueError(f"no map on screen to type into: {sorted(typed)}")
            return None
        for name, value in (typed or {}).items():
            if name not in self.fields:
                raise ValueError(f"field {name} is not on map {self.map.name}")
            if value is None:
                continue
            st = self.fields[name]
            st["value"], st["mdt"] = str(value), True
        if aid in NO_DATA_AIDS:
            return None
        buf = bytearray(self.map.size)
        sent = False
        for f in self.map.fields:
            st = self.fields[f["name"]]
            if not st["mdt"]:
                continue
            sent = True
            value = st["value"][:f["len"]]
            buf[f["l"]:f["l"] + 2] = len(value).to_bytes(2, "big", signed=True)
            if value:
                buf[f["data"]:f["data"] + f["len"]] = value.encode("latin-1").ljust(f["len"], b" ")
        return bytes(buf) if sent else None

    def snapshot(self):
        if self.text is not None:
            return {"text": self.text}
        if self.map is None:
            return {}
        shown = {n: s["value"] for n, s in self.fields.items()
                 if s["value"].strip() and n not in IGNORED_FIELDS}
        colors = {n: s["color"] for n, s in self.fields.items()
                  if s["color"] != s["bms_color"] and n not in IGNORED_FIELDS}
        out = {"map": self.map.name, "fields": shown}
        if colors:
            out["colors"] = colors
        prot = sorted(n for n, s in self.fields.items() if s["protected"] and not s["bms_protected"])
        unprot = sorted(n for n, s in self.fields.items() if not s["protected"] and s["bms_protected"])
        if prot:
            out["protected"] = prot
        if unprot:
            out["unprotected"] = unprot
        if self.cursor:
            out["cursor"] = self.cursor
        return out
