-- CICS transient data queues the programs write with EXEC CICS WRITEQ TD, such as CSSL, COPAUA0C's error log.
-- A record is bytes because a program's storage may hold LOW-VALUES. CSSL is extrapartition and not recoverable,
-- so a write commits at once, whatever the task does after it.

CREATE TABLE td_queue_records (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue varchar(4) COLLATE "C" NOT NULL,
    data bytea NOT NULL,
    written_at timestamp(6) NOT NULL DEFAULT localtimestamp
);
CREATE INDEX td_queue_records_by_queue ON td_queue_records (queue, sequence);
COMMENT ON TABLE td_queue_records IS 'EXEC CICS WRITEQ TD records in write order, one row per record';

-- The trigger definitions of the region's request queues. A queue starts one transid, so CODATE01's CDRD gets a
-- request queue of its own next to CARDDEMO.REQUEST.QUEUE, which starts COACCT01.

INSERT INTO mq_queues (name, trigger_type, trigger_transid) VALUES
    ('AWS.M2.CARDDEMO.PAUTH.REQUEST', 'FIRST', 'CP00'),
    ('CARDDEMO.REQUEST.QUEUE', 'FIRST', 'CDRA'),
    ('CARDDEMO.REQUEST.DATE', 'FIRST', 'CDRD')
ON CONFLICT (name) DO NOTHING;
