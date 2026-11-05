-- IMS database DBPAUTP0 (HIDAM): root PAUTSUM0 keyed by ACCNTID, child PAUTDTL1 keyed by PAUT9CTS.
-- Both key fields are positive COMP-3 of fixed width, so numeric order is IMS key order, and the
-- hierarchical sequence is ORDER BY acct_id, auth_date_9c, auth_time_9c. DLET of a root deletes its
-- children, as the foreign key's cascade does. Text columns are stored without trailing spaces, and NULL is
-- a field of all LOW-VALUES, as in V2: three sample summaries and some details hold them. A COMP count holds
-- whatever its two bytes do, as the sample's last root shows with spaces in every field but the text.

CREATE TABLE pending_auth_summaries (
    acct_id bigint NOT NULL CHECK (acct_id BETWEEN 0 AND 99999999999),
    cust_id integer NOT NULL CHECK (cust_id BETWEEN 0 AND 999999999),
    auth_status varchar(1) COLLATE "C",
    account_status_1 varchar(2) COLLATE "C",
    account_status_2 varchar(2) COLLATE "C",
    account_status_3 varchar(2) COLLATE "C",
    account_status_4 varchar(2) COLLATE "C",
    account_status_5 varchar(2) COLLATE "C",
    credit_limit numeric(11,2) NOT NULL,
    cash_limit numeric(11,2) NOT NULL,
    credit_balance numeric(11,2) NOT NULL,
    cash_balance numeric(11,2) NOT NULL,
    approved_auth_cnt integer NOT NULL CHECK (approved_auth_cnt BETWEEN -32768 AND 32767),
    declined_auth_cnt integer NOT NULL CHECK (declined_auth_cnt BETWEEN -32768 AND 32767),
    approved_auth_amt numeric(11,2) NOT NULL,
    declined_auth_amt numeric(11,2) NOT NULL,
    filler varchar(34) COLLATE "C",
    PRIMARY KEY (acct_id)
);
COMMENT ON TABLE pending_auth_summaries IS 'IMS DBPAUTP0 root segment PAUTSUM0, copybook CIPAUSMY, segment length 100, key ACCNTID offset 0 length 6';
COMMENT ON COLUMN pending_auth_summaries.acct_id IS 'PA-ACCT-ID PIC S9(11) COMP-3 offset 0 length 6';
COMMENT ON COLUMN pending_auth_summaries.cust_id IS 'PA-CUST-ID PIC 9(09) offset 6 length 9';
COMMENT ON COLUMN pending_auth_summaries.auth_status IS 'PA-AUTH-STATUS PIC X(01) offset 15 length 1';
COMMENT ON COLUMN pending_auth_summaries.account_status_1 IS 'PA-ACCOUNT-STATUS(1) PIC X(02) offset 16 length 2';
COMMENT ON COLUMN pending_auth_summaries.account_status_2 IS 'PA-ACCOUNT-STATUS(2) PIC X(02) offset 18 length 2';
COMMENT ON COLUMN pending_auth_summaries.account_status_3 IS 'PA-ACCOUNT-STATUS(3) PIC X(02) offset 20 length 2';
COMMENT ON COLUMN pending_auth_summaries.account_status_4 IS 'PA-ACCOUNT-STATUS(4) PIC X(02) offset 22 length 2';
COMMENT ON COLUMN pending_auth_summaries.account_status_5 IS 'PA-ACCOUNT-STATUS(5) PIC X(02) offset 24 length 2';
COMMENT ON COLUMN pending_auth_summaries.credit_limit IS 'PA-CREDIT-LIMIT PIC S9(09)V99 COMP-3 offset 26 length 6';
COMMENT ON COLUMN pending_auth_summaries.cash_limit IS 'PA-CASH-LIMIT PIC S9(09)V99 COMP-3 offset 32 length 6';
COMMENT ON COLUMN pending_auth_summaries.credit_balance IS 'PA-CREDIT-BALANCE PIC S9(09)V99 COMP-3 offset 38 length 6';
COMMENT ON COLUMN pending_auth_summaries.cash_balance IS 'PA-CASH-BALANCE PIC S9(09)V99 COMP-3 offset 44 length 6';
COMMENT ON COLUMN pending_auth_summaries.approved_auth_cnt IS 'PA-APPROVED-AUTH-CNT PIC S9(04) COMP offset 50 length 2';
COMMENT ON COLUMN pending_auth_summaries.declined_auth_cnt IS 'PA-DECLINED-AUTH-CNT PIC S9(04) COMP offset 52 length 2';
COMMENT ON COLUMN pending_auth_summaries.approved_auth_amt IS 'PA-APPROVED-AUTH-AMT PIC S9(09)V99 COMP-3 offset 54 length 6';
COMMENT ON COLUMN pending_auth_summaries.declined_auth_amt IS 'PA-DECLINED-AUTH-AMT PIC S9(09)V99 COMP-3 offset 60 length 6';
COMMENT ON COLUMN pending_auth_summaries.filler IS 'FILLER PIC X(34) offset 66 length 34';

