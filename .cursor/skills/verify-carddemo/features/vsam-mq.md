# MQ inquiries (VSAM and MQ)

The optional extension `vsam-mq`, `app/app-vsam-mq`. Two triggered programs answer request messages without a terminal: COACCT01 looks an account up in ACCTDATA and CODATE01 reports the system date and time. Each reads every message on its trigger queue under syncpoint and puts one reply per message, with the request's correlation id. Both features run on the `legacy` and `java` targets.

## Sub-features

- `mq-account-inquiry` COACCT01, transid `CDRA`. A request is `INQA` plus an 11-digit account id. A found account is answered with its ACCTDATA fields on `CARD.DEMO.REPLY.ACCT` (amounts zoned with an overpunched sign, such as `00000004920{`). A missing account, an account id of zero or not numeric, or a function other than `INQA` is answered `INVALID REQUEST PARAMETERS ACCT ID : <key>` (with `FUNCTION : <function>` when the request itself is rejected).
- `mq-system-date` CODATE01, transid `CDRD`. Any message is answered `SYSTEM DATE : MM-DD-YYYYSYSTEM TIME : HH:MM:SS` on `CARD.DEMO.REPLY.DATE`.
- Failures go to `CARD.DEMO.ERROR`, and the task ends normally.

## How to get to it (user POV)

- Put a request on the trigger queue. Legacy cases use `CARDDEMO.REQUEST.QUEUE` for both transids. On Java the production triggers are `CARDDEMO.REQUEST.QUEUE` for CDRA and `CARDDEMO.REQUEST.DATE` for CDRD (the source names no queue for CODATE01, so that name is the port's choice).
- On Java an admin puts and reads messages over HTTP: `POST /api/mq/queues/{queue}/messages` `{"data","correlId","replyTo"}`, `GET /api/mq/queues/{queue}/messages`, `POST /api/mq/queues/{queue}/get` (204 when empty). The `/mq` page of the React app only sends authorization requests (see [authorization.md](./authorization.md)).

## Driving it with run.py

Preconditions: baseline; cases in `characterization/cases/vsam-mq/`. Inside tmux, run every command as `env -u LD_LIBRARY_PATH python3 ...`.

- **Both.** Run `python3 characterization/run.py --feature mq-account-inquiry --feature mq-system-date`. Expect 4 PASS (account inquiry 3, system date 1).
- **Install the extension.** Each suite has `"install": ["vsam-mq"]`, which installs COACCT01 and CODATE01 with transids CDRA and CDRD (from `app/app-vsam-mq/csd`). Without it the transids are not defined, and the legacy harness cannot start them.
- **The mq step.** `{"do": {"mq": {"queue": "CARDDEMO.REQUEST.QUEUE", "trigger": "CDRA", "messages": [{"data": "INQA00000000001", "reply_to": "", "correl_id": "ACCT0001"}]}}}` puts the messages and runs the trigger once. The observation is `mq` per queue put to (`data`, `correl_id`), `unread`, `queues`, `files`, `abend`. Several messages in one step are answered in order by one task.
- **Java slice.** Add `--target java`. The beans are `vsammq.AccountInquiry` (CDRA) and `vsammq.SystemDateInquiry` (CDRD), both `mq.TriggeredProgram`s built on `vsammq.MqInquiryProgram`. The adapter posts the step to `POST /characterization/mq`.

## Gotchas

- COACCT01 says `CICS RETREIVE` in its source and swaps its open flags. Neither is observable through MQ, so no golden pins them.
- The clock is fixed at `2026-11-05 10:00:00`, so every date reply is `SYSTEM DATE : 11-05-2026SYSTEM TIME : 10:00:00`.
- MQGET with wait returns 2033 at once under the harness and under Java's `characterization` profile (`carddemo.mq.max-wait` PT0S); production Java waits up to PT5S.
