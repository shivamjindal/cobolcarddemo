# carddemo-java

Spring Boot 3 / Java 21 port of CardDemo. It holds the data layer (Postgres tables for the VSAM KSDS clusters, derived from the copybooks in `../app/cpy`, and a loader for the sample data in `../app/data`) and the online screens migrated so far, which are sign-on, both menus and user admin (COSGN00C, COMEN01C, COADM01C, COUSR00C-03C).

## Commands

```bash
./gradlew test                      # unit tests + Testcontainers round-trip (needs Docker)
./gradlew generateDdl               # rewrite V1__vsam_tables.sql from the copybooks
docker compose up -d                # local Postgres on :5432 (carddemo/carddemo)
./gradlew bootRun --args='--carddemo.seed.dir=../app/data/EBCDIC'
./gradlew bootRun --args='--carddemo.seed.dir=../app/data/ASCII --carddemo.seed.format=ASCII'
```

`DdlGeneratorTest` fails when the committed migration drifts from the copybooks.

The app listens on 8080 and is ready when `GET /actuator/health` returns `{"status":"UP"}` (health and info are the only actuator endpoints, both public). Open http://localhost:8080/ and sign on as `ADMIN001` or `USER0001`, password `PASSWORD`. Browse at `carddemo.base-url`, which is the OIDC issuer and redirect URI. It defaults to `http://localhost:${server.port}`, so `--server.port=8181` alone works on localhost, and any other host name needs `--carddemo.base-url` to match or the sign-on cookies get lost.

## Sign-on

The app embeds an OIDC provider (Spring Authorization Server) whose login page is COSGN00C, and signs users in as its OIDC client. Two filter chains:

1. Identity provider: the SAS endpoints and `/signon`. `SignonController` runs COSGN00C, authenticates against the `users` table and resumes the pending `/oauth2/authorize` request. Its security context lives under its own session key, so signing on here does not sign you into the app.
2. App: everything else. `oauth2Login` with the `carddemo` registration; the client secret is random per boot and never configured. The login success handler puts a `Conversation` (user and type from the `usr_type` claim) in the session and redirects to `/screens/COADM01C` for type A or `/screens/COMEN01C` otherwise.

Unauthenticated pages redirect into that flow; unauthenticated `/api/**` calls get 401. PF3 on a menu or the sign-on screen invalidates the session, which ends both sign-ons. A non-admin gets 403 from admin-only programs.

The curl dance (cookie jar, follow redirects):

```bash
curl -s -c jar -b jar -L -o signon.html http://localhost:8080/          # ends at /signon
token=$(grep -o 'name="_csrf" value="[^"]*"' signon.html | cut -d'"' -f4)
curl -s -c jar -b jar -L -o menu.html -w '%{url_effective}\n' \
  -d USERID=admin001 -d PASSWD=password -d aid=ENTER --data-urlencode _csrf=$token \
  http://localhost:8080/signon                                            # ends at /screens/COADM01C
xsrf=$(awk '$6=="XSRF-TOKEN"{print $7}' jar)
curl -s -b jar -c jar http://localhost:8080/api/screens/COADM01C
curl -s -b jar -c jar -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' \
  -d '{"aid":"ENTER","fields":{"OPTION":"1"}}' http://localhost:8080/api/screens/COADM01C   # 303 to /api/screens/COUSR00C
```

## Case rule

User IDs, passwords and user types are upper-cased wherever they are typed. That covers sign-on, add, update, the list filter and the update/delete lookups (`users.Credentials.normalize`). Passwords are stored as bcrypt of the upper-cased value (`V2__hash_user_passwords.sql` converted the seed rows; `SeedLoader` hashes on load). Names are stored as typed. A user type must be A or U.

## Screens

A migrated program is a `ScreenProgram` bean. `arrive(conversation)` runs when another program transfers to it (GET), and `receive(conversation, aid, fields)` runs when the user presses a key (POST). Both return an `Outcome`, which is `Show(fields, message, cursor)`, `Transfer(program)` or `SignOff(message)`. Fields are keyed by BMS base name (`USRIDIN`, `SEL0001`), the program's own state goes in the conversation (`conversation.put(new UserListPage(...))`), and `conversation.from()` is the program that transferred here. A program with no bean is not installed, so menus hide it and its URLs are 404.

Two adapters serve every program, so a new screen adds only its `ScreenProgram` and one template:

- HTML: `GET /screens/{program}` and `POST /screens/{program}` (form fields plus `aid` from the pressed button). Every POST answers 303; a redisplay travels as a flash attribute.
- REST: `GET /api/screens/{program}` returns `{program, transid, fields, message: {text, kind}, cursor}`. `POST` takes `{aid, fields}` and returns the same, a 303 to `/api/screens/{to}` for a transfer, or `{signedOff: true, message}`. Send the `XSRF-TOKEN` cookie back as `X-XSRF-TOKEN`.

The template is `templates/<PROGRAM>.html` and uses the `layout :: screen(title, body, keys)` fragment, the `fields :: field(name, label, size)` input, and one `<button name="aid" value="PFn">` per key, ENTER first. Each input carries a `<label>` and `data-testid` equal to its BMS name, the message line is `data-testid="ERRMSG"`, and the cursor field gets `autofocus`. `static/js/screen.js` maps F3, F4, F5, F7, F8 and F12 to those buttons.

## Spec replay

`OnlineSpecReplayTest` replays `../characterization/online/specs/<PROGRAM>.yaml` for every installed program through `/api/screens`, and `SignonReplayTest` replays COSGN00C through the full OIDC flow on a running server. Deliberate differences are entries in `src/test/resources/spec-overrides/<PROGRAM>.yaml`: a one-sentence `reason` plus `given`, `when` or `then` keys that replace the spec's, an optional `then.status`, or `skip: true`. The test task prints a `[spec-replay]` tally per program. When a later slice installs a program a menu points at, delete the override that expects "not installed".

## Layout

- `copybook/` parses a single-record copybook into fields with byte offsets.
- `vsam/VsamDataset` lists each KSDS with its copybook, sample files, LRECL, primary key and alternate index byte ranges (from `app/catlg/LISTCAT.txt` and the `DEFINE ALTERNATEINDEX` steps in `app/jcl`).
- `vsam/Table` and `vsam/Column` turn a dataset into DDL and decode records into row values.
- `vsam/SeedLoader` truncates the tables and reloads them in one transaction.
- `online/` is the screen engine (`Screens`), both adapters, `Conversation` and the two menus.
- `signon/` holds both security chains and COSGN00C; `users/` holds the users table and COUSR00C-03C.

## Mapping rules

| COBOL | Postgres |
| --- | --- |
| `PIC X(n)` | `varchar(n) NOT NULL`, trailing spaces trimmed |
| `PIC 9(n)` | `smallint` / `integer` / `bigint` by width, with a `CHECK` for the PIC range |
| `PIC S9(p)V9(s)` (DISPLAY or COMP-3) | `numeric(p+s, s)`; unsigned adds `CHECK (>= 0)` |
| `PIC X(10)` dates listed in `VsamDataset` | `date`, blank becomes `NULL` |
| `PIC X(26)` transaction timestamps | `timestamp(6)`, accepts `yyyy-MM-dd HH:mm:ss.ffffff` and DB2 `yyyy-MM-dd-HH.mm.ss.ffffff` |
| VSAM key / AIX | `PRIMARY KEY` / non-unique index |
| `FILLER` | dropped |

Column names are the COBOL names in snake case, typos included (`acct_expiraion_date`), so they stay greppable against the legacy source.