CREATE TABLE pending_auth_details (
    acct_id bigint NOT NULL REFERENCES pending_auth_summaries (acct_id) ON DELETE CASCADE,
    auth_date_9c integer NOT NULL CHECK (auth_date_9c BETWEEN 0 AND 99999),
    auth_time_9c integer NOT NULL CHECK (auth_time_9c BETWEEN 0 AND 999999999),
    auth_orig_date varchar(6) COLLATE "C",
    auth_orig_time varchar(6) COLLATE "C",
    card_num varchar(16) COLLATE "C",
    auth_type varchar(4) COLLATE "C",
    card_expiry_date varchar(4) COLLATE "C",
    message_type varchar(6) COLLATE "C",
    message_source varchar(6) COLLATE "C",
    auth_id_code varchar(6) COLLATE "C",
    auth_resp_code varchar(2) COLLATE "C",
    auth_resp_reason varchar(4) COLLATE "C",
    processing_code integer NOT NULL CHECK (processing_code BETWEEN 0 AND 999999),
    transaction_amt numeric(12,2) NOT NULL,
    approved_amt numeric(12,2) NOT NULL,
    merchant_catagory_code varchar(4) COLLATE "C",
    acqr_country_code varchar(3) COLLATE "C",
    pos_entry_mode integer NOT NULL CHECK (pos_entry_mode BETWEEN 0 AND 99),
    merchant_id varchar(15) COLLATE "C",
    merchant_name varchar(22) COLLATE "C",
    merchant_city varchar(13) COLLATE "C",
    merchant_state varchar(2) COLLATE "C",
    merchant_zip varchar(9) COLLATE "C",
    transaction_id varchar(15) COLLATE "C",
    match_status varchar(1) COLLATE "C",
    auth_fraud varchar(1) COLLATE "C",
    fraud_rpt_date varchar(8) COLLATE "C",
    filler varchar(17) COLLATE "C",
    PRIMARY KEY (acct_id, auth_date_9c, auth_time_9c)
);
COMMENT ON TABLE pending_auth_details IS 'IMS DBPAUTP0 child segment PAUTDTL1 under PAUTSUM0, copybook CIPAUDTY, segment length 200, key PAUT9CTS offset 0 length 8';
COMMENT ON COLUMN pending_auth_details.acct_id IS 'the parent PAUTSUM0 ACCNTID, the first 6 bytes of the concatenated key';
COMMENT ON COLUMN pending_auth_details.auth_date_9c IS 'PA-AUTH-DATE-9C PIC S9(05) COMP-3 offset 0 length 3; 99999 - YYDDD, so newer sorts first';
COMMENT ON COLUMN pending_auth_details.auth_time_9c IS 'PA-AUTH-TIME-9C PIC S9(09) COMP-3 offset 3 length 5; 999999999 - HHMMSSmmm';
COMMENT ON COLUMN pending_auth_details.auth_orig_date IS 'PA-AUTH-ORIG-DATE PIC X(06) offset 8 length 6';
COMMENT ON COLUMN pending_auth_details.auth_orig_time IS 'PA-AUTH-ORIG-TIME PIC X(06) offset 14 length 6';
COMMENT ON COLUMN pending_auth_details.card_num IS 'PA-CARD-NUM PIC X(16) offset 20 length 16';
COMMENT ON COLUMN pending_auth_details.auth_type IS 'PA-AUTH-TYPE PIC X(04) offset 36 length 4';
COMMENT ON COLUMN pending_auth_details.card_expiry_date IS 'PA-CARD-EXPIRY-DATE PIC X(04) offset 40 length 4';
COMMENT ON COLUMN pending_auth_details.message_type IS 'PA-MESSAGE-TYPE PIC X(06) offset 44 length 6';
COMMENT ON COLUMN pending_auth_details.message_source IS 'PA-MESSAGE-SOURCE PIC X(06) offset 50 length 6';
COMMENT ON COLUMN pending_auth_details.auth_id_code IS 'PA-AUTH-ID-CODE PIC X(06) offset 56 length 6';
COMMENT ON COLUMN pending_auth_details.auth_resp_code IS 'PA-AUTH-RESP-CODE PIC X(02) offset 62 length 2; 00 approved';
COMMENT ON COLUMN pending_auth_details.auth_resp_reason IS 'PA-AUTH-RESP-REASON PIC X(04) offset 64 length 4';
COMMENT ON COLUMN pending_auth_details.processing_code IS 'PA-PROCESSING-CODE PIC 9(06) offset 68 length 6';
COMMENT ON COLUMN pending_auth_details.transaction_amt IS 'PA-TRANSACTION-AMT PIC S9(10)V99 COMP-3 offset 74 length 7';
COMMENT ON COLUMN pending_auth_details.approved_amt IS 'PA-APPROVED-AMT PIC S9(10)V99 COMP-3 offset 81 length 7';
COMMENT ON COLUMN pending_auth_details.merchant_catagory_code IS 'PA-MERCHANT-CATAGORY-CODE PIC X(04) offset 88 length 4';
COMMENT ON COLUMN pending_auth_details.acqr_country_code IS 'PA-ACQR-COUNTRY-CODE PIC X(03) offset 92 length 3';
COMMENT ON COLUMN pending_auth_details.pos_entry_mode IS 'PA-POS-ENTRY-MODE PIC 9(02) offset 95 length 2';
COMMENT ON COLUMN pending_auth_details.merchant_id IS 'PA-MERCHANT-ID PIC X(15) offset 97 length 15';
COMMENT ON COLUMN pending_auth_details.merchant_name IS 'PA-MERCHANT-NAME PIC X(22) offset 112 length 22';
COMMENT ON COLUMN pending_auth_details.merchant_city IS 'PA-MERCHANT-CITY PIC X(13) offset 134 length 13';
COMMENT ON COLUMN pending_auth_details.merchant_state IS 'PA-MERCHANT-STATE PIC X(02) offset 147 length 2';
COMMENT ON COLUMN pending_auth_details.merchant_zip IS 'PA-MERCHANT-ZIP PIC X(09) offset 149 length 9';
COMMENT ON COLUMN pending_auth_details.transaction_id IS 'PA-TRANSACTION-ID PIC X(15) offset 158 length 15';
COMMENT ON COLUMN pending_auth_details.match_status IS 'PA-MATCH-STATUS PIC X(01) offset 173 length 1; P pending, D declined, E expired, M matched';
COMMENT ON COLUMN pending_auth_details.auth_fraud IS 'PA-AUTH-FRAUD PIC X(01) offset 174 length 1; F confirmed, R removed';
COMMENT ON COLUMN pending_auth_details.fraud_rpt_date IS 'PA-FRAUD-RPT-DATE PIC X(08) offset 175 length 8';
COMMENT ON COLUMN pending_auth_details.filler IS 'FILLER PIC X(17) offset 183 length 17';

