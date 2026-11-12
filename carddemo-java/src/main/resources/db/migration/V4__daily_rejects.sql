-- What POSTTRAN rejected, so that a rerun of a feed rejects those records again for the same reason instead
-- of judging them against balances that later records in the feed have since moved.
CREATE TABLE daily_rejects (
    tran_id char(16) PRIMARY KEY,
    record varchar(350) NOT NULL,
    reason_code smallint NOT NULL,
    reason_desc varchar(76) NOT NULL
);
