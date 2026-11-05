      * MQOD, object descriptor, version 1 (168 bytes), IBM layout.
         10 MQOD.
           15 MQOD-STRUCID           PIC X(4)  VALUE 'OD  '.
           15 MQOD-VERSION           PIC S9(9) BINARY VALUE 1.
           15 MQOD-OBJECTTYPE        PIC S9(9) BINARY VALUE 1.
           15 MQOD-OBJECTNAME        PIC X(48) VALUE SPACES.
           15 MQOD-OBJECTQMGRNAME    PIC X(48) VALUE SPACES.
           15 MQOD-DYNAMICQNAME      PIC X(48) VALUE 'AMQ.*'.
           15 MQOD-ALTERNATEUSERID   PIC X(12) VALUE SPACES.
