-- Reference data: TRANTYPE, TRANCATG, DISCGRP VSAM KSDS clusters.

CREATE TABLE transaction_types (
    type_cd     VARCHAR(2)  PRIMARY KEY,
    description VARCHAR(50) NOT NULL
);

CREATE TABLE transaction_categories (
    type_cd     VARCHAR(2)  NOT NULL REFERENCES transaction_types (type_cd),
    cat_cd      INTEGER     NOT NULL CHECK (cat_cd BETWEEN 0 AND 9999),
    description VARCHAR(50) NOT NULL,
    PRIMARY KEY (type_cd, cat_cd)
);

-- group_id is only the leading part of the KSDS key, so accounts.group_id cannot reference it.
CREATE TABLE disclosure_groups (
    group_id      VARCHAR(10)  NOT NULL,
    type_cd       VARCHAR(2)   NOT NULL,
    cat_cd        INTEGER      NOT NULL,
    interest_rate NUMERIC(6,2) NOT NULL,
    PRIMARY KEY (group_id, type_cd, cat_cd),
    FOREIGN KEY (type_cd, cat_cd) REFERENCES transaction_categories (type_cd, cat_cd)
);
