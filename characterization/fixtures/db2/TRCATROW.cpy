      * Row image of CARDDEMO.TRANSACTION_TYPE_CATEGORY in the harness
      * fixtures. TRC_CAT_DATA is VARCHAR(50), kept with its length.
       01  TRANSACTION-TYPE-CATEGORY-ROW.
           05  TRC-TYPE-CODE           PIC X(02).
           05  TRC-TYPE-CATEGORY       PIC X(04).
           05  TRC-CAT-DATA-LEN        PIC 9(02).
           05  TRC-CAT-DATA            PIC X(50).
