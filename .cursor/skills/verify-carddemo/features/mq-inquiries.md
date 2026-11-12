# MQ inquiries

COACCT01 and CODATE01 answer one MQ request each. COACCT01 reads an account from ACCTDAT. CODATE01 reports the system date and time. Neither program is a menu option.

## Sub-features

- `account-inquiry` (`COACCT01`, CDRA) reads ACCTDAT when the request is `INQA` plus a non-zero 11-digit account id, and puts the labeled account group on `CARD.DEMO.REPLY.ACCT`.
- `date-inquiry` (`CODATE01`, CDRD) puts `SYSTEM DATE` and `SYSTEM TIME` on `CARD.DEMO.REPLY.DATE`. The request text is not read.

## How to get to it (user POV)

On the mainframe, an MQ trigger starts the program. The trigger message names the input queue. The programs are not on the main menu (`app/cpy/COMEN02Y.cpy`) or the admin menu (`app/cpy/COADM02Y.cpy`).

In Java, put a text message on the request queue. COACCT01 listens on `carddemo.mq.account-request`, which defaults to `CARD.DEMO.REQUEST.ACCT`. CODATE01 listens on `carddemo.mq.date-request`, which defaults to `CARD.DEMO.REQUEST.DATE`. The reply carries the request correlation id. A failed account read arrives on `CARD.DEMO.ERROR`.

## Driving it with validate_specs.py and the spec replay

Preconditions: `scripts/doctor.sh` exits 0. The replay also needs Docker, because the test starts Postgres.

- **Grounding.** `python3 characterization/online/validate_specs.py characterization/online/specs/COACCT01.yaml characterization/online/specs/CODATE01.yaml` exits 0. The specs are derived from source, not executed.
- **Replay.** `cd carddemo-java && ./gradlew test --tests com.carddemo.online.MessageSpecReplayTest`. The `[spec-replay]` lines for COACCT01 and CODATE01 list each case as passed, overridden, or skipped.
- **Proof.** Cite the case id. Say "derived from source" for the YAML, and "replayed against Java" for a `[spec-replay]` line.

## Gotchas

- The module README at `app/app-vsam-mq/README.md` describes request type `ACCT` or `DATE`, a typed response, and queues named `CARDDEMO.REQUEST.QUEUE` and `CARDDEMO.RESPONSE.QUEUE`. The COBOL does not. The specs follow the COBOL.
- COACCT01 replies on the literal `CARD.DEMO.REPLY.ACCT`. CODATE01 replies on `CARD.DEMO.REPLY.DATE`. Both save MQMD-REPLYTOQ and never read it.
- Account amounts in the reply are zoned `PIC S9(10)V99`. A negative amount overpunches the last digit. Dates are the 10 characters stored on the account, `yyyy-MM-dd`.
- CODATE01 formats the clock as `MM-DD-YYYY` and `HH:MM:SS`.
- A READ failure is the only error-queue path these tests can reach. The port reports IOERR (`17`) and RESP2 `00000`. MQOPEN, MQGET, MQPUT, and MQCLOSE failures are listed in the spec notes and are not cases.
- A key that is not 11 digits abends the COBOL comparison. The port sends the invalid-parameters reply instead.
- The COBOL puts a 1000-byte buffer. The port sends the text without that trailing padding. The replay strips trailing spaces before it compares.
