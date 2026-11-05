      * PAUTDTL1 as the harness stores it: the parent PAUTSUM0 key, then
      * the 200-byte segment laid out by CIPAUDTY. Bytes 1-14 are the IMS
      * concatenated key.
       01  PAUTDTL1-RECORD.
           05  PAUT-PARENT-ACCT-ID          PIC S9(11) COMP-3.
           05  PA-AUTHORIZATION-KEY.
               10 PA-AUTH-DATE-9C           PIC S9(05) COMP-3.
               10 PA-AUTH-TIME-9C           PIC S9(09) COMP-3.
           05  PA-AUTH-ORIG-DATE            PIC  X(06).
           05  PA-AUTH-ORIG-TIME            PIC  X(06).
           05  PA-CARD-NUM                  PIC  X(16).
           05  PA-AUTH-TYPE                 PIC  X(04).
           05  PA-CARD-EXPIRY-DATE          PIC  X(04).
           05  PA-MESSAGE-TYPE              PIC  X(06).
           05  PA-MESSAGE-SOURCE            PIC  X(06).
           05  PA-AUTH-ID-CODE              PIC  X(06).
           05  PA-AUTH-RESP-CODE            PIC  X(02).
           05  PA-AUTH-RESP-REASON          PIC  X(04).
           05  PA-PROCESSING-CODE           PIC  9(06).
           05  PA-TRANSACTION-AMT           PIC S9(10)V99 COMP-3.
           05  PA-APPROVED-AMT              PIC S9(10)V99 COMP-3.
           05  PA-MERCHANT-CATAGORY-CODE    PIC  X(04).
           05  PA-ACQR-COUNTRY-CODE         PIC  X(03).
           05  PA-POS-ENTRY-MODE            PIC  9(02).
           05  PA-MERCHANT-ID               PIC  X(15).
           05  PA-MERCHANT-NAME             PIC  X(22).
           05  PA-MERCHANT-CITY             PIC  X(13).
           05  PA-MERCHANT-STATE            PIC  X(02).
           05  PA-MERCHANT-ZIP              PIC  X(09).
           05  PA-TRANSACTION-ID            PIC  X(15).
           05  PA-MATCH-STATUS              PIC  X(01).
           05  PA-AUTH-FRAUD                PIC  X(01).
           05  PA-FRAUD-RPT-DATE            PIC  X(08).
           05  FILLER                       PIC  X(17).
