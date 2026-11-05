"""Minimal COBOL copybook layout parser and record codec.

Covers what CardDemo records and BMS symbolic maps use: PIC X/A/9/S9/V and edited
pictures, DISPLAY, COMP/BINARY/COMP-5, COMP-3, fixed OCCURS, REDEFINES, FILLER.
Signed DISPLAY numerics use the EBCDIC overpunch convention seen in app/data/ASCII
('{' 'A'-'I' positive, '}' 'J'-'R' negative), matching cobc -fsign=EBCDIC.
"""
import re
from dataclasses import dataclass, field
from decimal import Decimal

POS_OVERPUNCH = "{ABCDEFGHI"
NEG_OVERPUNCH = "}JKLMNOPQR"


@dataclass
class Item:
    level: int
    name: str
    pic: str = ""
    usage: str = "DISPLAY"
    occurs: int = 1
    redefines: str = ""
    children: list = field(default_factory=list)
    offset: int = 0
    size: int = 0


@dataclass
class Leaf:
    name: str
    offset: int
    size: int
    kind: str  # text | num | comp | comp3
    digits: int = 0
    scale: int = 0
    signed: bool = False


def _statements(text):
    code = []
    for line in text.splitlines():
        if len(line) > 6 and line[6] in "*/":
            continue
        code.append(line[7:72] if len(line) > 7 else "")
    joined = " ".join(code)
    return [s.strip() for s in re.split(r"\.(?=\s|$)", joined) if s.strip()]


def _pic_info(pic):
    expanded = re.sub(r"(.)\((\d+)\)", lambda m: m.group(1) * int(m.group(2)), pic.upper())
    signed = expanded.startswith("S")
    body = expanded.lstrip("S")
    numeric = bool(body) and set(body) <= set("9VP")
    digits = body.count("9")
    scale = len(body.split("V")[1]) if "V" in body else 0
    length = len(body.replace("V", "")) if numeric else len(expanded.replace("V", ""))
    return numeric, signed, digits, scale, length


def _size(item):
    if item.children:
        return sum(c.size * c.occurs for c in item.children if not c.redefines)
    numeric, signed, digits, scale, length = _pic_info(item.pic)
    if item.usage in ("COMP", "BINARY", "COMP-5", "COMP-4"):
        return 2 if digits <= 4 else 4 if digits <= 9 else 8
    if item.usage == "COMP-3":
        return digits // 2 + 1
    return length


def parse(text):
    """Return the list of 01-level Items in the copybook text, with offsets resolved."""
    roots, stack = [], []
    for st in _statements(text):
        m = re.match(r"(\d\d?)\s+([A-Z0-9-]+)?(.*)$", st, re.I | re.S)
        if not m:
            continue
        level = int(m.group(1))
        if level in (66, 88):
            continue
        name = (m.group(2) or "FILLER").upper()
        rest = " " + (m.group(3) or "") + " "
        if name in ("PIC", "PICTURE", "COMP", "COMP-3", "VALUE"):
            rest = f" {name} {rest}"
            name = "FILLER"
        item = Item(level, name)
        pm = re.search(r"\sPIC(?:TURE)?\s+(?:IS\s+)?(\S+)", rest, re.I)
        if pm:
            item.pic = pm.group(1).rstrip(".")
        um = re.search(r"\s(COMP-3|COMP-5|COMP-4|COMPUTATIONAL-3|COMP|BINARY|PACKED-DECIMAL)\b", rest, re.I)
        if um:
            usage = um.group(1).upper()
            item.usage = {"COMPUTATIONAL-3": "COMP-3", "PACKED-DECIMAL": "COMP-3"}.get(usage, usage)
        om = re.search(r"\sOCCURS\s+(\d+)", rest, re.I)
        if om:
            item.occurs = int(om.group(1))
        rm = re.search(r"\sREDEFINES\s+([A-Z0-9-]+)", rest, re.I)
        if rm:
            item.redefines = rm.group(1).upper()
        while stack and stack[-1].level >= level:
            stack.pop()
        if stack:
            stack[-1].children.append(item)
        else:
            roots.append(item)
        stack.append(item)

    def resolve(item, offset, siblings):
        if item.redefines:
            target = next(s for s in siblings if s.name == item.redefines)
            offset = target.offset
        item.offset = offset
        pos = offset
        for c in item.children:
            resolve(c, pos, item.children)
            if not c.redefines:
                pos += c.size * c.occurs
        item.size = _size(item)
        return offset + item.size * item.occurs

    for r in roots:
        resolve(r, 0, roots)
    return roots


