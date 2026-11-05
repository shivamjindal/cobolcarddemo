      * DL/I interface block the EXEC DLI translator generates (DLZDIB).
       01  DLZDIB.
           05  DIBVER         PIC X(2).
           05  DIBSTAT        PIC X(2).
           05  DIBSEGM        PIC X(8).
           05  DIBFIL01       PIC X.
           05  DIBFIL02       PIC X.
           05  DIBSEGLV       PIC X(2).
           05  DIBKFBL        PIC S9(4) COMP.
           05  DIBDBDNM       PIC X(8).
           05  DIBDBORG       PIC X(8).
           05  DIBFIL03       PIC X(6).
