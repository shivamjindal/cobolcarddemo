-- The reference reloads REPRO a sequential record image, and the shipped files' filler is zeros.
-- V1 did not store it, so a reload of the seed came back as spaces.
-- NULL is LOW-VALUES, as for the other FILLER columns. '' is spaces.

ALTER TABLE transaction_types ADD COLUMN tran_type_filler varchar(8) COLLATE "C" DEFAULT '';
ALTER TABLE transaction_categories ADD COLUMN tran_catg_filler varchar(4) COLLATE "C" DEFAULT '';
ALTER TABLE disclosure_groups ADD COLUMN dis_group_filler varchar(28) COLLATE "C" DEFAULT '';

COMMENT ON COLUMN transaction_types.tran_type_filler IS 'TRANTYPE FILLER PIC X(08) @ 52 len 8; NULL = LOW-VALUES';
COMMENT ON COLUMN transaction_categories.tran_catg_filler IS 'TRANCATG FILLER PIC X(04) @ 56 len 4; NULL = LOW-VALUES';
COMMENT ON COLUMN disclosure_groups.dis_group_filler IS 'DISCGRP FILLER PIC X(28) @ 22 len 28; NULL = LOW-VALUES';
