# VSAM KSDS schema mapping

SchemaGenerator writes this file from the copybooks, LISTCAT, and EBCDIC sample rows. The Flyway migration is the same run.

## accounts

Cluster `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`, copybook `CVACT01Y`, record `ACCOUNT-RECORD`, length 300, key offset 0 length 11. Data file `AWS.M2.CARDDEMO.ACCTDATA.PS`.

| COBOL name | PIC | Offset | Length | SQL type | Reason |
| --- | --- | --- | --- | --- | --- |
| ACCT-ID | 9(11) | 0 | 11 | bigint | unsigned zoned with 11 digits, stored as bigint |
| ACCT-ACTIVE-STATUS | X(01) | 11 | 1 | varchar(1) | PIC X(01) stored as text |
| ACCT-CURR-BAL | S9(10)V99 | 12 | 12 | numeric(12,2) | signed zoned numeric(12,2) |
| ACCT-CREDIT-LIMIT | S9(10)V99 | 24 | 12 | numeric(12,2) | signed zoned numeric(12,2) |
| ACCT-CASH-CREDIT-LIMIT | S9(10)V99 | 36 | 12 | numeric(12,2) | signed zoned numeric(12,2) |
| ACCT-OPEN-DATE | X(10) | 48 | 10 | date | every source row parses as uuuu-MM-dd |
| ACCT-EXPIRAION-DATE | X(10) | 58 | 10 | date | every source row parses as uuuu-MM-dd |
| ACCT-REISSUE-DATE | X(10) | 68 | 10 | date | every source row parses as uuuu-MM-dd |
| ACCT-CURR-CYC-CREDIT | S9(10)V99 | 78 | 12 | numeric(12,2) | signed zoned numeric(12,2) |
| ACCT-CURR-CYC-DEBIT | S9(10)V99 | 90 | 12 | numeric(12,2) | signed zoned numeric(12,2) |
| ACCT-ADDR-ZIP | X(10) | 102 | 10 | varchar(10) | PIC X(10) is not a date on every row |
| ACCT-GROUP-ID | X(10) | 112 | 10 | varchar(10) | PIC X(10) is not a date on every row |

Primary key is `acct_id`.

Alternate indexes

None.

Foreign keys

None.

Notes

- FILLER offset 122 length 178 is not stored

## customers

Cluster `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS`, copybook `CVCUS01Y`, record `CUSTOMER-RECORD`, length 500, key offset 0 length 9. Data file `AWS.M2.CARDDEMO.CUSTDATA.PS`.

| COBOL name | PIC | Offset | Length | SQL type | Reason |
| --- | --- | --- | --- | --- | --- |
| CUST-ID | 9(09) | 0 | 9 | integer | unsigned zoned with 9 digits, stored as integer |
| CUST-FIRST-NAME | X(25) | 9 | 25 | varchar(25) | PIC X(25) stored as text |
| CUST-MIDDLE-NAME | X(25) | 34 | 25 | varchar(25) | PIC X(25) stored as text |
| CUST-LAST-NAME | X(25) | 59 | 25 | varchar(25) | PIC X(25) stored as text |
| CUST-ADDR-LINE-1 | X(50) | 84 | 50 | varchar(50) | PIC X(50) stored as text |
| CUST-ADDR-LINE-2 | X(50) | 134 | 50 | varchar(50) | PIC X(50) stored as text |
| CUST-ADDR-LINE-3 | X(50) | 184 | 50 | varchar(50) | PIC X(50) stored as text |
| CUST-ADDR-STATE-CD | X(02) | 234 | 2 | varchar(2) | PIC X(02) stored as text |
| CUST-ADDR-COUNTRY-CD | X(03) | 236 | 3 | varchar(3) | PIC X(03) stored as text |
| CUST-ADDR-ZIP | X(10) | 239 | 10 | varchar(10) | PIC X(10) is not a date on every row |
| CUST-PHONE-NUM-1 | X(15) | 249 | 15 | varchar(15) | PIC X(15) stored as text |
| CUST-PHONE-NUM-2 | X(15) | 264 | 15 | varchar(15) | PIC X(15) stored as text |
| CUST-SSN | 9(09) | 279 | 9 | integer | unsigned zoned with 9 digits, stored as integer |
| CUST-GOVT-ISSUED-ID | X(20) | 288 | 20 | varchar(20) | PIC X(20) stored as text |
| CUST-DOB-YYYY-MM-DD | X(10) | 308 | 10 | date | every source row parses as uuuu-MM-dd |
| CUST-EFT-ACCOUNT-ID | X(10) | 318 | 10 | varchar(10) | PIC X(10) is not a date on every row |
| CUST-PRI-CARD-HOLDER-IND | X(01) | 328 | 1 | varchar(1) | PIC X(01) stored as text |
| CUST-FICO-CREDIT-SCORE | 9(03) | 329 | 3 | integer | unsigned zoned with 3 digits, stored as integer |

