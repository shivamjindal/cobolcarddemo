      *> characterization harness: TRANSACTION_TYPE as an indexed file.
      *> SQLCODE 0, 100 (not found), -803 (duplicate), and -532 when a category still uses the type.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. TRTYPE.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT TYPE-FILE ASSIGN TO KSFILE
               ORGANIZATION IS INDEXED
               ACCESS MODE IS DYNAMIC
               RECORD KEY IS TYPE-CODE
               FILE STATUS IS TYPE-STATUS.
           SELECT CAT-FILE ASSIGN TO CATFILE
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS CAT-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD TYPE-FILE.
       01 TYPE-RECORD.
           05 TYPE-CODE PIC X(2).
           05 TYPE-DESC PIC X(50).
           05 TYPE-REST PIC X(8).
       FD CAT-FILE.
       01 CAT-CODE PIC X(2).
       WORKING-STORAGE SECTION.
       01 TYPE-STATUS PIC XX.
       01 CAT-STATUS PIC XX.
       01 CAT-HIT PIC 9 VALUE 0.
       LINKAGE SECTION.
       01 LK-OP PIC X.
       01 LK-CODE PIC X(2).
       01 LK-DESC PIC X(50).
       01 LK-SQLCODE PIC S9(9) COMP.
       PROCEDURE DIVISION USING LK-OP LK-CODE LK-DESC LK-SQLCODE.
           OPEN I-O TYPE-FILE.
           EVALUATE LK-OP
               WHEN "I"
                   MOVE LK-CODE TO TYPE-CODE
                   MOVE LK-DESC TO TYPE-DESC
                   MOVE SPACES TO TYPE-REST
                   WRITE TYPE-RECORD
                   IF TYPE-STATUS = "22"
                       MOVE -803 TO LK-SQLCODE
                   ELSE
                       MOVE 0 TO LK-SQLCODE
                   END-IF
               WHEN "U"
                   MOVE LK-CODE TO TYPE-CODE
                   READ TYPE-FILE
                   IF TYPE-STATUS = "23"
                       MOVE 100 TO LK-SQLCODE
                   ELSE
                       MOVE LK-DESC TO TYPE-DESC
                       REWRITE TYPE-RECORD
                       MOVE 0 TO LK-SQLCODE
                   END-IF
               WHEN "D"
                   PERFORM CATEGORY-EXISTS
                   IF CAT-HIT = 1
                       MOVE -532 TO LK-SQLCODE
                   ELSE
                       MOVE LK-CODE TO TYPE-CODE
                       DELETE TYPE-FILE RECORD
                       IF TYPE-STATUS = "23"
                           MOVE 100 TO LK-SQLCODE
                       ELSE
                           MOVE 0 TO LK-SQLCODE
                       END-IF
                   END-IF
           END-EVALUATE.
           CLOSE TYPE-FILE.
           GOBACK.

       CATEGORY-EXISTS.
           MOVE 0 TO CAT-HIT.
           OPEN INPUT CAT-FILE.
           PERFORM UNTIL CAT-STATUS NOT = "00"
               READ CAT-FILE
                   AT END MOVE "10" TO CAT-STATUS
               END-READ
               IF CAT-STATUS = "00" AND CAT-CODE = LK-CODE
                   MOVE 1 TO CAT-HIT
               END-IF
           END-PERFORM.
           CLOSE CAT-FILE.
