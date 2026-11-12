      *> Stand-in for the Language Environment CEE3ABD abend service.
      *> z/OS would end the step with user abend U<code>; here the step
      *> ends with return code 16 after printing the abend code.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEE3ABD.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-CODE                 PIC 9(4).
       LINKAGE SECTION.
       01  ABCODE                  PIC S9(9) BINARY.
       01  TIMING                  PIC S9(9) BINARY.
       PROCEDURE DIVISION USING ABCODE TIMING.
           IF ADDRESS OF ABCODE = NULL
               DISPLAY 'CEE3ABD: ABEND (NO CODE)'
           ELSE
               MOVE ABCODE TO WS-CODE
               DISPLAY 'CEE3ABD: ABEND U' WS-CODE
           END-IF
           MOVE 16 TO RETURN-CODE
           STOP RUN.
