      * Row image of CARDDEMO.TRANSACTION_TYPE in the harness fixtures.
      * TR_DESCRIPTION is VARCHAR(50): its length is kept beside the text
      * because Db2 keeps trailing blanks a program writes.
       01  TRANSACTION-TYPE-ROW.
           05  TR-TYPE                 PIC X(02).
           05  TR-DESCRIPTION-LEN      PIC 9(02).
           05  TR-DESCRIPTION          PIC X(50).
