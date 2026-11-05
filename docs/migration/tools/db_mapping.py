"""Single source of truth: copybook field -> Postgres column. Used by the loader, checker and doc generator.

Converters: str (strip trailing blanks), int, dec (zoned decimal, trailing overpunch sign),
date (YYYY-MM-DD), ts (YYYY-MM-DD HH:MM:SS.ffffff).
"""
from dataclasses import dataclass

CPY = "app/cpy/"


@dataclass(frozen=True)
class Col:
    cobol: str
    column: str
    sql: str
    conv: str
    nullable: bool = False
    note: str = ""


@dataclass(frozen=True)
class Rec:
    copybook: str
    record: str
    table: str
    vsam: str
    pk: tuple
    cols: tuple


RECORDS = [
    Rec("CVTRA03Y", "TRAN-TYPE-RECORD", "transaction_types", "TRANTYPE.VSAM.KSDS", ("type_cd",), (
        Col("TRAN-TYPE", "type_cd", "VARCHAR(2)", "str"),
        Col("TRAN-TYPE-DESC", "description", "VARCHAR(50)", "str"),
    )),
    Rec("CVTRA04Y", "TRAN-CAT-RECORD", "transaction_categories", "TRANCATG.VSAM.KSDS", ("type_cd", "cat_cd"), (
        Col("TRAN-TYPE-CD", "type_cd", "VARCHAR(2)", "str", note="part of KSDS key; FK to transaction_types"),
        Col("TRAN-CAT-CD", "cat_cd", "INTEGER", "int", note="PIC 9(04) display; INTEGER avoids Short in Java"),
        Col("TRAN-CAT-TYPE-DESC", "description", "VARCHAR(50)", "str"),
    )),
    Rec("CVTRA02Y", "DIS-GROUP-RECORD", "disclosure_groups", "DISCGRP.VSAM.KSDS",
        ("group_id", "type_cd", "cat_cd"), (
            Col("DIS-ACCT-GROUP-ID", "group_id", "VARCHAR(10)", "str"),
            Col("DIS-TRAN-TYPE-CD", "type_cd", "VARCHAR(2)", "str"),
            Col("DIS-TRAN-CAT-CD", "cat_cd", "INTEGER", "int"),
            Col("DIS-INT-RATE", "interest_rate", "NUMERIC(6,2)", "dec", note="S9(4)V99; annual percent"),
        )),
    Rec("CVCUS01Y", "CUSTOMER-RECORD", "customers", "CUSTDATA.VSAM.KSDS", ("cust_id",), (
        Col("CUST-ID", "cust_id", "INTEGER", "int", note="9 digits fit INTEGER; zero-pad at the edge"),
        Col("CUST-FIRST-NAME", "first_name", "VARCHAR(25)", "str"),
        Col("CUST-MIDDLE-NAME", "middle_name", "VARCHAR(25)", "str", True),
        Col("CUST-LAST-NAME", "last_name", "VARCHAR(25)", "str"),
        Col("CUST-ADDR-LINE-1", "addr_line_1", "VARCHAR(50)", "str"),
        Col("CUST-ADDR-LINE-2", "addr_line_2", "VARCHAR(50)", "str", True),
        Col("CUST-ADDR-LINE-3", "addr_line_3", "VARCHAR(50)", "str", True, "holds the city in the sample data"),
        Col("CUST-ADDR-STATE-CD", "addr_state_cd", "VARCHAR(2)", "str", True),
        Col("CUST-ADDR-COUNTRY-CD", "addr_country_cd", "VARCHAR(3)", "str", True),
        Col("CUST-ADDR-ZIP", "addr_zip", "VARCHAR(10)", "str", True),
        Col("CUST-PHONE-NUM-1", "phone_1", "VARCHAR(15)", "str", True),
        Col("CUST-PHONE-NUM-2", "phone_2", "VARCHAR(15)", "str", True),
        Col("CUST-SSN", "ssn", "CHAR(9)", "str", note="PIC 9(09) but an identifier: keep leading zeros, never do math. PII"),
        Col("CUST-GOVT-ISSUED-ID", "govt_issued_id", "VARCHAR(20)", "str", True, "PII"),
        Col("CUST-DOB-YYYY-MM-DD", "date_of_birth", "DATE", "date", True, "PII"),
        Col("CUST-EFT-ACCOUNT-ID", "eft_account_id", "VARCHAR(10)", "str", True),
        Col("CUST-PRI-CARD-HOLDER-IND", "primary_card_holder", "CHAR(1)", "str",
            note="CHECK in ('Y','N') from the Y/N edit in COACTUPC"),
        Col("CUST-FICO-CREDIT-SCORE", "fico_score", "SMALLINT", "int", True,
            note="COACTUPC edits 300..850 (88 FICO-RANGE-IS-VALID) but sample data has 274; DB checks 0..999 only"),
    )),
    Rec("CVACT01Y", "ACCOUNT-RECORD", "accounts", "ACCTDATA.VSAM.KSDS", ("acct_id",), (
        Col("ACCT-ID", "acct_id", "BIGINT", "int", note="11 digits do not fit INTEGER"),
        Col("ACCT-ACTIVE-STATUS", "active_status", "CHAR(1)", "str", note="CHECK in ('Y','N')"),
        Col("ACCT-CURR-BAL", "curr_balance", "NUMERIC(12,2)", "dec"),
        Col("ACCT-CREDIT-LIMIT", "credit_limit", "NUMERIC(12,2)", "dec"),
        Col("ACCT-CASH-CREDIT-LIMIT", "cash_credit_limit", "NUMERIC(12,2)", "dec"),
        Col("ACCT-OPEN-DATE", "open_date", "DATE", "date"),
        Col("ACCT-EXPIRAION-DATE", "expiration_date", "DATE", "date", note="copybook typo EXPIRAION fixed"),
        Col("ACCT-REISSUE-DATE", "reissue_date", "DATE", "date", True),
        Col("ACCT-CURR-CYC-CREDIT", "curr_cycle_credit", "NUMERIC(12,2)", "dec"),
        Col("ACCT-CURR-CYC-DEBIT", "curr_cycle_debit", "NUMERIC(12,2)", "dec"),
        Col("ACCT-ADDR-ZIP", "addr_zip", "VARCHAR(10)", "str", True),
        Col("ACCT-GROUP-ID", "group_id", "VARCHAR(10)", "str", True,
            "blank in sample data; joins to disclosure_groups.group_id logically (no FK: group_id is not unique there)"),
    )),
    Rec("CVACT02Y", "CARD-RECORD", "cards", "CARDDATA.VSAM.KSDS", ("card_num",), (
        Col("CARD-NUM", "card_num", "VARCHAR(16)", "str", note="PAN: leading zeros, string key"),
        Col("CARD-ACCT-ID", "acct_id", "BIGINT", "int", note="CARDDATA.VSAM.AIX (key 11 at offset 16) becomes an index"),
        Col("CARD-CVV-CD", "cvv", "CHAR(3)", "str", note="PIC 9(03): string, leading zeros. Sensitive: do not log"),
        Col("CARD-EMBOSSED-NAME", "embossed_name", "VARCHAR(50)", "str"),
        Col("CARD-EXPIRAION-DATE", "expiration_date", "DATE", "date", note="copybook typo fixed"),
        Col("CARD-ACTIVE-STATUS", "active_status", "CHAR(1)", "str", note="CHECK in ('Y','N')"),
    )),
    Rec("CVACT03Y", "CARD-XREF-RECORD", "card_account_xref", "CARDXREF.VSAM.KSDS", ("card_num",), (
        Col("XREF-CARD-NUM", "card_num", "VARCHAR(16)", "str"),
        Col("XREF-CUST-ID", "cust_id", "INTEGER", "int"),
        Col("XREF-ACCT-ID", "acct_id", "BIGINT", "int", note="CARDXREF.VSAM.AIX (key 11 at offset 25) becomes an index"),
    )),
    Rec("CSUSR01Y", "SEC-USER-DATA", "users", "USRSEC.VSAM.KSDS", ("usr_id",), (
        Col("SEC-USR-ID", "usr_id", "VARCHAR(8)", "str", note="COSGN00C upper-cases input before the key read"),
        Col("SEC-USR-FNAME", "first_name", "VARCHAR(20)", "str"),
        Col("SEC-USR-LNAME", "last_name", "VARCHAR(20)", "str"),
        Col("SEC-USR-PWD", "password_hash", "TEXT", "str",
            note="legacy value is an 8-char plaintext password; loader stores a bcrypt hash, never the clear text"),
        Col("SEC-USR-TYPE", "usr_type", "CHAR(1)", "str", note="88 CDEMO-USRTYP-ADMIN 'A', CDEMO-USRTYP-USER 'U' (COCOM01Y)"),
    )),
    Rec("CVTRA05Y", "TRAN-RECORD", "transactions", "TRANSACT.VSAM.KSDS", ("tran_id",), (
        Col("TRAN-ID", "tran_id", "VARCHAR(16)", "str", note="numeric string; legacy code mints max+1 by browsing, replaced by a sequence"),
        Col("TRAN-TYPE-CD", "type_cd", "VARCHAR(2)", "str"),
        Col("TRAN-CAT-CD", "cat_cd", "INTEGER", "int"),
        Col("TRAN-SOURCE", "source", "VARCHAR(10)", "str", True),
        Col("TRAN-DESC", "description", "VARCHAR(100)", "str", True),
        Col("TRAN-AMT", "amount", "NUMERIC(11,2)", "dec"),
        Col("TRAN-MERCHANT-ID", "merchant_id", "INTEGER", "int", note="9 digits; 0 is stored as 0"),
        Col("TRAN-MERCHANT-NAME", "merchant_name", "VARCHAR(50)", "str", True),
        Col("TRAN-MERCHANT-CITY", "merchant_city", "VARCHAR(50)", "str", True),
        Col("TRAN-MERCHANT-ZIP", "merchant_zip", "VARCHAR(10)", "str", True),
        Col("TRAN-CARD-NUM", "card_num", "VARCHAR(16)", "str"),
        Col("TRAN-ORIG-TS", "orig_ts", "TIMESTAMP(6)", "ts", note="no zone in source; stored as local wall time"),
        Col("TRAN-PROC-TS", "proc_ts", "TIMESTAMP(6)", "ts", True,
            "blank until CBTRN02C posts the row; TRANSACT.VSAM.AIX (key 26 at offset 304) is this column"),
    )),
    Rec("CVTRA01Y", "TRAN-CAT-BAL-RECORD", "transaction_category_balances", "TCATBALF.VSAM.KSDS",
        ("acct_id", "type_cd", "cat_cd"), (
            Col("TRANCAT-ACCT-ID", "acct_id", "BIGINT", "int"),
            Col("TRANCAT-TYPE-CD", "type_cd", "VARCHAR(2)", "str"),
            Col("TRANCAT-CD", "cat_cd", "INTEGER", "int"),
            Col("TRAN-CAT-BAL", "balance", "NUMERIC(11,2)", "dec"),
        )),
]

BY_TABLE = {r.table: r for r in RECORDS}
LOAD_ORDER = ["transaction_types", "transaction_categories", "disclosure_groups", "customers", "accounts", "cards",
              "card_account_xref", "users", "transactions", "transaction_category_balances"]

OVERPUNCH = {**{c: (d, 1) for d, c in enumerate("{ABCDEFGHI")}, **{c: (d, -1) for d, c in enumerate("}JKLMNOPQR")}}


def decode_zoned(raw: str, scale: int):
    """Zoned decimal with trailing overpunch sign: '00000001940{' -> 194.00 (scale 2)."""
    from decimal import Decimal
    raw = raw.strip()
    if not raw:
        raise ValueError("blank numeric")
    last = raw[-1]
    if last.isdigit():
        digits, sign = raw, 1
    elif last in OVERPUNCH:
        d, sign = OVERPUNCH[last]
        digits = raw[:-1] + str(d)
    else:
        raise ValueError(f"bad sign nibble {last!r} in {raw!r}")
    return Decimal(int(digits) * sign).scaleb(-scale)
