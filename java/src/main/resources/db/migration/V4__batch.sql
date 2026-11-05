-- Spring Batch 5.2.6 job repository, verbatim from org/springframework/batch/core/schema-postgresql.sql.
-- Boot does not initialize it (spring.batch.jdbc.initialize-schema=never), so upgrades come through here.

CREATE TABLE BATCH_JOB_INSTANCE  (
	JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT ,
	JOB_NAME VARCHAR(100) NOT NULL,
	JOB_KEY VARCHAR(32) NOT NULL,
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ;

CREATE TABLE BATCH_JOB_EXECUTION  (
	JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT  ,
	JOB_INSTANCE_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
	references BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS  (
	JOB_EXECUTION_ID BIGINT NOT NULL ,
	PARAMETER_NAME VARCHAR(100) NOT NULL ,
	PARAMETER_TYPE VARCHAR(100) NOT NULL ,
	PARAMETER_VALUE VARCHAR(2500) ,
	IDENTIFYING CHAR(1) NOT NULL ,
	constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION  (
	STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT NOT NULL,
	STEP_NAME VARCHAR(100) NOT NULL,
	JOB_EXECUTION_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	COMMIT_COUNT BIGINT ,
	READ_COUNT BIGINT ,
	FILTER_COUNT BIGINT ,
	WRITE_COUNT BIGINT ,
	READ_SKIP_COUNT BIGINT ,
	WRITE_SKIP_COUNT BIGINT ,
	PROCESS_SKIP_COUNT BIGINT ,
	ROLLBACK_COUNT BIGINT ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT  (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
	references BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT  (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_SEQ MAXVALUE 9223372036854775807 NO CYCLE;

-- Sequential datasets and GDG generations. A GDG generation is a row with the base as dsn and its absolute
-- generation number; (0) is the newest CATALOGED one when the job starts. PENDING is a NEW allocation not yet
-- dispositioned at step end; UNCATALOGED is a NOSCRATCH roll-off or DISP=UNCATLG, kept but no longer resolvable.
-- A keyed dataset that is not one of the ten tables (the CREASTMT TRXFL work file) records its key here.

CREATE TABLE datasets (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dsn varchar(44) COLLATE "C" NOT NULL,
    generation integer,
    state varchar(11) COLLATE "C" NOT NULL CHECK (state IN ('PENDING', 'CATALOGED', 'UNCATALOGED')),
    recfm varchar(1) COLLATE "C" NOT NULL CHECK (recfm IN ('F', 'V')),
    lrecl integer,
    key_offset integer,
    key_length integer,
    job_execution_id bigint,
    created_at timestamp(6) NOT NULL
);

CREATE UNIQUE INDEX datasets_cataloged_dsn ON datasets (dsn) WHERE state = 'CATALOGED' AND generation IS NULL;
CREATE UNIQUE INDEX datasets_dsn_generation ON datasets (dsn, generation) WHERE generation IS NOT NULL;

CREATE TABLE dataset_records (
    dataset_id bigint NOT NULL REFERENCES datasets ON DELETE CASCADE,
    seq integer NOT NULL,
    record bytea NOT NULL,
    PRIMARY KEY (dataset_id, seq)
);

COMMENT ON TABLE dataset_records IS 'one latin-1 record image per row, in write order; V records without the RDW';

-- SYSOUT=* data sets of a job: program DISPLAY lines (dd SYSOUT) and SYSOUT DDs, kept per step and DD.

CREATE TABLE spool (
    job_execution_id bigint NOT NULL,
    step varchar(17) COLLATE "C" NOT NULL,
    dd varchar(8) COLLATE "C" NOT NULL,
    seq integer NOT NULL,
    record bytea NOT NULL,
    PRIMARY KEY (job_execution_id, step, dd, seq)
);

-- The internal reader's claim on a job_submissions row. The queue stays append-only and owned by its writer;
-- a row without a claim has not been read yet. Rows submitted before the reader existed are never run.

CREATE TABLE job_submission_runs (
    submission_id bigint PRIMARY KEY REFERENCES job_submissions ON DELETE CASCADE,
    claimed_at timestamp(6) NOT NULL,
    job_execution_id bigint,
    completion varchar(40) COLLATE "C",
    ended_at timestamp(6)
);

INSERT INTO job_submission_runs (submission_id, claimed_at, completion, ended_at)
SELECT id, submitted_at, 'NOT RUN', submitted_at FROM job_submissions;

-- Decks other than CORPT00C's carry no report procedure or dates.

ALTER TABLE job_submissions ALTER COLUMN procedure DROP NOT NULL;
ALTER TABLE job_submissions ALTER COLUMN start_date DROP NOT NULL;
ALTER TABLE job_submissions ALTER COLUMN end_date DROP NOT NULL;

-- CICS files closed by CEMT SET FILE CLOSE (the CLOSEFIL step), one row for the region.

CREATE TABLE cics_region (
    id boolean PRIMARY KEY DEFAULT true CHECK (id),
    closed text[] NOT NULL DEFAULT '{}'
);

INSERT INTO cics_region DEFAULT VALUES;

-- ACCT-REISSUE-DATE may be spaces in a batch input. In a date column NULL is a field of all spaces.

ALTER TABLE accounts ALTER COLUMN acct_reissue_date DROP NOT NULL;

COMMENT ON COLUMN accounts.acct_reissue_date IS 'ACCTDATA ACCT-REISSUE-DATE PIC X(10) @ 68 len 10; NULL = SPACES';

-- VSAM has no referential integrity. Batch posts category balances for categories TRANCATG lacks, posts
-- transactions for cards it has validated only through CARDXREF, and reloads the reference files in any order.

ALTER TABLE transactions DROP CONSTRAINT transactions_tran_card_num_fk;
ALTER TABLE transaction_category_balances DROP CONSTRAINT transaction_category_balances_trancat_acct_id_fk;
ALTER TABLE transaction_category_balances DROP CONSTRAINT transaction_category_balances_trancat_type_cd_trancat_cd_fk;
ALTER TABLE transaction_categories DROP CONSTRAINT transaction_categories_tran_type_cd_fk;
ALTER TABLE disclosure_groups DROP CONSTRAINT disclosure_groups_dis_tran_type_cd_dis_tran_cat_cd_fk;
