      * One CARDDEMO.AUTHFRDS row as the harness stores it, a text record.
      * Bytes 1-42 are the primary key CARD_NUM, AUTH_TS. AUTH_TS and
      * FRAUD_RPT_DATE hold Db2 external formats, numbers are DISPLAY.
       01  AUTHFRDS-RECORD.
           05  CARD-NUM                     PIC X(16).
           05  AUTH-TS                      PIC X(26).
           05  AUTH-TYPE                    PIC X(04).
           05  CARD-EXPIRY-DATE             PIC X(04).
           05  MESSAGE-TYPE                 PIC X(06).
           05  MESSAGE-SOURCE               PIC X(06).
           05  AUTH-ID-CODE                 PIC X(06).
           05  AUTH-RESP-CODE               PIC X(02).
           05  AUTH-RESP-REASON             PIC X(04).
           05  PROCESSING-CODE              PIC X(06).
           05  TRANSACTION-AMT              PIC S9(10)V99.
           05  APPROVED-AMT                 PIC S9(10)V99.
           05  MERCHANT-CATAGORY-CODE       PIC X(04).
           05  ACQR-COUNTRY-CODE            PIC X(03).
           05  POS-ENTRY-MODE               PIC S9(04).
           05  MERCHANT-ID                  PIC X(15).
           05  MERCHANT-NAME                PIC X(22).
           05  MERCHANT-CITY                PIC X(13).
           05  MERCHANT-STATE               PIC X(02).
           05  MERCHANT-ZIP                 PIC X(09).
           05  TRANSACTION-ID               PIC X(15).
           05  MATCH-STATUS                 PIC X(01).
           05  AUTH-FRAUD                   PIC X(01).
           05  FRAUD-RPT-DATE               PIC X(10).
           05  ACCT-ID                      PIC S9(11).
           05  CUST-ID                      PIC S9(09).
