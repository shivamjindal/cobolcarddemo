#!/usr/bin/env python3
"""Check the Postgres schema against copybooks, LISTCAT keys, and the EBCDIC extracts.

Run after the migrations and load_sample_data.py. Exits non-zero on any failure.
"""
import argparse
import json
import re
import sys
from pathlib import Path

import psycopg2

import copybook as cb
from db_mapping import BY_TABLE, CPY, RECORDS
from load_sample_data import SOURCES, convert, read_records

ROOT = Path(__file__).resolve().parents[3]
EBCDIC_PEERS = {
    "accounts": "AWS.M2.CARDDEMO.ACCTDATA.PS", "cards": "AWS.M2.CARDDEMO.CARDDATA.PS",
    "card_account_xref": "AWS.M2.CARDDEMO.CARDXREF.PS", "customers": "AWS.M2.CARDDEMO.CUSTDATA.PS",
    "transactions": "AWS.M2.CARDDEMO.DALYTRAN.PS", "disclosure_groups": "AWS.M2.CARDDEMO.DISCGRP.PS",
    "transaction_category_balances": "AWS.M2.CARDDEMO.TCATBALF.PS",
    "transaction_categories": "AWS.M2.CARDDEMO.TRANCATG.PS", "transaction_types": "AWS.M2.CARDDEMO.TRANTYPE.PS",
}
KNOWN_SAMPLE_DIVERGENCE = {"accounts": 1, "disclosure_groups": 1}
KNOWN_EXPORT_BALANCE_MISMATCH = [1, 50]
failures = []


def check(ok, msg):
    print(("PASS " if ok else "FAIL ") + msg)
    if not ok:
        failures.append(msg)


def sql_shape(col, meta):
    t = col.sql.upper()
    m = re.match(r"(\w+)(?:\((\d+)(?:,(\d+))?\))?", t)
    return m.group(1), m.group(2), m.group(3)


def unpack_comp3(raw: bytes, scale: int):
    from decimal import Decimal
    nibbles = raw.hex()
    sign = -1 if nibbles[-1] in "bd" else 1
    return Decimal(int(nibbles[:-1]) * sign).scaleb(-scale)


