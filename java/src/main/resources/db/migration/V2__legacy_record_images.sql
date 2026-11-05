-- Bytes the online update programs write that V1's typed columns cannot hold. COACTUPC and COCRDUPC
-- REWRITE from working-storage records whose FILLER is never initialized, so it is LOW-VALUES; COACTUPC's
-- record has no zip field, so its group id lands in ACCT-ADDR-ZIP and LOW-VALUES in ACCT-GROUP-ID; and
-- COCRDUPC writes spaces into CARD-CVV-CD. Postgres text cannot store NUL bytes, so in these columns
-- NULL is a field of all LOW-VALUES (CARD-CVV-CD: all spaces), and '' is all spaces as everywhere else.

ALTER TABLE accounts ALTER COLUMN acct_addr_zip DROP NOT NULL;
ALTER TABLE accounts ALTER COLUMN acct_group_id DROP NOT NULL;
ALTER TABLE accounts ADD COLUMN acct_filler varchar(178) COLLATE "C" DEFAULT '';
ALTER TABLE customers ADD COLUMN cust_filler varchar(168) COLLATE "C" DEFAULT '';
ALTER TABLE cards ALTER COLUMN card_cvv_cd DROP NOT NULL;
ALTER TABLE cards ADD COLUMN card_filler varchar(59) COLLATE "C" DEFAULT '';

COMMENT ON COLUMN accounts.acct_addr_zip IS 'ACCTDATA ACCT-ADDR-ZIP PIC X(10) @ 102 len 10; NULL = LOW-VALUES';
COMMENT ON COLUMN accounts.acct_group_id IS 'ACCTDATA ACCT-GROUP-ID PIC X(10) @ 112 len 10; NULL = LOW-VALUES';
COMMENT ON COLUMN accounts.acct_filler IS 'ACCTDATA FILLER PIC X(178) @ 122 len 178; NULL = LOW-VALUES';
COMMENT ON COLUMN customers.cust_filler IS 'CUSTDATA FILLER PIC X(168) @ 332 len 168; NULL = LOW-VALUES';
COMMENT ON COLUMN cards.card_cvv_cd IS 'CARDDATA CARD-CVV-CD PIC 9(03) @ 27 len 3; NULL = SPACES';
COMMENT ON COLUMN cards.card_filler IS 'CARDDATA FILLER PIC X(59) @ 91 len 59; NULL = LOW-VALUES';
