      *> Plays the role of JCL EXEC PGM=<name>,PARM='<parm>': builds the
      *> halfword-length PARM block z/OS passes and calls the program.
      *> Usage: RUNPGM <program> [parm]
       IDENTIFICATION DIVISION.
       PROGRAM-ID. RUNPGM.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-PROGRAM              PIC X(30).
       01  WS-PARM-TEXT            PIC X(100).
       01  JCL-PARM.
           05  PARM-LENGTH         PIC S9(4) COMP.
           05  PARM-DATA           PIC X(100).
       PROCEDURE DIVISION.
           ACCEPT WS-PROGRAM FROM ARGUMENT-VALUE
           ACCEPT WS-PARM-TEXT FROM ARGUMENT-VALUE
           MOVE WS-PARM-TEXT TO PARM-DATA
           MOVE FUNCTION LENGTH(FUNCTION TRIM(WS-PARM-TEXT TRAILING))
             TO PARM-LENGTH
           IF WS-PARM-TEXT = SPACES
               MOVE 0 TO PARM-LENGTH
           END-IF
           CALL WS-PROGRAM USING JCL-PARM
           STOP RUN.
