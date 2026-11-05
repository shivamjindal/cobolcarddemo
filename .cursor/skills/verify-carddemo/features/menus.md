# Menus

After sign-on a regular user sees the main menu (11 options) and an admin sees the admin menu (6 options). The user types an option number and presses ENTER to transfer to that function; invalid numbers, unassigned keys and uninstalled options are reported on the menu.

## Sub-features

- `menu-display` lists the options with the cursor on `OPTION`.
- `menu-validate` rejects blank, zero, non-numeric and out-of-range options with `Please enter a valid option number...` and echoes the option as two digits.
- `menu-route` transfers to the program in `COMEN02Y` / `COADM02Y` for the option.
- `menu-not-installed` main option 11 (COPAUS0C) and admin options 5 and 6 (Db2 programs) report not installed.
- `menu-exit` PF3 returns to sign-on; other keys show the invalid key message.

## How to get to it (user POV)

- Sign on as `USER0001` for the main menu or `ADMIN001` for the admin menu.
- PF3 from most function screens returns to the menu that started them.

## Driving it with run.py

Preconditions: baseline; cases in `characterization/cases/user-admin/COMEN01C.json` and `COADM01C.json`.

- **Main menu.** Run `python3 characterization/run.py --feature main-menu`. Expect 10 PASS.
- **Admin menu.** Run `--feature admin-menu`. Expect 10 PASS.
- **One route.** Run `--case admin-menu-option-1-user-list`. The golden's map is `COUSR0A` with ten users listed.
- **Java slice.** Add `--target java` to either command. Expect `java: 10 PASS` for each. In the React app, sign on, type the option in `OPTION` and press Enter. Over HTTP the menu actions are `select` (ENTER) and `sign-off` (PF3), on screens `main-menu` and `admin-menu`.

## Gotchas

- The main menu's admin-only check (`No access - Admin Only option...`) is unreachable with the shipped option table: every option is type U. It is not characterized.
- Option 11's message depends on whether COPAUS0C is installed. The harness does not build the optional IMS/MQ authorization modules, so the golden shows not installed.
- Admin option 5 goes through `HANDLE CONDITION PGMIDERR`; the message is green, not red.
- Java: "installed" means "ported" (`ProgramCatalog`). Main options 1-5 reach the ported account and card programs (see [account-card.md](./account-card.md)). Options 6-10 transfer to `PendingProgramScreen`, a text reply naming the unported program, with PF3 back to the menu. Options 11 and admin 5-6 keep the legacy not-installed messages.
