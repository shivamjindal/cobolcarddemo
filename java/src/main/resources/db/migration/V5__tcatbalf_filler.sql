-- CBACT04C DISPLAYs the category-balance record, filler included. The shipped file's filler is
-- zeros, which V1 did not store, so a read came back as spaces and the golden line was short.
-- NULL is LOW-VALUES, as for the other FILLER columns. '' is spaces.

ALTER TABLE transaction_category_balances ADD COLUMN tran_cat_filler varchar(22) COLLATE "C" DEFAULT '';

COMMENT ON COLUMN transaction_category_balances.tran_cat_filler IS 'TCATBALF FILLER PIC X(22) @ 28 len 22; NULL = LOW-VALUES';
