-- Db2 TRANSACTION_TYPE_CATEGORY.TRC_TYPE_CODE references TRANSACTION_TYPE.TR_TYPE
-- ON DELETE RESTRICT (SQLCODE -532). The seed categories all use types 01-07.
ALTER TABLE tran_categories
    ADD CONSTRAINT tran_categories_type_fk
    FOREIGN KEY (tran_type_cd) REFERENCES tran_types (tran_type)
    ON DELETE RESTRICT;
