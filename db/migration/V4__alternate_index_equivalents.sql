-- Alternate indexes from LISTCAT/JCL. All three are NONUNIQUEKEY.
-- Foreign-key columns on the base tables get the same indexes so deletes and joins do not scan.

-- CARDDATA.VSAM.AIX: KEYS(11 16), CARD-ACCT-ID. Used online via CICS file CARDAIX (COCRDSLC).
CREATE INDEX cards_acct_id_idx ON cards (acct_id);

-- CARDXREF.VSAM.AIX: KEYS(11 25), XREF-ACCT-ID. Used online via CICS file CXACAIX (account view/update, bill pay, add transaction).
CREATE INDEX card_account_xref_acct_id_idx ON card_account_xref (acct_id);
CREATE INDEX card_account_xref_cust_id_idx ON card_account_xref (cust_id);

-- TRANSACT.VSAM.AIX: KEYS(26 304), TRAN-PROC-TS. No CICS file definition or program reads it; kept for parity.
CREATE INDEX transactions_proc_ts_idx ON transactions (proc_ts);

-- Not in the legacy catalog: card_num is the FK and the report access path.
CREATE INDEX transactions_card_num_idx ON transactions (card_num);
