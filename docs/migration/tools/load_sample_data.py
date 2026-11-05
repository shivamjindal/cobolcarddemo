#!/usr/bin/env python3
"""Load the CardDemo sample extracts (app/data) into Postgres using the copybook layouts.

Fixed-width ASCII extracts are sliced by copybook offset; USRSEC comes from the EBCDIC (cp037) file.
Passwords are bcrypt-hashed with pgcrypto; the legacy clear text is never stored.

Usage: load_sample_data.py --dsn "host=/tmp port=55432 dbname=carddemo"
"""
import argparse
import datetime as dt
import sys
from pathlib import Path

import psycopg2
from psycopg2.extras import execute_values

import copybook as cb
from db_mapping import BY_TABLE, CPY, LOAD_ORDER, decode_zoned

ROOT = Path(__file__).resolve().parents[3]
ASCII = ROOT / "app" / "data" / "ASCII"
EBCDIC = ROOT / "app" / "data" / "EBCDIC"

SOURCES = {
    "transaction_types": (ASCII / "trantype.txt", "ascii"),
    "transaction_categories": (ASCII / "trancatg.txt", "ascii"),
    "disclosure_groups": (ASCII / "discgrp.txt", "ascii"),
    "customers": (ASCII / "custdata.txt", "ascii"),
    "accounts": (ASCII / "acctdata.txt", "ascii"),
    "cards": (ASCII / "carddata.txt", "ascii"),
    "card_account_xref": (ASCII / "cardxref.txt", "ascii"),
    "users": (EBCDIC / "AWS.M2.CARDDEMO.USRSEC.PS", "ebcdic"),
    "transactions": (ASCII / "dailytran.txt", "ascii"),
    "transaction_category_balances": (ASCII / "tcatbal.txt", "ascii"),
}


def read_records(path: Path, enc: str, lrecl: int):
    data = path.read_bytes()
    if enc == "ebcdic":
        text = data.decode("cp037")
        return [text[i:i + lrecl] for i in range(0, len(text), lrecl)]
    text = data.decode("latin-1")
    rows = [l.rstrip("\r") for l in text.split("\n") if l.strip("\r")]
    return [r.ljust(lrecl) for r in rows]


def convert(col, field, raw):
    if col.conv == "str":
        v = raw.rstrip()
        return v if v or not col.nullable else None
    if not raw.strip():
        if col.nullable:
            return None
        raise ValueError(f"{col.cobol}: blank in NOT NULL column")
    if col.conv == "int":
        return int(raw)
    if col.conv == "dec":
        return decode_zoned(raw, field.scale)
    if col.conv == "date":
        return dt.date.fromisoformat(raw)
    if col.conv == "ts":
        return dt.datetime.strptime(raw.strip(), "%Y-%m-%d %H:%M:%S.%f")
    raise ValueError(col.conv)


def load_table(cur, table):
    rec = BY_TABLE[table]
    root = cb.record(ROOT / CPY / f"{rec.copybook}.cpy", rec.record)
    fields = {f.name: f for f in cb.flatten(root)}
    path, enc = SOURCES[table]
    rows, errors = [], []
    for n, line in enumerate(read_records(path, enc, root.size), 1):
        try:
            row = []
            for col in rec.cols:
                f = fields[col.cobol]
                row.append(convert(col, f, line[f.offset:f.offset + f.size]))
            rows.append(tuple(row))
        except Exception as e:
            errors.append(f"{path.name} record {n}: {e}")
    if errors:
        raise SystemExit("\n".join(errors[:10]))
    names = [c.column for c in rec.cols]
    if table == "users":
        pw = names.index("password_hash")
        hashed = []
        for r in rows:
            cur.execute("SELECT crypt(%s, gen_salt('bf', 8))", (r[pw],))
            hashed.append(r[:pw] + (cur.fetchone()[0],) + r[pw + 1:])
        rows = hashed
    execute_values(cur, f"INSERT INTO {table} ({', '.join(names)}) VALUES %s", rows)
    return len(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", required=True)
    args = ap.parse_args()
    with psycopg2.connect(args.dsn) as conn, conn.cursor() as cur:
        cur.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")
        for table in LOAD_ORDER:
            print(f"{table:34}{load_table(cur, table):>5} rows")
        cur.execute("SELECT setval('transaction_id_seq', COALESCE(MAX(tran_id::bigint), 0) + 1, false) FROM transactions")
        print("transaction_id_seq next value:", cur.fetchone()[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