Primary key is `cust_id`.

Alternate indexes

None.

Foreign keys

None.

Notes

- FILLER offset 332 length 168 is not stored

## cards

Cluster `AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS`, copybook `CVACT02Y`, record `CARD-RECORD`, length 150, key offset 0 length 16. Data file `AWS.M2.CARDDEMO.CARDDATA.PS`.

| COBOL name | PIC | Offset | Length | SQL type | Reason |
| --- | --- | --- | --- | --- | --- |
| CARD-NUM | X(16) | 0 | 16 | varchar(16) | PIC X(16) stored as text |
| CARD-ACCT-ID | 9(11) | 16 | 11 | bigint | unsigned zoned with 11 digits, stored as bigint |
| CARD-CVV-CD | 9(03) | 27 | 3 | integer | unsigned zoned with 3 digits, stored as integer |
| CARD-EMBOSSED-NAME | X(50) | 30 | 50 | varchar(50) | PIC X(50) stored as text |
| CARD-EXPIRAION-DATE | X(10) | 80 | 10 | date | every source row parses as uuuu-MM-dd |
| CARD-ACTIVE-STATUS | X(01) | 90 | 1 | varchar(1) | PIC X(01) stored as text |

Primary key is `card_num`.

Alternate indexes

- `cards_card_acct_id_aix` (card_acct_id)

Foreign keys

- `cards_card_acct_id_fk` (card_acct_id) references `accounts` (acct_id)

Notes

- FILLER offset 91 length 59 is not stored

## card_xref

Cluster `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS`, copybook `CVACT03Y`, record `CARD-XREF-RECORD`, length 50, key offset 0 length 16. Data file `AWS.M2.CARDDEMO.CARDXREF.PS`.

| COBOL name | PIC | Offset | Length | SQL type | Reason |
| --- | --- | --- | --- | --- | --- |
| XREF-CARD-NUM | X(16) | 0 | 16 | varchar(16) | PIC X(16) stored as text |
| XREF-CUST-ID | 9(09) | 16 | 9 | integer | unsigned zoned with 9 digits, stored as integer |
| XREF-ACCT-ID | 9(11) | 25 | 11 | bigint | unsigned zoned with 11 digits, stored as bigint |

Primary key is `xref_card_num`.

Alternate indexes

- `card_xref_xref_acct_id_aix` (xref_acct_id)

Foreign keys

- `card_xref_xref_card_num_fk` (xref_card_num) references `cards` (card_num)
- `card_xref_xref_cust_id_fk` (xref_cust_id) references `customers` (cust_id)
- `card_xref_xref_acct_id_fk` (xref_acct_id) references `accounts` (acct_id)

Notes

- FILLER offset 36 length 14 is not stored

## transaction_types

Cluster `AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS`, copybook `CVTRA03Y`, record `TRAN-TYPE-RECORD`, length 60, key offset 0 length 2. Data file `AWS.M2.CARDDEMO.TRANTYPE.PS`.

