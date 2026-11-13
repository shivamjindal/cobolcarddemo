-- Requests a message program has committed, keyed by JMSMessageID, with the puts it made. The broker commits after
-- the database, so a crash between the two redelivers a request already stored; its program re-puts these instead
-- of processing it again.
CREATE TABLE processed_messages (
    program varchar(8) NOT NULL,
    message_id varchar(100) NOT NULL,
    puts jsonb NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (program, message_id)
);
