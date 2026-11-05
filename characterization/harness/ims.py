"""Read the IMS PSB and DBD sources of the authorization module into the PSB library
the DL/I stub loads (runtime/dli.c, env DLI_PSBLIB). One line per PSB and per PCB:

  PSB  <name> <cmpat 0|1>          batch regions get an I/O PCB first when CMPAT=YES
  DB   <dbd> <procopt> <keylen>
  GSAM <dbd> <output DD> <record length>   from the GSAM DBD's DATASET DD2= and RECORD=
"""
import re
from pathlib import Path

IMS = Path(__file__).resolve().parents[2] / "app" / "app-authorization-ims-db2-mq" / "ims"


def _statements(text):
    """Assembler statements with continuations joined (column 72 non-blank)."""
    out, cur = [], ""
    for line in text.splitlines():
        if line.startswith("*") or not line.strip():
            continue
        body = line[:71].rstrip()
        cont = len(line) > 71 and line[71] != " "
        cur = cur + body.strip() if cur else body
        if not cont:
            out.append(cur)
            cur = ""
    if cur:
        out.append(cur)
    return out


def _params(stmt):
    return dict(re.findall(r"(\w+)=(\([^)]*\)|[^,\s]+)", stmt))


def gsam_dbds():
    out = {}
    for path in IMS.iterdir():
        if path.suffix.upper() != ".DBD":
            continue
        stmts = _statements(path.read_text(encoding="latin-1"))
        dbd = next((_params(s) for s in stmts if re.search(r"\bDBD\b", s)), {})
        if "GSAM" not in dbd.get("ACCESS", ""):
            continue
        ds = next(_params(s) for s in stmts if re.search(r"\bDATASET\b", s))
        out[dbd["NAME"]] = (ds["DD2"], ds["RECORD"].strip("()"))
    return out


def psb_library():
    gsam = gsam_dbds()
    lines = []
    for path in sorted(IMS.iterdir()):
        if path.suffix.upper() != ".PSB":
            continue
        pcbs, gen = [], {}
        for s in _statements(path.read_text(encoding="latin-1")):
            if re.search(r"\bPCB\b", s):
                p = _params(s)
                if p.get("TYPE") == "GSAM":
                    dd, reclen = gsam[p["DBDNAME"]]
                    pcbs.append(f"GSAM {p['DBDNAME']} {dd} {reclen}")
                else:
                    pcbs.append(f"DB {p['DBDNAME']} {p['PROCOPT']} {p['KEYLEN']}")
            elif re.search(r"\bPSBGEN\b", s):
                gen = _params(s)
        lines.append(f"PSB {gen['PSBNAME']} {1 if gen.get('CMPAT') == 'YES' else 0}")
        lines += pcbs
    return "\n".join(lines) + "\n"


def write_library(path):
    text = psb_library()
    path = Path(path)
    if not path.exists() or path.read_text() != text:
        path.write_text(text)
    return path


if __name__ == "__main__":
    print(psb_library(), end="")
