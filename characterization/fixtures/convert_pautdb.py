"""Convert the DBPAUTP0 unload into the harness seeds pautsum0.hex and pautdtl1.hex.

The input is a DFSURGU0 unload with RDW records. A segment record carries the level at
byte 4, the prefix length (35) at bytes 6-7, the data length at bytes 8-9, the EBCDIC
segment name at bytes 10-17, and the segment data from byte 39 (offsets include the RDW).
Header and trailer records have another prefix length. A detail's parent is the summary before it.

Text and DISPLAY fields are converted from cp037 to latin-1 using the copybook layouts,
like app/data/ASCII; COMP and COMP-3 bytes are kept as they are.

    python3 characterization/fixtures/convert_pautdb.py
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "harness"))
from copybook import load_layout  # noqa: E402

REPO = HERE.parents[1]
UNLOAD = REPO / "app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat"
SUMMARY = load_layout(REPO / "app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy")
DETAIL = load_layout(REPO / "app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy")


def to_ascii(layout, raw):
    buf = bytearray(raw)
    for lf in layout.leaves:
        if lf.kind in ("text", "num"):
            seg = raw[lf.offset:lf.offset + lf.size]
            buf[lf.offset:lf.offset + lf.size] = seg.decode("cp037").encode("latin-1")
    return bytes(buf)


def segments(raw):
    i = 0
    while i + 4 <= len(raw):
        n = int.from_bytes(raw[i:i + 2], "big")
        rec = raw[i:i + n]
        i += n
        if n < 39 or rec[6:8] != b"\x00\x23":
            continue
        name = rec[10:18].decode("cp037").strip()
        length = int.from_bytes(rec[8:10], "big")
        yield name, rec[39:39 + length]


def main():
    sums, dtls, parent = [], [], None
    for name, data in segments(UNLOAD.read_bytes()):
        if name == "PAUTSUM0":
            seg = to_ascii(SUMMARY, data)
            parent = seg[:6]
            sums.append(seg)
        elif name == "PAUTDTL1":
            dtls.append(parent + to_ascii(DETAIL, data))
        else:
            raise ValueError(f"unexpected segment {name}")
    sums.sort(key=lambda r: r[:6])
    dtls.sort(key=lambda r: r[:14])
    seed = HERE / "seed"
    (seed / "pautsum0.hex").write_text("".join(r.hex() + "\n" for r in sums))
    (seed / "pautdtl1.hex").write_text("".join(r.hex() + "\n" for r in dtls))
    print(f"{len(sums)} summaries, {len(dtls)} details")


if __name__ == "__main__":
    main()
