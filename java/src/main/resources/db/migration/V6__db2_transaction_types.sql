-- The Db2 tables of the optional transaction-type extension (app/app-transaction-type-db2), apart from the
-- VSAM-loaded transaction_types and transaction_categories. They are a separate store in the legacy system:
-- the load cards are upper case with their own typos ('REVERAL'), and VSAM sees Db2 only through the
-- TRANEXTR extract. The schema-qualified names match the programs' SQL (CARDDEMO.TRANSACTION_TYPE).
-- VARCHAR keeps the trailing blanks a program binds, as Db2 does.
-- IF NOT EXISTS because spring.flyway.schemas lists these schemas, and Flyway creates a listed schema itself
-- on an empty database.

CREATE SCHEMA IF NOT EXISTS carddemo;

CREATE TABLE carddemo.transaction_type (
    tr_type char(2) COLLATE "C" NOT NULL,
    tr_description varchar(50) COLLATE "C" NOT NULL,
    PRIMARY KEY (tr_type)
);
COMMENT ON TABLE carddemo.transaction_type IS 'Db2 table CARDDEMO.TRANSACTION_TYPE, DDL TRNTYPE, DCLGEN DCLTRTYP';
COMMENT ON COLUMN carddemo.transaction_type.tr_type IS 'TR_TYPE CHAR(2) NOT NULL; DCL-TR-TYPE PIC X(2)';
COMMENT ON COLUMN carddemo.transaction_type.tr_description IS 'TR_DESCRIPTION VARCHAR(50) NOT NULL; DCL-TR-DESCRIPTION';

CREATE TABLE carddemo.transaction_type_category (
    trc_type_code char(2) COLLATE "C" NOT NULL,
    trc_type_category char(4) COLLATE "C" NOT NULL,
    trc_cat_data varchar(50) COLLATE "C" NOT NULL,
    PRIMARY KEY (trc_type_code, trc_type_category),
    CONSTRAINT trc_type_code FOREIGN KEY (trc_type_code)
        REFERENCES carddemo.transaction_type (tr_type) ON DELETE RESTRICT
);
COMMENT ON TABLE carddemo.transaction_type_category IS 'Db2 table CARDDEMO.TRANSACTION_TYPE_CATEGORY, DDL TRNTYCAT, DCLGEN DCLTRCAT';
COMMENT ON COLUMN carddemo.transaction_type_category.trc_type_code IS 'TRC_TYPE_CODE CHAR(2) NOT NULL; DCL-TRC-TYPE-CODE PIC X(2)';
COMMENT ON COLUMN carddemo.transaction_type_category.trc_type_category IS 'TRC_TYPE_CATEGORY CHAR(4) NOT NULL; DCL-TRC-TYPE-CATEGORY PIC X(4)';
COMMENT ON COLUMN carddemo.transaction_type_category.trc_cat_data IS 'TRC_CAT_DATA VARCHAR(50) NOT NULL; DCL-TRC-CAT-DATA';

-- The one-row table Db2 programs select constants from, as CSDB2RPY's priming query does.
CREATE SCHEMA IF NOT EXISTS sysibm;

CREATE TABLE sysibm.sysdummy1 (
    ibmreqd char(1) NOT NULL
);
COMMENT ON TABLE sysibm.sysdummy1 IS 'Db2 catalog table SYSIBM.SYSDUMMY1: one row, IBMREQD = Y';
INSERT INTO sysibm.sysdummy1 VALUES ('Y');

-- CREADB21 steps LDTTYPE and LDTCCAT: the rows of load cards DB2LTTYP and DB2LTCAT.
INSERT INTO carddemo.transaction_type (tr_type, tr_description) VALUES
    ('01', 'PURCHASE'),
    ('02', 'PAYMENT'),
    ('03', 'CREDIT'),
    ('04', 'AUTHORIZATION'),
    ('05', 'REFUND'),
    ('06', 'REVERAL'),
    ('07', 'ADJUSTMENT');

INSERT INTO carddemo.transaction_type_category (trc_type_code, trc_type_category, trc_cat_data) VALUES
    ('01', '0001', 'REGULAR SALES DRAFT'),
    ('01', '0002', 'REGULAR CASH ADVANCE'),
    ('01', '0003', 'CONVENIENCE CHECK DEBIT'),
    ('01', '0004', 'ATM CASH ADVANCE'),
    ('01', '0005', 'INTEREST AMOUNT'),
    ('02', '0001', 'CASH PAYMENT'),
    ('02', '0002', 'ELECTRONIC PAYMENT'),
    ('02', '0003', 'CHECK PAYMENT'),
    ('03', '0001', 'CREDIT TO ACCOUNT'),
    ('03', '0002', 'CREDIT TO PURCHASE BALANCE'),
    ('03', '0003', 'CREDIT TO CASH BALANCE'),
    ('04', '0001', 'ZERO DOLLAR AUTHORIZATION'),
    ('04', '0002', 'ONLINE PURCHASE AUTHORIZATION'),
    ('04', '0003', 'TRAVEL BOOKING AUTHORIZATION'),
    ('05', '0001', 'REFUND CREDIT'),
    ('06', '0001', 'FRAUD REVERSAL'),
    ('06', '0002', 'NON FRAUD REVERSAL'),
    ('07', '0001', 'SALES DRAFT CREDIT ADJUSTMENT');
