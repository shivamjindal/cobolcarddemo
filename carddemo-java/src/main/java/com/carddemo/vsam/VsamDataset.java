package com.carddemo.vsam;

import java.util.List;
import java.util.Map;

/**
 * The VSAM KSDS clusters the online and batch programs use. Record lengths and primary keys come from
 * app/catlg/LISTCAT.txt (MAXLRECL, RKP, KEYLEN). Alternate index offsets come from the DEFINE
 * ALTERNATEINDEX KEYS(length offset) in app/jcl; LISTCAT's AIX RKP is the AIX record's own layout, not
 * the base record offset.
 */
public enum VsamDataset {
    ACCOUNTS("accounts", "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS", "CVACT01Y",
            "AWS.M2.CARDDEMO.ACCTDATA.PS", "acctdata.txt", 300, new ByteRange(0, 11), List.of(),
            Map.of("ACCT-OPEN-DATE", ColumnKind.DATE,
                    "ACCT-EXPIRAION-DATE", ColumnKind.DATE,
                    "ACCT-REISSUE-DATE", ColumnKind.DATE)),
    CARDS("cards", "AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS", "CVACT02Y",
            "AWS.M2.CARDDEMO.CARDDATA.PS", "carddata.txt", 150, new ByteRange(0, 16),
            List.of(new ByteRange(16, 11)),
            Map.of("CARD-EXPIRAION-DATE", ColumnKind.DATE)),
    CARD_XREF("card_xref", "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS", "CVACT03Y",
            "AWS.M2.CARDDEMO.CARDXREF.PS", "cardxref.txt", 50, new ByteRange(0, 16),
            List.of(new ByteRange(25, 11)), Map.of()),
    CUSTOMERS("customers", "AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS", "CVCUS01Y",
            "AWS.M2.CARDDEMO.CUSTDATA.PS", "custdata.txt", 500, new ByteRange(0, 9), List.of(),
            Map.of("CUST-DOB-YYYY-MM-DD", ColumnKind.DATE)),
    TRANSACTIONS("transactions", "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS", "CVTRA05Y",
            "AWS.M2.CARDDEMO.DALYTRAN.PS.INIT", null, 350, new ByteRange(0, 16),
            List.of(new ByteRange(304, 26)),
            Map.of("TRAN-ORIG-TS", ColumnKind.TIMESTAMP,
                    "TRAN-PROC-TS", ColumnKind.TIMESTAMP)),
    USERS("users", "AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS", "CSUSR01Y",
            "AWS.M2.CARDDEMO.USRSEC.PS", null, 80, new ByteRange(0, 8), List.of(), Map.of()),
    DISCLOSURE_GROUPS("disclosure_groups", "AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS", "CVTRA02Y",
            "AWS.M2.CARDDEMO.DISCGRP.PS", "discgrp.txt", 50, new ByteRange(0, 16), List.of(), Map.of()),
    TRAN_CAT_BALANCES("tran_cat_balances", "AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS", "CVTRA01Y",
            "AWS.M2.CARDDEMO.TCATBALF.PS", "tcatbal.txt", 50, new ByteRange(0, 17), List.of(), Map.of()),
    TRAN_CATEGORIES("tran_categories", "AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS", "CVTRA04Y",
            "AWS.M2.CARDDEMO.TRANCATG.PS", "trancatg.txt", 60, new ByteRange(0, 6), List.of(), Map.of()),
    TRAN_TYPES("tran_types", "AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS", "CVTRA03Y",
            "AWS.M2.CARDDEMO.TRANTYPE.PS", "trantype.txt", 60, new ByteRange(0, 2), List.of(), Map.of());

    public record ByteRange(int offset, int length) {
        public int end() {
            return offset + length;
        }
    }

    public final String table;
    public final String cluster;
    public final String copybook;
    public final String ebcdicFile;
    public final String asciiFile;
    public final int recordLength;
    public final ByteRange primaryKey;
    public final List<ByteRange> alternateIndexes;
    public final Map<String, ColumnKind> kindOverrides;

    VsamDataset(String table, String cluster, String copybook, String ebcdicFile, String asciiFile,
                int recordLength, ByteRange primaryKey, List<ByteRange> alternateIndexes,
                Map<String, ColumnKind> kindOverrides) {
        this.table = table;
        this.cluster = cluster;
        this.copybook = copybook;
        this.ebcdicFile = ebcdicFile;
        this.asciiFile = asciiFile;
        this.recordLength = recordLength;
        this.primaryKey = primaryKey;
        this.alternateIndexes = alternateIndexes;
        this.kindOverrides = kindOverrides;
    }
}