-- Db2 table CARDDEMO.AUTHFRDS as ddl/AUTHFRDS.ddl declares it. The primary key serves index XAUTHFRD,
-- which orders AUTH_TS descending; Postgres reads the key index backwards for that order.

CREATE TABLE authfrds (
    card_num varchar(16) COLLATE "C" NOT NULL,
    auth_ts timestamp(6) NOT NULL,
    auth_type varchar(4) COLLATE "C",
    card_expiry_date varchar(4) COLLATE "C",
    message_type varchar(6) COLLATE "C",
    message_source varchar(6) COLLATE "C",
    auth_id_code varchar(6) COLLATE "C",
    auth_resp_code varchar(2) COLLATE "C",
    auth_resp_reason varchar(4) COLLATE "C",
    processing_code varchar(6) COLLATE "C",
    transaction_amt numeric(12,2),
    approved_amt numeric(12,2),
    merchant_catagory_code varchar(4) COLLATE "C",
    acqr_country_code varchar(3) COLLATE "C",
    pos_entry_mode smallint,
    merchant_id varchar(15) COLLATE "C",
    merchant_name varchar(22) COLLATE "C",
    merchant_city varchar(13) COLLATE "C",
    merchant_state varchar(2) COLLATE "C",
    merchant_zip varchar(9) COLLATE "C",
    transaction_id varchar(15) COLLATE "C",
    match_status varchar(1) COLLATE "C",
    auth_fraud varchar(1) COLLATE "C",
    fraud_rpt_date date,
    acct_id numeric(11),
    cust_id numeric(9),
    PRIMARY KEY (card_num, auth_ts)
);
COMMENT ON TABLE authfrds IS 'Db2 CARDDEMO.AUTHFRDS, ddl/AUTHFRDS.ddl; COPAUS2C inserts a row when an authorization is reported as fraud';
COMMENT ON COLUMN authfrds.processing_code IS 'PROCESSING_CODE CHAR(6)';
COMMENT ON COLUMN authfrds.merchant_name IS 'MERCHANT_NAME VARCHAR(22)';
COMMENT ON COLUMN authfrds.fraud_rpt_date IS 'FRAUD_RPT_DATE DATE; COPAUS2C sets CURRENT DATE';