| COBOL name | PIC | Offset | Length | SQL type | Reason |
| --- | --- | --- | --- | --- | --- |
| TRAN-TYPE | X(02) | 0 | 2 | varchar(2) | PIC X(02) stored as text |
| TRAN-TYPE-DESC | X(50) | 2 | 50 | varchar(50) | PIC X(50) stored as text |

Primary key is `tran_type`.

Alternate indexes

None.

Foreign keys

None.

Notes

- FILLER offset 52 length 8 is not stored

## transaction_categories

Cluster `AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS`, copybook `CVTRA04Y`, record `TRAN-CAT-RECORD`, length 60, key offset 0 length 6. Data file `AWS.M2.CARDDEMO.TRANCATG.PS`.

| COBOL name | PIC | Offset | Length | SQL type | Reason |
| --- | --- | --- | --- | --- | --- |
| TRAN-TYPE-CD | X(02) | 0 | 2 | varchar(2) | PIC X(02) stored as text |
| TRAN-CAT-CD | 9(04) | 2 | 4 | integer | unsigned zoned with 4 digits, stored as integer |
| TRAN-CAT-TYPE-DESC | X(50) | 6 | 50 | varchar(50) | PIC X(50) stored as text |

Primary key is `tran_type_cd`, `tran_cat_cd`.

Alternate indexes

None.

Foreign keys

- `transaction_categories_tran_type_cd_fk` (tran_type_cd) references `transaction_types` (tran_type)

Notes

- FILLER offset 56 length 4 is not stored

## disclosure_groups

Cluster `AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS`, copybook `CVTRA02Y`, record `DIS-GROUP-RECORD`, length 50, key offset 0 length 16. Data file `AWS.M2.CARDDEMO.DISCGRP.PS`.

| COBOL name | PIC | Offset | Length | SQL type | Reason |
| --- | --- | --- | --- | --- | --- |
| DIS-ACCT-GROUP-ID | X(10) | 0 | 10 | varchar(10) | PIC X(10) is not a date on every row |
| DIS-TRAN-TYPE-CD | X(02) | 10 | 2 | varchar(2) | PIC X(02) stored as text |
| DIS-TRAN-CAT-CD | 9(04) | 12 | 4 | integer | unsigned zoned with 4 digits, stored as integer |
| DIS-INT-RATE | S9(04)V99 | 16 | 6 | numeric(6,2) | signed zoned numeric(6,2) |

Primary key is `dis_acct_group_id`, `dis_tran_type_cd`, `dis_tran_cat_cd`.

Alternate indexes

None.

Foreign keys

- `disclosure_groups_dis_tran_type_cd_dis_tran_cat_cd_fk` (dis_tran_type_cd, dis_tran_cat_cd) references `transaction_categories` (tran_type_cd, tran_cat_cd)

Notes

- FILLER offset 22 length 28 is not stored

## transaction_category_balances

Cluster `AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS`, copybook `CVTRA01Y`, record `TRAN-CAT-BAL-RECORD`, length 50, key offset 0 length 17. Data file `AWS.M2.CARDDEMO.TCATBALF.PS`.

| COBOL name | PIC | Offset | Length | SQL type | Reason |
| --- | --- | --- | --- | --- | --- |
| TRANCAT-ACCT-ID | 9(11) | 0 | 11 | bigint | unsigned zoned with 11 digits, stored as bigint |
| TRANCAT-TYPE-CD | X(02) | 11 | 2 | varchar(2) | PIC X(02) stored as text |
| TRANCAT-CD | 9(04) | 13 | 4 | integer | unsigned zoned with 4 digits, stored as integer |
| TRAN-CAT-BAL | S9(09)V99 | 17 | 11 | numeric(11,2) | signed zoned numeric(11,2) |

Primary key is `trancat_acct_id`, `trancat_type_cd`, `trancat_cd`.

Alternate indexes

None.

Foreign keys