def leaves(item, shift=0, suffix=""):
    """Flatten an Item into Leaves; OCCURS entries get a (n) suffix, REDEFINES are kept."""
    out = []
    for i in range(item.occurs):
        at = shift + i * item.size
        sub = f"{suffix}({i + 1})" if item.occurs > 1 else suffix
        if item.children:
            for c in item.children:
                out += leaves(c, at, sub)
            continue
        numeric, signed, digits, scale, _ = _pic_info(item.pic)
        kind = "text"
        if numeric:
            kind = {"COMP-3": "comp3", "COMP": "comp", "BINARY": "comp", "COMP-5": "comp",
                    "COMP-4": "comp"}.get(item.usage, "num")
        out.append(Leaf(item.name + sub, item.offset + at, item.size, kind, digits, scale, signed))
    return out


def decode_leaf(leaf, raw):
    if leaf.kind == "text":
        return raw.decode("latin-1")
    if leaf.kind == "comp":
        return str(Decimal(int.from_bytes(raw, "big", signed=leaf.signed)).scaleb(-leaf.scale))
    if leaf.kind == "comp3":
        digits = raw.hex()
        sign = -1 if digits[-1] in "bd" else 1
        return str((sign * Decimal(int(digits[:-1] or "0"))).scaleb(-leaf.scale))
    text = raw.decode("latin-1")
    if not text.strip(" \x00") or not re.fullmatch(r"[0-9]*[0-9{}A-R]", text):
        return text
    sign, last = 1, text[-1]
    if last in POS_OVERPUNCH:
        last = str(POS_OVERPUNCH.index(last))
    elif last in NEG_OVERPUNCH:
        last, sign = str(NEG_OVERPUNCH.index(last)), -1
    value = sign * Decimal(int(text[:-1] + last))
    return f"{value.scaleb(-leaf.scale):.{leaf.scale}f}"


def encode_leaf(leaf, value):
    if leaf.kind == "text":
        return str(value).encode("latin-1")[:leaf.size].ljust(leaf.size, b" ")
    d = Decimal(str(value)).scaleb(leaf.scale).to_integral_value()
    if leaf.kind == "comp":
        return int(d).to_bytes(leaf.size, "big", signed=leaf.signed)
    if leaf.kind == "comp3":
        digits = str(abs(int(d))).rjust(leaf.size * 2 - 1, "0")
        sign = "d" if d < 0 else ("c" if leaf.signed else "f")
        return bytes.fromhex(digits + sign)
    digits = str(abs(int(d))).rjust(leaf.size, "0")[-leaf.size:]
    if leaf.signed:
        table = NEG_OVERPUNCH if d < 0 else POS_OVERPUNCH
        digits = digits[:-1] + table[int(digits[-1])]
    return digits.encode("latin-1")


class Layout:
    """A record layout: ordered leaves of one 01-level item (REDEFINES dropped)."""

    def __init__(self, item, keep_redefines=False):
        self.name = item.name
        self.size = item.size
        all_leaves = leaves(item)
        if keep_redefines:
            self.leaves = all_leaves
        else:
            spans, kept = [], []
            for lf in all_leaves:
                if any(lf.offset < e and lf.offset + lf.size > s for s, e in spans):
                    continue
                spans.append((lf.offset, lf.offset + lf.size))
                kept.append(lf)
            self.leaves = kept
        self.by_name = {lf.name: lf for lf in self.leaves}

    def decode(self, raw):
        raw = raw.ljust(self.size, b" ")
        return {lf.name: decode_leaf(lf, raw[lf.offset:lf.offset + lf.size])
                for lf in self.leaves if lf.name != "FILLER"}

    def encode(self, values, base=None):
        buf = bytearray(base if base is not None else b" " * self.size)
        for name, value in values.items():
            lf = self.by_name[name]
            buf[lf.offset:lf.offset + lf.size] = encode_leaf(lf, value)
        return bytes(buf)


def load_layout(path, record=None, keep_redefines=False):
    with open(path, encoding="latin-1") as f:
        roots = parse(f.read())
    item = next(r for r in roots if record is None or r.name == record) if record else roots[0]
    return Layout(item, keep_redefines)
