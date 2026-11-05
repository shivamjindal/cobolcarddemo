      * MQTM, trigger message, version 1 (684 bytes), IBM layout.
         10 MQTM.
           15 MQTM-STRUCID           PIC X(4)   VALUE 'TM  '.
           15 MQTM-VERSION           PIC S9(9)  BINARY VALUE 1.
           15 MQTM-QNAME             PIC X(48)  VALUE SPACES.
           15 MQTM-PROCESSNAME       PIC X(48)  VALUE SPACES.
           15 MQTM-TRIGGERDATA       PIC X(64)  VALUE SPACES.
           15 MQTM-APPLTYPE          PIC S9(9)  BINARY VALUE 0.
           15 MQTM-APPLID            PIC X(256) VALUE SPACES.
           15 MQTM-ENVDATA           PIC X(128) VALUE SPACES.
           15 MQTM-USERDATA          PIC X(128) VALUE SPACES.
