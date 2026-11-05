"""Minimal COBOL copybook parser: levels, PIC/USAGE, OCCURS, REDEFINES, 88-levels, byte offsets."""
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional


@dataclass
class Field:
    level: int
    name: str
    pic: Optional[str] = None
    usage: str = "DISPLAY"
    occurs: int = 1
    redefines: Optional[str] = None
    values: list = field(default_factory=list)
    conditions: list = field(default_factory=list)
    children: list = field(default_factory=list)
    offset: int = 0
    size: int = 0
    digits: int = 0
    scale: int = 0
    signed: bool = False
    kind: str = "group"

    @property
    def is_elementary(self):
        return self.pic is not None


def _expand(pic: str) -> str:
    return re.sub(r"([9XAV])\((\d+)\)", lambda m: m.group(1) * int(m.group(2)), pic)


def analyse_pic(pic: str, usage: str):
    p = _expand(pic.upper())
    signed = p.startswith("S")
    p = p.lstrip("S")
    if set(p) <= set("X"):
        return "char", len(p), 0, 0, False
    if set(p) <= set("9V"):
        int_digits = p.split("V")[0].count("9")
        scale = p.split("V")[1].count("9") if "V" in p else 0
        digits = int_digits + scale
        if usage == "COMP-3":
            size = digits // 2 + 1
        elif usage in ("COMP", "COMP-4", "BINARY"):
            size = 2 if digits <= 4 else 4 if digits <= 9 else 8
        else:
            size = digits
        return "numeric", size, digits, scale, signed
    return "edited", len(p), 0, 0, False


def _source_lines(text: str):
    for raw in text.splitlines():
        raw = raw.expandtabs(8)
        if len(raw) < 7 or raw[6] in "*/":
            continue
        yield raw[6:72]


def parse(path) -> list:
    text = Path(path).read_text(errors="replace")
    body = " ".join(l.strip() for l in _source_lines(text))
    stmts = [s.strip() for s in re.split(r"\.(?:\s+|$)", body) if s.strip()]
    roots, stack = [], []
    last = None
    for st in stmts:
        m = re.match(r"(\d+)\s+(FILLER|[A-Z0-9-]+)\s*(.*)", st)
        if not m:
            continue
        level, name, rest = int(m.group(1)), m.group(2), m.group(3)
        if level == 88:
            vals = re.findall(r"'([^']*)'|(\d+)", rest.split("VALUE", 1)[-1])
            if last is not None:
                last.conditions.append({"name": name, "values": [a or b for a, b in vals]})
            continue
        f = Field(level=level, name=name)
        pm = re.search(r"\bPIC(?:TURE)?\s+(?:IS\s+)?(\S+)", rest)
        if pm:
            f.pic = pm.group(1)
        um = re.search(r"\b(COMP-3|COMP-4|COMP|BINARY|PACKED-DECIMAL)\b", rest)
        if um:
            f.usage = "COMP-3" if um.group(1) == "PACKED-DECIMAL" else um.group(1)
        om = re.search(r"\bOCCURS\s+(\d+)", rest)
        if om:
            f.occurs = int(om.group(1))
        rm = re.search(r"\bREDEFINES\s+([A-Z0-9-]+)", rest)
        if rm:
            f.redefines = rm.group(1)
        vm = re.search(r"\bVALUE\s+(?:IS\s+)?('([^']*)'|\S+)", rest)
        if vm:
            f.values.append(vm.group(2) if vm.group(2) is not None else vm.group(1))
        while stack and stack[-1].level >= level:
            stack.pop()
        (stack[-1].children if stack else roots).append(f)
        stack.append(f)
        last = f
    for r in roots:
        _layout(r, 0)
    return roots


def _layout(f: Field, offset: int) -> int:
    f.offset = offset
    if f.is_elementary:
        f.kind, f.size, f.digits, f.scale, f.signed = (lambda a: (a[0], a[1], a[2], a[3], a[4]))(
            analyse_pic(f.pic, f.usage))
        return offset + f.size * f.occurs
    cur = offset
    for c in f.children:
        if c.redefines:
            target = next((x for x in f.children if x.name == c.redefines), None)
            _layout(c, target.offset if target else cur)
        else:
            cur = _layout(c, cur)
    f.size = max((c.offset + c.size * c.occurs for c in f.children), default=offset) - offset
    return offset + f.size * f.occurs


def flatten(f: Field, prefix=""):
    """Elementary fields in storage order (REDEFINES alternatives included, marked)."""
    out = []
    for c in f.children:
        if c.is_elementary:
            out.append(c)
        else:
            out.extend(flatten(c))
    return out


def record(path, name):
    for r in parse(path):
        if r.name == name:
            return r
    raise KeyError(f"{name} not in {path}")