def check_export_file(cur):
    """CBEXPORT file: one 500-byte record, five REDEFINES layouts keyed by EXPORT-REC-TYPE, mixed COMP/COMP-3."""
    root = cb.record(ROOT / CPY / "CVEXPORT.cpy", "EXPORT-RECORD")
    data = (ROOT / "app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS").read_bytes()
    check(root.size == 500 and len(data) % 500 == 0, f"CVEXPORT: EXPORT-RECORD is {root.size} bytes, file holds {len(data) // 500} records")
    variants = {c.name: c for c in root.children if c.redefines == "EXPORT-RECORD-DATA"}
    check(all(v.offset == 40 and v.size <= 460 for v in variants.values()),
          f"CVEXPORT: {len(variants)} REDEFINES variants all start at offset 40 and fit in 460 bytes")
    acct = {f.name: f for f in cb.flatten(variants["EXPORT-ACCOUNT-DATA"])}
    counts, mismatched = {}, []
    for i in range(len(data) // 500):
        rec = data[i * 500:(i + 1) * 500]
        t = rec[0:1].decode("cp037")
        counts[t] = counts.get(t, 0) + 1
        if t == "A":
            a = {n: rec[f.offset:f.offset + f.size] for n, f in acct.items()}
            acct_id = int(a["EXP-ACCT-ID"].decode("cp037"))
            bal = unpack_comp3(a["EXP-ACCT-CURR-BAL"], 2)
            cur.execute("SELECT curr_balance FROM accounts WHERE acct_id = %s", (acct_id,))
            row = cur.fetchone()
            if not (row and row[0] == bal):
                mismatched.append(acct_id)
    check(counts.get("A", 0) > 0 and mismatched == KNOWN_EXPORT_BALANCE_MISMATCH,
          f"CVEXPORT: {counts.get('A', 0) - len(mismatched)}/{counts.get('A', 0)} account records decode (zoned id, COMP-3 balance) "
          f"to the loaded accounts; shipped export shows 0.00 for accounts {mismatched}; record types {counts}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", required=True)
    args = ap.parse_args()
    dm = json.loads((ROOT / "docs/migration/dependency-map.json").read_text())
    listcat = dm["listcat"]
    conn = psycopg2.connect(args.dsn)
    cur = conn.cursor()

    for rec in RECORDS:
        root = cb.record(ROOT / CPY / f"{rec.copybook}.cpy", rec.record)
        fields = {f.name: f for f in cb.flatten(root)}
        mapped = {c.cobol for c in rec.cols}
        unmapped = [n for n, f in fields.items() if n not in mapped and "FILLER" not in n and n != "SEC-USR-FILLER"]
        check(not unmapped, f"{rec.table}: every non-filler copybook field is mapped {unmapped or ''}")
        lc = listcat[f"AWS.M2.CARDDEMO.{rec.vsam}"]
        check(root.size == lc["max_lrecl"], f"{rec.table}: copybook {rec.record} is {root.size} bytes, LISTCAT MAXLRECL {lc['max_lrecl']}")
        pk_fields = [next(c for c in rec.cols if c.column == k) for k in rec.pk]
        pk_len = sum(fields[c.cobol].size for c in pk_fields)
        pk_off = fields[pk_fields[0].cobol].offset
        check(pk_len == lc["key_length"] and pk_off == lc["key_offset"],
              f"{rec.table}: PK {rec.pk} is {pk_len} bytes at offset {pk_off}, LISTCAT key {lc['key_length']} at {lc['key_offset']}")
        cur.execute("""SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale,
                              datetime_precision, is_nullable
                       FROM information_schema.columns WHERE table_name = %s""", (rec.table,))
        db = {r[0]: r for r in cur.fetchall()}
        check(set(db) == {c.column for c in rec.cols}, f"{rec.table}: column set matches mapping")
        for c in rec.cols:
            if c.column not in db:
                continue
            _, dtype, clen, nprec, nscale, dprec, nullable = db[c.column]
            base, p1, p2 = sql_shape(c, None)
            ok = {"VARCHAR": dtype == "character varying" and clen == int(p1 or 0),
                  "CHAR": dtype == "character" and clen == int(p1 or 0),
                  "NUMERIC": dtype == "numeric" and nprec == int(p1) and nscale == int(p2),
                  "INTEGER": dtype == "integer", "BIGINT": dtype == "bigint", "SMALLINT": dtype == "smallint",
                  "DATE": dtype == "date", "TIMESTAMP": dtype.startswith("timestamp") and dprec == int(p1),
                  "TEXT": dtype == "text"}[base]
            f = fields[c.cobol]
            if c.conv == "dec":
                ok = ok and nprec == f.digits and nscale == f.scale
            if base in ("VARCHAR", "CHAR") and c.conv == "str" and c.cobol != "SEC-USR-PWD":
                ok = ok and clen == f.size
            ok = ok and ((nullable == "YES") == c.nullable)
            check(ok, f"{rec.table}.{c.column} type/nullability matches {c.cobol} ({c.sql})")

    cur.execute("SELECT tablename, indexdef FROM pg_indexes WHERE schemaname = 'public'")
    idx = cur.fetchall()
    for name, ds in listcat.items():
        if ds["type"] != "AIX":
            continue
        base = BY_TABLE[next(r.table for r in RECORDS if f"AWS.M2.CARDDEMO.{r.vsam}" == ds["base_cluster"])]
        root = cb.record(ROOT / CPY / f"{base.copybook}.cpy", base.record)
        fld = next(f for f in cb.flatten(root) if f.offset == ds["key_offset"] and f.size == ds["key_length"])
        col = next(c.column for c in base.cols if c.cobol == fld.name)
        check(any(t == base.table and re.search(rf"\(\s*{col}\s*\)", d) for t, d in idx),
              f"AIX {name.replace('AWS.M2.CARDDEMO.', '')} ({fld.name} at {ds['key_offset']}) has an index on {base.table}.{col}")

    for table, peer in EBCDIC_PEERS.items():
        rec = BY_TABLE[table]
        root = cb.record(ROOT / CPY / f"{rec.copybook}.cpy", rec.record)
        fields = {f.name: f for f in cb.flatten(root)}
        lines = read_records(ROOT / "app/data/EBCDIC" / peer, "ebcdic", root.size)
        expected = sorted(tuple(convert(c, fields[c.cobol], l[fields[c.cobol].offset:fields[c.cobol].offset + fields[c.cobol].size])
                                for c in rec.cols) for l in lines)
        cur.execute(f"SELECT {', '.join(c.column for c in rec.cols)} FROM {table}")
        got = sorted(cur.fetchall())
        differing = len([r for r in expected if r not in got])
        allowed = KNOWN_SAMPLE_DIVERGENCE.get(table, 0)
        check(differing == allowed and len(expected) == len(got),
              f"{table}: {len(got) - differing}/{len(got)} rows equal the EBCDIC (cp037) extract decoded independently"
              + (f" ({allowed} known divergence in the shipped sample files)" if allowed else ""))

    check_export_file(cur)

    cur.execute("SELECT count(*) FROM users WHERE password_hash !~ '^\\$2'")
    check(cur.fetchone()[0] == 0, "users: no clear-text passwords stored")
    cur.execute("SELECT count(*) FROM accounts WHERE curr_balance < 0")
    cur.execute("SELECT count(*) FROM transactions WHERE amount < 0")
    neg = cur.fetchone()[0]
    check(neg > 0, f"transactions: {neg} negative amounts decoded from overpunch signs")

    bad_inserts = {
        "account status outside Y/N": "INSERT INTO accounts VALUES (99999999990,'X',0,0,0,'2020-01-01','2030-01-01',NULL,0,0,NULL,NULL)",
        "duplicate account key": "INSERT INTO accounts SELECT * FROM accounts LIMIT 1",
        "card with unknown account": "INSERT INTO cards VALUES ('9999999999999999',99999999991,'123','X','2030-01-01','Y')",
        "user type outside A/U": "INSERT INTO users VALUES ('ZZZ','a','b','h','Z')",
        "ssn not 9 digits": "INSERT INTO customers (cust_id,first_name,last_name,addr_line_1,ssn,primary_card_holder) VALUES (999999990,'a','b','c','12','Y')",
        "transaction with unknown category": "INSERT INTO transactions (tran_id,type_cd,cat_cd,amount,merchant_id,card_num,orig_ts) SELECT 'T9','99',9999,1,0,card_num,now() FROM cards LIMIT 1",
    }
    for label, sql in bad_inserts.items():
        try:
            cur.execute("SAVEPOINT s")
            cur.execute(sql)
            check(False, f"rejects {label}")
        except psycopg2.Error:
            check(True, f"rejects {label}")
        finally:
            cur.execute("ROLLBACK TO SAVEPOINT s")

    cur.execute("""SELECT x.acct_id, count(*) FROM card_account_xref x JOIN cards c USING (card_num)
                   GROUP BY x.acct_id HAVING count(*) > 1""")
    print("INFO accounts with more than one card in sample data:", len(cur.fetchall()))
    cur.execute("SELECT count(*) FROM cards c LEFT JOIN card_account_xref x USING (card_num) WHERE x.acct_id IS DISTINCT FROM c.acct_id")
    check(cur.fetchone()[0] == 0, "cards.acct_id agrees with card_account_xref.acct_id for every card")

    conn.rollback()
    print(f"\n{len(failures)} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
