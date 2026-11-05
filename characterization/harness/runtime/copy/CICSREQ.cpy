      * Request block filled by cicsprep.py before each CALL "CICSAPI".
       01  CICS-REQ.
           05  CICS-FN        PIC X(16).
           05  CICS-NAME      PIC X(8).
           05  CICS-MAPSET    PIC X(8).
           05  CICS-TRANSID   PIC X(4).
           05  CICS-LEN       PIC S9(8) COMP-5.
           05  CICS-KEYLEN    PIC S9(8) COMP-5.
           05  CICS-FLAGS     PIC X(80).
           05  CICS-ARGNAMES  PIC X(80).
           05  CICS-ARGLEN    PIC S9(8) COMP-5 OCCURS 8.
           05  CICS-RESP      PIC S9(8) COMP-5.
           05  CICS-RESP2     PIC S9(8) COMP-5.
