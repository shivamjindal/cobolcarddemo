-- TRANSACT and TCATBALF VSAM KSDS clusters.

CREATE TABLE transactions (
    tran_id       VARCHAR(16)   PRIMARY KEY,
    type_cd       VARCHAR(2)    NOT NULL,
    cat_cd        INTEGER       NOT NULL,
    source        VARCHAR(10),
    description   VARCHAR(100),
    amount        NUMERIC(11,2) NOT NULL,
    merchant_id   INTEGER       NOT NULL,
    merchant_name VARCHAR(50),
    merchant_city VARCHAR(50),
    merchant_zip  VARCHAR(10),
    card_num      VARCHAR(16)   NOT NULL REFERENCES cards (card_num),
    orig_ts       TIMESTAMP(6)  NOT NULL,
    proc_ts       TIMESTAMP(6),
    FOREIGN KEY (type_cd, cat_cd) REFERENCES transaction_categories (type_cd, cat_cd)
);

CREATE TABLE transaction_category_balances (
    acct_id BIGINT        NOT NULL REFERENCES accounts (acct_id),
    type_cd VARCHAR(2)    NOT NULL,
    cat_cd  INTEGER       NOT NULL,
    balance NUMERIC(11,2) NOT NULL,
    PRIMARY KEY (acct_id, type_cd, cat_cd),
    FOREIGN KEY (type_cd, cat_cd) REFERENCES transaction_categories (type_cd, cat_cd)
);

-- Replaces COTRN02C/COBIL00C minting max(TRAN-ID)+1 from a backward browse, which races under concurrency.
-- Seed with setval() after the initial load.
CREATE SEQUENCE transaction_id_seq AS BIGINT MINVALUE 1 MAXVALUE 9999999999999999;