- `transaction_category_balances_trancat_acct_id_fk` (trancat_acct_id) references `accounts` (acct_id)
- `transaction_category_balances_trancat_type_cd_trancat_cd_fk` (trancat_type_cd, trancat_cd) references `transaction_categories` (tran_type_cd, tran_cat_cd)

Notes

- FILLER offset 28 length 22 is not stored

## transactions

Cluster `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS`, copybook `CVTRA05Y`, record `TRAN-RECORD`, length 350, key offset 0 length 16. Data file `AWS.M2.CARDDEMO.DALYTRAN.PS`.

| COBOL name | PIC | Offset | Length | SQL type | Reason |
| --- | --- | --- | --- | --- | --- |
| TRAN-ID | X(16) | 0 | 16 | varchar(16) | PIC X(16) stored as text |
| TRAN-TYPE-CD | X(02) | 16 | 2 | varchar(2) | PIC X(02) stored as text |
| TRAN-CAT-CD | 9(04) | 18 | 4 | integer | unsigned zoned with 4 digits, stored as integer |
| TRAN-SOURCE | X(10) | 22 | 10 | varchar(10) | PIC X(10) is not a date on every row |
| TRAN-DESC | X(100) | 32 | 100 | varchar(100) | PIC X(100) stored as text |
| TRAN-AMT | S9(09)V99 | 132 | 11 | numeric(11,2) | signed zoned numeric(11,2) |
| TRAN-MERCHANT-ID | 9(09) | 143 | 9 | integer | unsigned zoned with 9 digits, stored as integer |
| TRAN-MERCHANT-NAME | X(50) | 152 | 50 | varchar(50) | PIC X(50) stored as text |
| TRAN-MERCHANT-CITY | X(50) | 202 | 50 | varchar(50) | PIC X(50) stored as text |
| TRAN-MERCHANT-ZIP | X(10) | 252 | 10 | varchar(10) | PIC X(10) is not a date on every row |
| TRAN-CARD-NUM | X(16) | 262 | 16 | varchar(16) | PIC X(16) stored as text |
| TRAN-ORIG-TS | X(26) | 278 | 26 | timestamp(6) | every source row parses as uuuu-MM-dd HH:mm:ss.SSSSSS |
| TRAN-PROC-TS | X(26) | 304 | 26 | varchar(26) | PIC X(26) is not a timestamp on every row |

Primary key is `tran_id`.

Alternate indexes

- `transactions_tran_proc_ts_aix` (tran_proc_ts)

Foreign keys

- `transactions_tran_card_num_fk` (tran_card_num) references `card_xref` (xref_card_num)
- `transactions_tran_type_cd_tran_cat_cd_fk` (tran_type_cd, tran_cat_cd) references `transaction_categories` (tran_type_cd, tran_cat_cd)

Notes

- FILLER offset 330 length 20 is not stored

## users

Cluster `AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS`, copybook `CSUSR01Y`, record `SEC-USER-DATA`, length 80, key offset 0 length 8. Data file `AWS.M2.CARDDEMO.USRSEC.PS`.

| COBOL name | PIC | Offset | Length | SQL type | Reason |
| --- | --- | --- | --- | --- | --- |
| SEC-USR-ID | X(08) | 0 | 8 | varchar(8) | PIC X(08) stored as text |
| SEC-USR-FNAME | X(20) | 8 | 20 | varchar(20) | PIC X(20) stored as text |
| SEC-USR-LNAME | X(20) | 28 | 20 | varchar(20) | PIC X(20) stored as text |
| SEC-USR-PWD | X(08) | 48 | 8 | varchar(8) | PIC X(08) stored as text |
| SEC-USR-TYPE | X(01) | 56 | 1 | varchar(1) | PIC X(01) stored as text |
| SEC-USR-FILLER | X(23) | 57 | 23 | varchar(23) | PIC X(23) stored as text |

Primary key is `sec_usr_id`.

Alternate indexes

None.

Foreign keys

None.

Notes

None.
