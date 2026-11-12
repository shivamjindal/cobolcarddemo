-- The authorization module (app/app-authorization-ims-db2-mq): the IMS database DBPAUTP0 and the Db2 table AUTHFRDS.
-- Segment columns follow CIPAUSMY and CIPAUDTY; a column a writer leaves out starts as INITIALIZE leaves it.

-- PAUTSUM0, the root segment: one account's pending authorization totals, keyed by ACCNTID.
CREATE TABLE pending_auth_summaries (
    pa_acct_id bigint PRIMARY KEY,
    pa_cust_id integer NOT NULL DEFAULT 0,
    pa_auth_status varchar(1) NOT NULL DEFAULT '',
    -- The five PA-ACCOUNT-STATUS occurrences, two bytes each.
    pa_account_status varchar(10) NOT NULL DEFAULT '',
    pa_credit_limit numeric(11, 2) NOT NULL DEFAULT 0,
    pa_cash_limit numeric(11, 2) NOT NULL DEFAULT 0,
    pa_credit_balance numeric(11, 2) NOT NULL DEFAULT 0,
    pa_cash_balance numeric(11, 2) NOT NULL DEFAULT 0,
    pa_approved_auth_cnt smallint NOT NULL DEFAULT 0,
    pa_declined_auth_cnt smallint NOT NULL DEFAULT 0,
    pa_approved_auth_amt numeric(11, 2) NOT NULL DEFAULT 0,
    pa_declined_auth_amt numeric(11, 2) NOT NULL DEFAULT 0
);

-- PAUTDTL1, the child segment: one authorization, sequenced under its summary by PAUT9CTS (PA-AUTHORIZATION-KEY).
-- COPAUA0C stores the date and time subtracted from all nines, so ascending key order lists the newest first.
-- Deleting a summary deletes its details, as DLET of a parent does.
CREATE TABLE pending_auth_details (
    pa_acct_id bigint NOT NULL REFERENCES pending_auth_summaries ON DELETE CASCADE,
    pa_auth_date_9c integer NOT NULL,
    pa_auth_time_9c integer NOT NULL,
    pa_auth_orig_date varchar(6) NOT NULL DEFAULT '',
    pa_auth_orig_time varchar(6) NOT NULL DEFAULT '',
    pa_card_num varchar(16) NOT NULL DEFAULT '',
    pa_auth_type varchar(4) NOT NULL DEFAULT '',
    pa_card_expiry_date varchar(4) NOT NULL DEFAULT '',
    pa_message_type varchar(6) NOT NULL DEFAULT '',
    pa_message_source varchar(6) NOT NULL DEFAULT '',
    pa_auth_id_code varchar(6) NOT NULL DEFAULT '',
    pa_auth_resp_code varchar(2) NOT NULL DEFAULT '',
    pa_auth_resp_reason varchar(4) NOT NULL DEFAULT '',
    pa_processing_code integer NOT NULL DEFAULT 0,
    pa_transaction_amt numeric(12, 2) NOT NULL DEFAULT 0,
    pa_approved_amt numeric(12, 2) NOT NULL DEFAULT 0,
    pa_merchant_catagory_code varchar(4) NOT NULL DEFAULT '',
    pa_acqr_country_code varchar(3) NOT NULL DEFAULT '',
    pa_pos_entry_mode smallint NOT NULL DEFAULT 0,
    pa_merchant_id varchar(15) NOT NULL DEFAULT '',
    pa_merchant_name varchar(22) NOT NULL DEFAULT '',
    pa_merchant_city varchar(13) NOT NULL DEFAULT '',
    pa_merchant_state varchar(2) NOT NULL DEFAULT '',
    pa_merchant_zip varchar(9) NOT NULL DEFAULT '',
    pa_transaction_id varchar(15) NOT NULL DEFAULT '',
    pa_match_status varchar(1) NOT NULL DEFAULT '',
    pa_auth_fraud varchar(1) NOT NULL DEFAULT '',
    pa_fraud_rpt_date varchar(8) NOT NULL DEFAULT '',
    PRIMARY KEY (pa_acct_id, pa_auth_date_9c, pa_auth_time_9c)
);

-- CARDDEMO.AUTHFRDS (ddl/AUTHFRDS.ddl): the authorizations COPAUS2C reported as fraud, or later cleared.
CREATE TABLE authfrds (
    card_num char(16) NOT NULL,
    auth_ts timestamp(6) NOT NULL,
    auth_type char(4),
    card_expiry_date char(4),
    message_type char(6),
    message_source char(6),
    auth_id_code char(6),
    auth_resp_code char(2),
    auth_resp_reason char(4),
    processing_code char(6),
    transaction_amt numeric(12, 2),
    approved_amt numeric(12, 2),
    merchant_catagory_code char(4),
    acqr_country_code char(3),
    pos_entry_mode smallint,
    merchant_id char(15),
    merchant_name varchar(22),
    merchant_city char(13),
    merchant_state char(2),
    merchant_zip char(9),
    transaction_id char(15),
    match_status char(1),
    auth_fraud char(1),
    fraud_rpt_date date,
    acct_id numeric(11),
    cust_id numeric(9),
    PRIMARY KEY (card_num, auth_ts)
);