-- WebSphere MQ local queues held in Postgres, so an MQGET under syncpoint and the IMS and VSAM work of
-- the same task commit or roll back together. Any queue name is accepted, as with a model queue.

CREATE TABLE mq_queues (
    name varchar(48) COLLATE "C" PRIMARY KEY,
    trigger_type varchar(5) COLLATE "C" NOT NULL CHECK (trigger_type IN ('FIRST', 'EVERY')),
    trigger_transid varchar(4) COLLATE "C" NOT NULL,
    trigger_data varchar(64) COLLATE "C" NOT NULL DEFAULT ''
);
COMMENT ON TABLE mq_queues IS 'trigger definitions: a committed put to the queue starts TRANSID with an MQTM naming the queue';
COMMENT ON COLUMN mq_queues.trigger_type IS 'TRIGTYPE: FIRST when the queue goes from empty to not empty, EVERY for each message';
COMMENT ON COLUMN mq_queues.trigger_data IS 'TRIGDATA, MQTM-TRIGGERDATA of the started task';

CREATE TABLE mq_messages (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue varchar(48) COLLATE "C" NOT NULL,
    msg_id bytea NOT NULL CHECK (length(msg_id) = 24),
    correl_id bytea NOT NULL CHECK (length(correl_id) = 24),
    reply_to_q varchar(48) COLLATE "C" NOT NULL,
    data bytea NOT NULL
);
CREATE INDEX mq_messages_by_queue ON mq_messages (queue, sequence);
COMMENT ON TABLE mq_messages IS 'one row per message on a queue, in put order; a destructive MQGET deletes it';
COMMENT ON COLUMN mq_messages.msg_id IS 'MQMD-MSGID MQBYTE24';
COMMENT ON COLUMN mq_messages.correl_id IS 'MQMD-CORRELID MQBYTE24';
COMMENT ON COLUMN mq_messages.reply_to_q IS 'MQMD-REPLYTOQ MQCHAR48 without trailing spaces';
