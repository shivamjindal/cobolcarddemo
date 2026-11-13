      *> Stand-in for IMS DL/I: the harness compiles CBPAUP0C with each
      *> EXEC DLI command turned into CALL 'DLISTUB' USING function,
      *> segment name, I/O area and DIBSTAT. The database is the file on
      *> DD PAUTDB, one 201-byte record a segment in hierarchic order: a
      *> type byte (S for PAUTSUM0, D for PAUTDTL1) and the segment. It is
      *> read on the first call and written back at each CHKP, so only
      *> checkpointed work is kept, as in a BMP.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. DLISTUB.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT DB-FILE ASSIGN TO PAUTDB
               ORGANIZATION SEQUENTIAL FILE STATUS DB-STAT.
       DATA DIVISION.
       FILE SECTION.
       FD  DB-FILE.
       01  DB-REC.
           05  DB-TYPE             PIC X.
           05  DB-SEG              PIC X(200).
       WORKING-STORAGE SECTION.
       01  DB-STAT                 PIC XX.
       01  LOADED                  PIC X VALUE 'N'.
       01  SEG-COUNT               PIC 9(4) COMP VALUE 0.
       01  CURRENT-SEG             PIC 9(4) COMP VALUE 0.
       01  PARENT                  PIC 9(4) COMP VALUE 0.
       01  I                       PIC 9(4) COMP.
       01  SEGS.
           05  SEG OCCURS 2001 TIMES.
               10  SEG-TYPE        PIC X.
               10  SEG-DATA        PIC X(200).
               10  SEG-GONE        PIC X.
       LINKAGE SECTION.
       01  L-FUNC                  PIC X(4).
       01  L-SEGMENT               PIC X(8).
       01  L-AREA                  PIC X(200).
       01  L-STATUS                PIC XX.
       PROCEDURE DIVISION USING L-FUNC L-SEGMENT L-AREA L-STATUS.
           IF LOADED = 'N'
               PERFORM LOAD-DB
           END-IF
           MOVE SPACES TO L-STATUS
           EVALUATE L-FUNC
             WHEN 'GN  '
               PERFORM NEXT-SUMMARY
             WHEN 'GNP '
               PERFORM NEXT-DETAIL
             WHEN 'DLET'
               PERFORM DELETE-SEGMENT
             WHEN 'CHKP'
               PERFORM WRITE-DB
             WHEN OTHER
               MOVE 'AD' TO L-STATUS
           END-EVALUATE
           GOBACK.

      *> GN PAUTSUM0: the next summary after the current position.
       NEXT-SUMMARY.
           PERFORM VARYING I FROM CURRENT-SEG BY 1 UNTIL I >= SEG-COUNT
               OR (SEG-TYPE(I + 1) = 'S' AND SEG-GONE(I + 1) = 'N')
           END-PERFORM
           IF I >= SEG-COUNT
               MOVE 'GB' TO L-STATUS
           ELSE
               ADD 1 TO I
               MOVE I TO CURRENT-SEG PARENT
               MOVE SEG-DATA(I)(1:100) TO L-AREA(1:100)
           END-IF.

      *> GNP PAUTDTL1: the next detail under the current summary.
       NEXT-DETAIL.
           PERFORM VARYING I FROM CURRENT-SEG BY 1 UNTIL I >= SEG-COUNT
               OR SEG-TYPE(I + 1) = 'S' OR SEG-GONE(I + 1) = 'N'
           END-PERFORM
           IF I >= SEG-COUNT OR SEG-TYPE(I + 1) = 'S'
               MOVE 'GE' TO L-STATUS
           ELSE
               ADD 1 TO I
               MOVE I TO CURRENT-SEG
               MOVE SEG-DATA(I) TO L-AREA
           END-IF.

      *> DLET of the detail last read, or of the summary and its details.
       DELETE-SEGMENT.
           IF L-SEGMENT = 'PAUTDTL1'
               MOVE 'Y' TO SEG-GONE(CURRENT-SEG)
           ELSE
               MOVE 'Y' TO SEG-GONE(PARENT)
               PERFORM VARYING I FROM PARENT BY 1 UNTIL I >= SEG-COUNT
                   OR SEG-TYPE(I + 1) = 'S'
                   MOVE 'Y' TO SEG-GONE(I + 1)
               END-PERFORM
           END-IF.

       LOAD-DB.
           MOVE 'Y' TO LOADED
           OPEN INPUT DB-FILE
           IF DB-STAT = '00'
               PERFORM UNTIL DB-STAT NOT = '00'
                   READ DB-FILE
                   IF DB-STAT = '00'
                       ADD 1 TO SEG-COUNT
                       MOVE DB-TYPE TO SEG-TYPE(SEG-COUNT)
                       MOVE DB-SEG TO SEG-DATA(SEG-COUNT)
                       MOVE 'N' TO SEG-GONE(SEG-COUNT)
                   END-IF
               END-PERFORM
               CLOSE DB-FILE
           END-IF.

       WRITE-DB.
           OPEN OUTPUT DB-FILE
           PERFORM VARYING I FROM 1 BY 1 UNTIL I > SEG-COUNT
               IF SEG-GONE(I) = 'N'
                   MOVE SEG-TYPE(I) TO DB-TYPE
                   MOVE SEG-DATA(I) TO DB-SEG
                   WRITE DB-REC
               END-IF
           END-PERFORM
           CLOSE DB-FILE.
