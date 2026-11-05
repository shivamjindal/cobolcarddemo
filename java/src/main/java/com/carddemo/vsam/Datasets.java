package com.carddemo.vsam;

import java.util.List;

public final class Datasets {

    private Datasets() {}

    public static final List<Dataset> ALL = List.of(
            new Dataset("accounts", "ACCTDATA", "CVACT01Y", "AWS.M2.CARDDEMO.ACCTDATA.PS"),
            new Dataset("customers", "CUSTDATA", "CVCUS01Y", "AWS.M2.CARDDEMO.CUSTDATA.PS"),
            new Dataset("cards", "CARDDATA", "CVACT02Y", "AWS.M2.CARDDEMO.CARDDATA.PS"),
            new Dataset("card_xref", "CARDXREF", "CVACT03Y", "AWS.M2.CARDDEMO.CARDXREF.PS"),
            new Dataset("transaction_types", "TRANTYPE", "CVTRA03Y", "AWS.M2.CARDDEMO.TRANTYPE.PS"),
            new Dataset("transaction_categories", "TRANCATG", "CVTRA04Y", "AWS.M2.CARDDEMO.TRANCATG.PS"),
            new Dataset("disclosure_groups", "DISCGRP", "CVTRA02Y", "AWS.M2.CARDDEMO.DISCGRP.PS"),
            new Dataset("transaction_category_balances", "TCATBALF", "CVTRA01Y", "AWS.M2.CARDDEMO.TCATBALF.PS"),
            // DALYTRAN.PS.INIT is one LOW-VALUES record. Postgres text cannot store those NUL bytes.
            new Dataset("transactions", "TRANSACT", "CVTRA05Y", "AWS.M2.CARDDEMO.DALYTRAN.PS"),
            new Dataset("users", "USRSEC", "CSUSR01Y", "AWS.M2.CARDDEMO.USRSEC.PS"));

    public static final List<ForeignKeyCandidate> FOREIGN_KEYS = List.of(
            new ForeignKeyCandidate("cards", List.of("card_acct_id"), "accounts"),
            new ForeignKeyCandidate("card_xref", List.of("xref_card_num"), "cards"),
            new ForeignKeyCandidate("card_xref", List.of("xref_cust_id"), "customers"),
            new ForeignKeyCandidate("card_xref", List.of("xref_acct_id"), "accounts"),
            new ForeignKeyCandidate("transactions", List.of("tran_card_num"), "card_xref"),
            new ForeignKeyCandidate("transactions", List.of("tran_type_cd", "tran_cat_cd"), "transaction_categories"),
            new ForeignKeyCandidate("transaction_categories", List.of("tran_type_cd"), "transaction_types"),
            new ForeignKeyCandidate("transaction_category_balances", List.of("trancat_acct_id"), "accounts"),
            new ForeignKeyCandidate(
                    "transaction_category_balances",
                    List.of("trancat_type_cd", "trancat_cd"),
                    "transaction_categories"),
            new ForeignKeyCandidate(
                    "disclosure_groups",
                    List.of("dis_tran_type_cd", "dis_tran_cat_cd"),
                    "transaction_categories"));
}
