      *> COBOL port of app/asm/COBDATFT.asm.
      *> Type 1: YYYYMMDD in, YYYY-MM-DD out. Type 2: YYYY-MM-DD in,
      *> YYYYMMDD out. Anything else sets the error message only.
      *> The assembler's MVC COERMSG,=C'INVALID INPUT' copies 38 bytes
      *> from a 13-byte literal, so bytes 14-38 are literal-pool garbage
      *> on z/OS; this port leaves them untouched.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COBDATFT.
       DATA DIVISION.
       LINKAGE SECTION.
       01  COREC.
           05  COINTYPE            PIC X.
           05  COINPDT             PIC X(20).
           05  COOUTYPE            PIC X.
           05  COOUTDT             PIC X(20).
           05  COERMSG             PIC X(38).
       PROCEDURE DIVISION USING COREC.
           EVALUATE TRUE
             WHEN COINTYPE = '1'
               IF COINPDT(5:1) = '-' OR COOUTYPE = '2'
                   MOVE 'INVALID INPUT' TO COERMSG(1:13)
               ELSE
                   MOVE COINPDT(1:4) TO COOUTDT(1:4)
                   MOVE '-'          TO COOUTDT(5:1)
                   MOVE COINPDT(5:2) TO COOUTDT(6:2)
                   MOVE '-'          TO COOUTDT(8:1)
                   MOVE COINPDT(7:2) TO COOUTDT(9:2)
               END-IF
             WHEN COINTYPE = '2'
               IF COOUTYPE = '1'
                   MOVE 'INVALID INPUT' TO COERMSG(1:13)
               ELSE
                   MOVE COINPDT(1:4) TO COOUTDT(1:4)
                   MOVE COINPDT(6:2) TO COOUTDT(5:2)
                   MOVE COINPDT(9:2) TO COOUTDT(7:2)
               END-IF
             WHEN OTHER
               MOVE 'INVALID INPUT' TO COERMSG(1:13)
           END-EVALUATE
           MOVE 0 TO RETURN-CODE
           GOBACK.
