"""The Db2 tables of the transaction-type extension, on Postgres, for one run directory.

The run directory holds each table as a dataset of row images (fixtures/db2/*.cpy), so seeds,
case setup and the per-step diff work as they do for VSAM files. reset() rebuilds the tables
from the extension's shipped DDL and loads the row images; dump() writes the tables back.

  python3 characterization/harness/db2.py seeds   # regenerate the seeds from the load cards
"""
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
EXT = REPO / "app" / "app-transaction-type-db2"
DATABASE = os.environ.get("CARDDEMO_LEGACY_DB2", "carddemo_legacy_db2")
SERVER = "host=localhost user=carddemo password=carddemo"
CONNINFO = f"{SERVER} dbname={DATABASE}"

# dataset -> (table, key columns with widths, varchar column)
TABLES = {
    "TRANSACTION_TYPE": ("carddemo.transaction_type", [("tr_type", 2)], "tr_description"),
    "TRANSACTION_TYPE_CATEGORY": ("carddemo.transaction_type_category",
                                  [("trc_type_code", 2), ("trc_type_category", 4)], "trc_cat_data"),
}
LOAD_CARDS = {"TRANSACTION_TYPE": "DB2LTTYP.ctl", "TRANSACTION_TYPE_CATEGORY": "DB2LTCAT.ctl"}


def psql(sql, dbname=DATABASE, rows=False):
    cmd = ["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", f"{SERVER} dbname={dbname}"] + (["-At"] if rows else [])
    proc = subprocess.run(cmd, input=sql.encode("latin-1"), capture_output=True,
                          env=dict(os.environ, PGCLIENTENCODING="SQL_ASCII"))
    if proc.returncode:
        raise RuntimeError(f"psql: {proc.stderr.decode('latin-1')}")
    return proc.stdout.decode("latin-1")


def ensure_database():
    if not psql(f"SELECT 1 FROM pg_database WHERE datname = '{DATABASE}'", "postgres", rows=True).strip():
        psql(f"CREATE DATABASE {DATABASE} ENCODING 'SQL_ASCII' LC_COLLATE 'C' LC_CTYPE 'C' "
             "TEMPLATE template0", "postgres")


def shipped_ddl():
    """The extension's DDL members, with the two Db2-only spellings rewritten for Postgres."""
    out = []
    for member in ("TRNTYPE", "TRNTYCAT", "XTRNTYPE", "XTRNTYCAT"):
        text = (EXT / "ddl" / f"{member}.ddl").read_text(encoding="latin-1")
        text = re.sub(r"FOREIGN KEY (\w+) \(", r"CONSTRAINT \1 FOREIGN KEY (", text)
        text = re.sub(r"INDEX CARDDEMO\.", "INDEX ", text)
        text = re.sub(r"\s+ERASE NO|\s+CLOSE NO", "", text)
        out.append(text.strip().rstrip(";") + ";")
    return "\n".join(out)


def _quote(s):
    return "'" + s.replace("'", "''") + "'"


def _rows(name, recs):
    _, keys, _ = TABLES[name]
    width = sum(w for _, w in keys)
    for rec in recs:
        text = rec.decode("latin-1")
        n = int(text[width:width + 2]) if text[width:width + 2].strip("0 ") else 0
        data = text[width + 2:]
        if n == 0:
            n = len(data.rstrip())
        cols, at = [], 0
        for _, w in keys:
            cols.append(text[at:at + w])
            at += w
        yield cols + [data[:n]]


def reset(data, run_dir):
    ensure_database()
    sql = ["DROP SCHEMA IF EXISTS carddemo CASCADE; DROP SCHEMA IF EXISTS sysibm CASCADE;",
           "CREATE SCHEMA carddemo; CREATE SCHEMA sysibm;",
           "CREATE TABLE sysibm.sysdummy1 (ibmreqd char(1)); INSERT INTO sysibm.sysdummy1 VALUES ('Y');",
           shipped_ddl()]
    for name, (table, keys, varchar) in TABLES.items():
        cols = ", ".join([k for k, _ in keys] + [varchar])
        for row in _rows(name, data.read(run_dir, name) or []):
            sql.append(f"INSERT INTO {table} ({cols}) VALUES ({', '.join(map(_quote, row))});")
    psql("\n".join(sql))


def dump(data, run_dir):
    for name, (table, keys, varchar) in TABLES.items():
        image = " || ".join([f"rpad({k}, {w})" for k, w in keys] +
                            [f"lpad(length({varchar})::text, 2, '0')", varchar])
        order = ", ".join(k for k, _ in keys)
        out = psql(f"SELECT {image} FROM {table} ORDER BY {order};", rows=True)
        lines = [l.rstrip(" ") for l in out.split("\n") if l]
        (Path(run_dir) / f"{name}.dat").write_bytes("".join(l + "\n" for l in lines).encode("latin-1"))


def load_card_rows(card):
    """The literal rows of a DB2LT* load card: SELECT 'a','b',... FROM ..."""
    text = (EXT / "ctl" / card).read_text(encoding="latin-1")
    return [re.findall(r"'([^']*)'", m) for m in re.findall(r"SELECT\s+('.*?)\s+FROM", text)]


def write_seeds():
    seed = REPO / "characterization" / "fixtures" / "seed"
    for name, card in LOAD_CARDS.items():
        lines = []
        for row in load_card_rows(card):
            *keys, text = row
            lines.append("".join(keys) + f"{len(text):02d}" + text)
        (seed / f"db2-{name.lower().replace('_', '-')}.txt").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    if sys.argv[1:] == ["seeds"]:
        write_seeds()
    else:
        sys.exit(__doc__)
