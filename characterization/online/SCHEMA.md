# Online program specs

One YAML file per CICS program in `specs/<PROGRAM>.yaml`. These are **derived from reading the COBOL, BMS, and copybook source**. None of them was executed: CardDemo's online programs need CICS, which this repo cannot run. `validate_specs.py` checks that every spec is grounded in source. A Java slice proves parity by loading these files and running each case against its own endpoints.

## Shape

```yaml
program: COSGN00C            # program id, matches app/cbl/<program>.cbl
transaction: CC00            # CICS transid from app/csd/CARDDEMO.CSD
screen:
  mapset: COSGN00            # app/bms/<mapset>.bms and app/cpy-bms/<mapset>.CPY
  map: COSGN0A
evidence: derived            # always "derived"; nothing here was executed
sources:                     # every file a case's source/message may cite
  - app/cbl/COSGN00C.cbl
  - app/cpy/CSUSR01Y.cpy
files:                       # CICS datasets the program touches (CSD FILE names)
  - name: USRSEC
    ops: [READ]              # READ, READ-UPDATE, WRITE, REWRITE, DELETE, STARTBR, READNEXT, READPREV, ENDBR
    key: SEC-USR-ID
navigation:                  # every program-to-program transfer the source can perform
  - to: COADM01C
    via: XCTL                # XCTL, LINK, RETURN-TRANSID, SEND-TEXT
    when: user type is admin ('A')
notes:                       # optional free text: quirks, dead code, latent bugs worth preserving or fixing on purpose
  - ...
cases:
  - id: COSGN00C-001         # <program>-NNN, unique
    summary: blank user id is rejected
    given:
      entry: reenter         # first-time (EIBCALEN = 0), reenter (pseudo-conversational return), xctl-from (arrived via XCTL with a COMMAREA)
      commarea: {}           # COMMAREA fields that matter (CARDDEMO-COMMAREA names from app/cpy/COCOM01Y.cpy or program-local ones)
      files:                 # dataset preconditions, record fields by copybook name
        USRSEC:
          - {SEC-USR-ID: ADMIN001, SEC-USR-PWD: PASSWORD, SEC-USR-TYPE: A}
      file_conditions:       # optional, forces a CICS RESP on a file command, for error branches
        - {file: USRSEC, op: READ, resp: IOERR}   # resp is a CICS condition name: NOTFND, DUPREC, DUPKEY, IOERR, NOTOPEN, DISABLED, LENGERR, INVREQ, OTHER
    when:
      aid: ENTER             # ENTER, CLEAR, PF1..PF12, PA1..PA3, OTHER (any other key)
      fields:                # input fields by BMS copybook name (the ...I field)
        USERIDI: ""
        PASSWDI: PASSWORD
    then:
      message: "Please enter User ID ..."   # exact text placed on the screen's message field, null when none
      message_field: ERRMSGO                # optional, defaults to ERRMSGO
      cursor: USERIDL                       # optional, field whose length was set to -1
      fields: {}                            # optional, output fields by copybook name (the ...O field) and expected value
      transfer: null                        # or {via: XCTL, to: COADM01C} or {via: RETURN-TRANSID, to: CC00}
      commarea: {}                          # optional, COMMAREA fields set on exit
      file_effects: []                      # [{file: USRSEC, op: WRITE, record: {...}}]; [] asserts no writes
    source:
      file: app/cbl/COSGN00C.cbl
      lines: [117, 121]                     # inclusive range that implements this outcome
```

## Rules the validator enforces

- Top-level keys `program, transaction, evidence, sources, files, navigation, cases` exist and `evidence` is `derived`.
- Each `sources` path exists. Each `source.file` is listed in `sources` and `source.lines` lies inside that file.
- Every non-null `then.message` appears in the concatenated text of the cited sources. A message built at runtime writes the variable part as `{name}`, e.g. `"User {SEC-USR-ID} has been added ..."`, and each literal fragment of 3 or more characters must appear. An empty `{}` marks where STRING joins two separate literals with nothing between them, so a replay treats it as the empty string.
- When `screen` is set, every key in `when.fields`, `then.fields`, `then.cursor`, and `then.message_field` is a field in `app/cpy-bms/<mapset>.CPY`.
- `files[].name` and every `file_effects[].file`, `given.files` key, and `given.file_conditions[].file` is a `DEFINE FILE` in `app/csd/CARDDEMO.CSD`. `file_conditions` ops and resps come from the fixed lists above.
- `transfer.to` and `navigation[].to` name either a program in `app/cbl/` or a transid in the CSD.
- Case ids are unique and start with the program id.

The validator only proves a spec is grounded, never that it is complete or that the outcome logic is read correctly. Treat a spec as a reviewed claim about the source, not as an observed behavior.
