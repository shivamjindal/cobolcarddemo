# Transaction types

An administrator lists transaction types, changes a description, or deletes a type from the list, and adds or edits one type on a second screen. A category that still points at a type blocks the delete. The same table can be maintained in batch with an input file.

## Sub-features

- `list` (`COTRTLIC`, CTLI) shows seven types a page, filters by type or description, and confirms an inline update or delete with F10.
- `maintain` (`COTRTUPC`, CTTU) finds a type, edits its description, adds a missing type, or deletes one. F5 saves and F4 deletes, each after a confirmation.
- `batch` (`MNTTRDB2`, `COBTUPDT`) reads an input file of add, update, delete, and comment records and applies them to `tran_types`. It is not in the daily or monthly chains. Control-M schedules the legacy job weekly.

## How to get to it (user POV)

- Sign on as an administrator. The admin menu shows option 5, Transaction Type List/Update, and option 6, Transaction Type Maintenance.
- On the list, F2 opens the add screen and F3 returns to the admin menu. On the add screen, F3 returns to whoever opened it.
- To run the batch job, launch `MNTTRDB2` with job parameter `input` set to the request file. Each line is an action (`A`, `U`, `D`, or `*`), a two-character type, and a description.

## Driving it with the specs and the harness

Preconditions: `scripts/doctor.sh` exits 0. The live app also needs `scripts/spring.sh up`.

- **Grounding.** `python3 characterization/online/validate_specs.py characterization/online/specs/COTRTLIC.yaml characterization/online/specs/COTRTUPC.yaml` exits 0. The cases are derived from source.
- **Replay.** `cd carddemo-java && ./gradlew test --tests com.carddemo.online.OnlineSpecReplayTest`. Read `[spec-replay] COTRTLIC` and `[spec-replay] COTRTUPC`.
- **Batch golden.** `python3 characterization/batch/harness.py --job MNTTRDB2` compares `MNTTRDB2/COBTUPDT.sysout` and `MNTTRDB2/TRANTYPE`. The full harness still reports `45/45`. `BatchGoldenTest` prints `[batch-golden] MNTTRDB2/COBTUPDT.sysout MATCHED`.
- **HTTP.** `GET /api/screens/COTRTLIC` and `GET /api/screens/COTRTUPC` after an admin sign-on. `POST` takes `{aid, fields}`.

## Gotchas

- A type filter of spaces or `00` means no type filter. Any other non-numeric type filter is rejected.
- Delete is refused while `tran_categories.tran_type_cd` references the type (`V6__tran_type_category_fk.sql`). The screen shows the legacy child-record message.
- GnuCOBOL cannot compile `EXEC SQL`. The harness replaces `COBTUPDT`'s three statements with calls to `characterization/batch/stubs/TRTYPE.cbl`.
- `COBTUPDT` does not clear its message area, so a later failure keeps the tail of the previous message. The golden pins that.
- A failed online update is reported as unsuccessful. The legacy program then marks the action saved, because the failure text overwrites the status flag first (`COTRTUPC-030`).
