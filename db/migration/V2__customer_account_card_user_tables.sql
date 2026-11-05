-- Master data: CUSTDATA, ACCTDATA, CARDDATA, CARDXREF, USRSEC VSAM KSDS clusters.
-- FILLER fields are not carried over. Y/N and A/U checks are the 88-level conditions.

CREATE TABLE customers (
    cust_id             INTEGER      PRIMARY KEY CHECK (cust_id BETWEEN 0 AND 999999999),
    first_name          VARCHAR(25)  NOT NULL,
    middle_name         VARCHAR(25),
    last_name           VARCHAR(25)  NOT NULL,
    addr_line_1         VARCHAR(50)  NOT NULL,
    addr_line_2         VARCHAR(50),
    addr_line_3         VARCHAR(50),
    addr_state_cd       VARCHAR(2),
    addr_country_cd     VARCHAR(3),
    addr_zip            VARCHAR(10),
    phone_1             VARCHAR(15),
    phone_2             VARCHAR(15),
    ssn                 CHAR(9)      NOT NULL CHECK (ssn ~ '^[0-9]{9}$'),
    govt_issued_id      VARCHAR(20),
    date_of_birth       DATE,
    eft_account_id      VARCHAR(10),
    primary_card_holder CHAR(1)      NOT NULL CHECK (primary_card_holder IN ('Y', 'N')),
    fico_score          SMALLINT     CHECK (fico_score BETWEEN 0 AND 999)
);

CREATE TABLE accounts (
    acct_id           BIGINT        PRIMARY KEY CHECK (acct_id BETWEEN 0 AND 99999999999),
    active_status     CHAR(1)       NOT NULL CHECK (active_status IN ('Y', 'N')),
    curr_balance      NUMERIC(12,2) NOT NULL,
    credit_limit      NUMERIC(12,2) NOT NULL,
    cash_credit_limit NUMERIC(12,2) NOT NULL,
    open_date         DATE          NOT NULL,
    expiration_date   DATE          NOT NULL,
    reissue_date      DATE,
    curr_cycle_credit NUMERIC(12,2) NOT NULL,
    curr_cycle_debit  NUMERIC(12,2) NOT NULL,
    addr_zip          VARCHAR(10),
    group_id          VARCHAR(10)
);

CREATE TABLE cards (
    card_num        VARCHAR(16) PRIMARY KEY,
    acct_id         BIGINT      NOT NULL REFERENCES accounts (acct_id),
    cvv             CHAR(3)     NOT NULL CHECK (cvv ~ '^[0-9]{3}$'),
    embossed_name   VARCHAR(50) NOT NULL,
    expiration_date DATE        NOT NULL,
    active_status   CHAR(1)     NOT NULL CHECK (active_status IN ('Y', 'N'))
);

CREATE TABLE card_account_xref (
    card_num VARCHAR(16) PRIMARY KEY REFERENCES cards (card_num),
    cust_id  INTEGER     NOT NULL REFERENCES customers (cust_id),
    acct_id  BIGINT      NOT NULL REFERENCES accounts (acct_id)
);

CREATE TABLE users (
    usr_id        VARCHAR(8)  PRIMARY KEY,
    first_name    VARCHAR(20) NOT NULL,
    last_name     VARCHAR(20) NOT NULL,
    password_hash TEXT        NOT NULL,
    usr_type      CHAR(1)     NOT NULL CHECK (usr_type IN ('A', 'U'))
);
