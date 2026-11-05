-- COTRN02C writes the typed 10-character date into TRAN-ORIG-TS and TRAN-PROC-TS, so both stay text, as
-- POSTTRAN will write them. COBIL00C writes LOW-VALUES into TRAN-CARD-NUM when the account has no card
-- cross-reference: NULL, as in V2. COTRN02C checks only that the type and category codes are numeric,
-- and VSAM has no referential integrity, so the category foreign key goes.

ALTER TABLE transactions ALTER COLUMN tran_orig_ts TYPE varchar(26) COLLATE "C"
    USING to_char(tran_orig_ts, 'YYYY-MM-DD HH24:MI:SS.US');
ALTER TABLE transactions ALTER COLUMN tran_card_num DROP NOT NULL;
ALTER TABLE transactions DROP CONSTRAINT transactions_tran_type_cd_tran_cat_cd_fk;

COMMENT ON COLUMN transactions.tran_card_num IS 'TRANSACT TRAN-CARD-NUM PIC X(16) @ 262 len 16; NULL = LOW-VALUES';
COMMENT ON COLUMN transactions.tran_orig_ts IS 'TRANSACT TRAN-ORIG-TS PIC X(26) @ 278 len 26; as written, a bare date or a timestamp';

-- TD queue JOBS: what CORPT00C submits. The batch slice reads it; nothing here runs a job. Dates stay text
-- because CORPT00C submits pre-1582 and reversed ranges, and the job reads them as CH symbols.

CREATE TABLE job_submissions (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue varchar(4) COLLATE "C" NOT NULL,
    job_name varchar(8) COLLATE "C" NOT NULL,
    procedure varchar(8) COLLATE "C" NOT NULL,
    start_date varchar(10) COLLATE "C" NOT NULL,
    end_date varchar(10) COLLATE "C" NOT NULL,
    jcl text[] NOT NULL,
    submitted_by varchar(8) COLLATE "C" NOT NULL,
    submitted_at timestamp(6) NOT NULL
);

COMMENT ON TABLE job_submissions IS 'WRITEQ TD of a whole job, one row per submit, in write order';
COMMENT ON COLUMN job_submissions.queue IS 'WRITEQ TD QUEUE PIC X(4)';
COMMENT ON COLUMN job_submissions.job_name IS 'CORPT00C JOB-DATA job card name';
COMMENT ON COLUMN job_submissions.procedure IS 'CORPT00C JOB-DATA EXEC PROC=';
COMMENT ON COLUMN job_submissions.start_date IS 'CORPT00C PARM-START-DATE-1 PIC X(10)';
COMMENT ON COLUMN job_submissions.end_date IS 'CORPT00C PARM-END-DATE-1 PIC X(10)';
COMMENT ON COLUMN job_submissions.jcl IS 'each 80-byte JCL-RECORD written, rstripped, through /*EOF';
COMMENT ON COLUMN job_submissions.submitted_by IS 'CDEMO-USER-ID PIC X(08) of the submitting task';
COMMENT ON COLUMN job_submissions.submitted_at IS 'clock time of the submitting task';
