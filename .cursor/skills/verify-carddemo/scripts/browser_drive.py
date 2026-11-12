#!/usr/bin/env python3
"""Drive the slice 1 and 2 screens in Chrome with the keyboard (ENTER and F-keys) and save a screenshot per screen.

Needs `pip install --user --break-system-packages playwright` and a Chrome binary ($CHROME, default
/usr/local/bin/google-chrome). Exits non-zero on the first failed check.

Usage: browser_drive.py [--base http://localhost:8080] --out DIR
"""
import argparse
import os
import sys
from pathlib import Path

from playwright.sync_api import expect, sync_playwright


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    chrome = os.environ.get("CHROME", "/usr/local/bin/google-chrome")
    passed = []

    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=chrome)
        page = browser.new_page(viewport={"width": 1000, "height": 720})
        testid = page.get_by_test_id

        def shot(name, what):
            page.screenshot(path=args.out / f"{len(passed) + 1:02d}-{name}.png")
            passed.append(what)
            print(f"PASS  {what}")

        page.goto(args.base)
        expect(testid("PGMNAME")).to_have_text("COSGN00C")
        expect(testid("USERID")).to_be_focused()
        shot("signon", "unauthenticated visit shows the sign-on screen with the cursor on User ID")

        testid("USERID").fill("ADMIN001")
        testid("PASSWD").fill("WRONG")
        page.keyboard.press("Enter")
        expect(testid("ERRMSG")).to_have_text("Wrong Password. Try again ...")
        expect(testid("PASSWD")).to_be_focused()
        shot("signon-wrong-password", "wrong password shows the legacy message with the cursor on Password")

        testid("USERID").fill("admin001")
        testid("PASSWD").fill("password")
        page.keyboard.press("Enter")
        expect(testid("PGMNAME")).to_have_text("COADM01C")
        expect(testid("OPTN004")).to_have_text("04. User Delete (Security)")
        expect(testid("OPTN005")).to_have_count(0)
        shot("admin-menu", "sign-on through OIDC lands on the admin menu with the Db2 options hidden")

        testid("OPTION").fill("1")
        page.keyboard.press("Enter")
        expect(testid("PGMNAME")).to_have_text("COUSR00C")
        expect(testid("USRID01")).to_have_text("ADMIN001")
        shot("user-list", "option 1 lists users from ADMIN001")

        page.keyboard.press("F3")
        expect(testid("PGMNAME")).to_have_text("COADM01C")
        testid("OPTION").fill("2")
        page.keyboard.press("Enter")
        expect(testid("PGMNAME")).to_have_text("COUSR01C")
        testid("FNAME").fill("Browser")
        testid("LNAME").fill("Drive")
        testid("USERID").fill("browse01")
        testid("PASSWD").fill("pw")
        testid("USRTYPE").fill("u")
        page.keyboard.press("Enter")
        expect(testid("ERRMSG")).to_have_text("User BROWSE01 has been added ...")
        expect(testid("FNAME")).to_be_focused()
        shot("user-added", "user add stores BROWSE01 and clears the form")

        page.keyboard.press("F3")
        testid("OPTION").fill("4")
        page.keyboard.press("Enter")
        testid("USRIDIN").fill("browse01")
        page.keyboard.press("Enter")
        expect(testid("ERRMSG")).to_have_text("Press PF5 key to delete this user ...")
        expect(testid("LNAME")).to_have_text("Drive")
        shot("user-delete-review", "user delete loads BROWSE01 for review")
        page.keyboard.press("F5")
        expect(testid("ERRMSG")).to_have_text("User BROWSE01 has been deleted ...")
        shot("user-deleted", "F5 deletes BROWSE01")

        page.keyboard.press("F3")
        expect(testid("PGMNAME")).to_have_text("COADM01C")
        page.keyboard.press("F3")
        expect(testid("PGMNAME")).to_have_text("COSGN00C")
        shot("signed-off", "F3 from the admin menu signs off back to sign-on")

        testid("USERID").fill("USER0001")
        testid("PASSWD").fill("PASSWORD")
        page.keyboard.press("Enter")
        expect(testid("PGMNAME")).to_have_text("COMEN01C")
        expect(testid("OPTN005")).to_have_text("05. Credit Card Update")
        expect(testid("OPTN006")).to_have_count(0)
        shot("main-menu", "a regular user lands on the main menu listing the installed account and card options")

        testid("OPTION").fill("1")
        page.keyboard.press("Enter")
        expect(testid("PGMNAME")).to_have_text("COACTVWC")
        testid("ACCTSID").fill("00000000050")
        page.keyboard.press("Enter")
        expect(testid("ACSFNAM")).to_have_text("Aniya")
        shot("account-view", "account view shows account 50 with its customer")

        page.keyboard.press("F3")
        testid("OPTION").fill("3")
        page.keyboard.press("Enter")
        expect(testid("PGMNAME")).to_have_text("COCRDLIC")
        expect(testid("CRDNUM1")).to_have_count(0)
        expect(testid("ERRMSG")).to_have_text("ENTER AN ACCOUNT NUMBER TO LIST ITS CARDS")
        shot("card-list-prompt", "a regular user's card list asks for an account before listing cards")
        testid("ACCTSID").fill("00000000050")
        page.keyboard.press("Enter")
        expect(testid("CRDNUM1")).to_have_text("0500024453765740")
        expect(testid("CRDNUM2")).to_have_count(0)
        shot("card-list-filtered", "filtering by account 50 lists only its card")

        testid("CRDSEL1").fill("U")
        page.keyboard.press("Enter")
        expect(testid("PGMNAME")).to_have_text("COCRDUPC")
        expect(testid("CARDSID")).to_have_value("0500024453765740")
        shot("card-update", "U opens card update with the selected card")
        page.keyboard.press("F3")
        expect(testid("PGMNAME")).to_have_text("COCRDLIC")
        expect(testid("ACCTSID")).to_have_value("00000000050")
        expect(testid("CRDNUM1")).to_have_text("0500024453765740")
        shot("card-list-return", "F3 from update returns to the list with its filter kept")
        browser.close()

    (args.out / "browser.txt").write_text("".join(f"PASS  {p}\n" for p in passed))
    print(f"{len(passed)} browser checks passed")


if __name__ == "__main__":
    sys.exit(main())
