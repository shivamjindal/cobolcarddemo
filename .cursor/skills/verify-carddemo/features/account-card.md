# Account and card

A signed-on user looks up an account with its customer, edits account and customer details through a confirm step, lists cards, opens one card, and edits a card's name, status and expiry. Updates rewrite ACCTDATA, CUSTDATA and CARDDATA.

## Sub-features

- `account-view` (COACTVWC, main option 1) validates the 11-digit account ID, reads ACCTDATA, CARDXREF (alternate key) and CUSTDATA, and shows account and customer fields.
- `account-update` (COACTUPC, option 2) fetches an account, edits every account and customer field with per-field validation (first failure's message shown, every failing field red), asks for confirmation with PF5, rewrites both records.
- `card-list` (COCRDLIC, option 3) pages cards 7 at a time with optional account and card filters; `S` opens detail, `U` opens update.
- `card-detail` (COCRDSLC, option 4) shows one card by account and card number.
- `card-update` (COCRDUPC, option 5) fetches a card, edits name, status and expiry, confirms with PF5, rewrites CARDDATA.

## How to get to it (user POV)

- Sign on as `USER0001`, then type 1 to 5 on the main menu.
- From the card list, type `S` or `U` next to a card.

## Driving it with run.py

Preconditions: baseline; cases in `characterization/cases/account-card/`.

- **All of it.** Run `python3 characterization/run.py --feature account-view --feature account-update --feature card-list --feature card-detail --feature card-update`. Expect 119 PASS.
- **An account rewrite.** Run `--feature account-update`; the saved cases pin `files.ACCTDATA.updated` (and the group ID landing in `ACCT-ADDR-ZIP`, a legacy defect).
- **A card rewrite.** Run `--feature card-update`; the saved cases pin `files.CARDDATA.updated`, including the CVV blanked on every save.

## Gotchas

- Seed account 1 fails its own update validation (FICO 274, NC with zip 12546, area code 373). Update cases that must save edit those fields first or use another account.
- Pinned legacy quirks: card detail and card update read by card number only, so a mismatched account still shows the card; card update upper-cases the name on fetch and writes it back on any save; card update blanks the CVV; account update writes the group ID into the zip field; invalid PF keys and early PF5/PF12 act as ENTER.
- Card list shows every card to every user; there is no per-user filtering.
- Lock and concurrent-change paths (`DATA-WAS-CHANGED-BEFORE-UPDATE` and friends) need data changed mid-journey and are not covered.
- `account-view-not-in-acctdat` uses `"match": "subset"` because the amount and date fields show uninitialized storage.
- Where no field asks for the cursor, the cursor falls back to the map's IC field; card-list row 1 depends on that.
