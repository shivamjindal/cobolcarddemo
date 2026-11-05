      * Request block filled by cicsprep.py before each CALL "DLIEXEC".
      * SEG1/FLD1/OP1 and SEG2/FLD2/OP2 are the SEGMENT and WHERE options
      * from the root down; the WHERE values are passed by reference.
       01  DLI-REQ.
           05  DLI-FN         PIC X(4).
           05  DLI-PCBNO      PIC S9(4) COMP-5.
           05  DLI-PSB        PIC X(8).
           05  DLI-SEG1       PIC X(8).
           05  DLI-FLD1       PIC X(8).
           05  DLI-OP1        PIC X(2).
           05  DLI-SEG2       PIC X(8).
           05  DLI-FLD2       PIC X(8).
           05  DLI-OP2        PIC X(2).
           05  DLI-IOLEN      PIC S9(8) COMP-5.
           05  DLI-W1LEN      PIC S9(8) COMP-5.
           05  DLI-W2LEN      PIC S9(8) COMP-5.
