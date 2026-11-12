#!/usr/bin/env python3
"""Drive a running CardDemo Spring instance through slice 1 over real HTTP and record every exchange.

Signs on through the OIDC flow (sign-on page, authorization server, code exchange), then walks the
admin menu and user admin screens through /api/screens. Exits non-zero on the first failed check.

Usage: drive_spring.py [--base http://localhost:8080] [--out DIR]
"""
import argparse
import http.cookiejar
import json
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Client:
    def __init__(self, base, transcript, label):
        self.base = base
        self.transcript = transcript
        self.label = label
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), NoRedirect)

    def request(self, method, path, body=None, headers=None):
        url = urllib.parse.urljoin(self.base, path)
        req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
        try:
            resp = self.opener.open(req)
        except urllib.error.HTTPError as e:
            resp = e
        text = resp.read().decode("utf-8", "replace")
        entry = {"session": self.label, "method": method, "url": url, "status": resp.status,
                 "location": resp.headers.get("Location")}
        if body is not None and b"PASSWD" not in body:
            entry["request"] = body.decode()
        if "json" in (resp.headers.get("Content-Type") or ""):
            entry["response"] = json.loads(text) if text else None
        self.transcript.append(entry)
        return resp.status, resp.headers.get("Location"), text, url

    def follow(self, method, path, body=None, headers=None):
        status, location, text, url = self.request(method, path, body, headers)
        while status in (301, 302, 303, 307) and location:
            status, location, text, url = self.request("GET", urllib.parse.urljoin(url, location))
        return status, text, url

    def xsrf(self):
        return next((c.value for c in self.jar if c.name == "XSRF-TOKEN"), "")

    def sign_on(self, user, password):
        status, page, url = self.follow("GET", "/")
        check(url.endswith("/signon"), f"{self.label}: unauthenticated / lands on the sign-on page", url)
        token = re.search(r'name="_csrf"\s+value="([^"]+)"', page)
        form = {"USERID": user, "PASSWD": password, "aid": "ENTER", "_csrf": token.group(1) if token else ""}
        return self.follow("POST", "/signon", urllib.parse.urlencode(form).encode(),
                           {"Content-Type": "application/x-www-form-urlencoded"})

    def screen(self, program):
        status, text, _ = self.follow("GET", f"/api/screens/{program}", headers={"Accept": "application/json"})
        check(status == 200, f"GET {program} is 200", status)
        return json.loads(text)

    def send(self, program, aid, **fields):
        body = json.dumps({"aid": aid, "fields": fields}).encode()
        status, text, url = self.follow("POST", f"/api/screens/{program}", body, {
            "Content-Type": "application/json", "Accept": "application/json", "X-XSRF-TOKEN": self.xsrf()})
        check(status == 200, f"{aid} on {program} is 200", f"{status} {text[:200]}")
        return json.loads(text)


results = []


def check(ok, what, detail=""):
    results.append(("PASS" if ok else "FAIL") + f"  {what}" + ("" if ok else f"  [{detail}]"))
    print(results[-1])
    if not ok:
        raise SystemExit(1)


def message(screen):
    return (screen.get("message") or {}).get("text")


def sql(base, query):
    port = urllib.parse.urlparse(base).port
    return subprocess.run(["docker", "exec", f"carddemo-pg-{port}", "psql", "-U", "carddemo", "-d", "carddemo",
                           "-tAc", query], capture_output=True, text=True).stdout.strip()


def stored_password(base, user):
    return sql(base, f"SELECT sec_usr_pwd FROM users WHERE sec_usr_id = '{user}'")


def drive_accounts(base, transcript):
    account = "00000000050"
    user = Client(base, transcript, "accounts")
    user.sign_on("USER0001", "PASSWORD")
    s = user.send("COMEN01C", "ENTER", OPTION="1")
    check(s["program"] == "COACTVWC", "option 1 opens account view", s)
    s = user.send("COACTVWC", "ENTER", ACCTSID=account)
    check(s["fields"].get("ACCTSID") == account and s["fields"].get("ACSTNUM"),
          "account view shows the account with its customer", s["fields"])

    zip_before = sql(base, f"SELECT acct_addr_zip FROM accounts WHERE acct_id = {int(account)}")
    s = user.send("COACTVWC", "PF3")
    s = user.send("COMEN01C", "ENTER", OPTION="2")
    s = user.send("COACTUPC", "ENTER", ACCTSID=account)
    check(s["fields"].get("INFOMSG") == "Update account details presented above.",
          "account update fetches the account for editing", s["fields"])
    fields = {k: v for k, v in s["fields"].items() if k not in ("INFOMSG", "ERRMSG")}
    # No seeded customer passes every legacy edit (FICO, area codes, SSN, state and zip), so type valid ones.
    valid_customer = {"ACTSSN1": "123", "ACTSSN2": "45", "ACTSSN3": "6789", "ACSTFCO": "700", "ACSSTTE": "NY",
                      "ACSZIPC": "10001", "ACSCTRY": "USA", "ACSPH1A": "212", "ACSPH1B": "555", "ACSPH1C": "0100",
                      "ACSPH2A": "212", "ACSPH2B": "555", "ACSPH2C": "0101"}
    edited = {**fields, **valid_customer, "AADDGRP": "DRIVEGRP"}
    s = user.send("COACTUPC", "ENTER", **edited)
    check(s["fields"].get("INFOMSG") == "Changes validated.Press F5 to save", "a valid change asks for F5", s["fields"])
    s = user.send("COACTUPC", "PF5", **edited)
    check(s["fields"].get("INFOMSG") == "Changes committed to database", "F5 saves the account", s["fields"])
    check(sql(base, f"SELECT acct_group_id || '|' || acct_addr_zip FROM accounts WHERE acct_id = {int(account)}")
          == f"DRIVEGRP|{zip_before}", "the group id goes to acct_group_id and the zip is kept", zip_before)


def drive_cards(base, transcript):
    card, account = "0500024453765740", "00000000050"
    user = Client(base, transcript, "cards")
    user.sign_on("USER0001", "PASSWORD")
    menu = user.screen("COMEN01C")
    check("03. Credit Card List" in menu["fields"].values(), "main menu shows the card options", menu["fields"])

    s = user.send("COMEN01C", "ENTER", OPTION="3")
    check(s["program"] == "COCRDLIC" and not s["fields"].get("CRDNUM1")
          and message(s) == "ENTER AN ACCOUNT NUMBER TO LIST ITS CARDS",
          "a regular user's card list is empty until an account is typed", s)
    s = user.send("COCRDLIC", "ENTER", ACCTSID=account)
    check(s["fields"].get("CRDNUM1") == card and not s["fields"].get("CRDNUM2"),
          f"filtering by account {account} lists only its card", s["fields"])

    cvv = sql(base, f"SELECT card_cvv_cd FROM cards WHERE card_num = '{card}'")
    s = user.send("COCRDLIC", "ENTER", ACCTSID=account, CRDSEL1="U")
    check(s["program"] == "COCRDUPC" and s["fields"].get("CARDSID") == card, "U opens card update with the card", s)
    fields = {k: s["fields"].get(k, "") for k in ("ACCTSID", "CARDSID", "CRDNAME", "CRDSTCD", "EXPMON", "EXPYEAR", "EXPDAY")}
    s = user.send("COCRDUPC", "ENTER", **{**fields, "CRDNAME": "Drive Renamed"})
    check(s["fields"].get("INFOMSG") == "Changes validated.Press F5 to save", "a valid change asks for F5", s["fields"])
    s = user.send("COCRDUPC", "PF5", **{**fields, "CRDNAME": "Drive Renamed"})
    check(s["fields"].get("INFOMSG") == "Changes committed to database", "F5 saves the card", s)
    check(sql(base, f"SELECT card_embossed_name || '|' || card_cvv_cd FROM cards WHERE card_num = '{card}'")
          == f"Drive Renamed|{cvv}", "the new name is stored and the CVV is kept", cvv)

    s = user.send("COCRDUPC", "ENTER")
    check(s["program"] == "COCRDLIC" and s["fields"].get("ACCTSID") == account and s["fields"].get("CRDNUM1") == card,
          "after the save any key returns to the list with its filter kept", s["fields"])

    admin = Client(base, transcript, "cards-admin")
    admin.sign_on("ADMIN001", "PASSWORD")
    s = admin.screen("COCRDLIC")
    check(all(s["fields"].get(f"CRDNUM{i}") for i in range(1, 8)), "an admin lists all cards, seven a page", s["fields"])


def drive(base, transcript):
    admin = Client(base, transcript, "admin")
    status, _, url = admin.sign_on("admin001", "password")
    check(url.endswith("/screens/COADM01C"), "lower-case admin001/password signs on to the admin menu", url)

    menu = admin.screen("COADM01C")
    check(menu["fields"].get("OPTN001") == "01. User List (Security)", "admin menu lists option 01", menu["fields"])
    check(not any("Db2" in v for v in menu["fields"].values()), "Db2 options (slice 5a) are hidden", menu["fields"])
    s = admin.send("COADM01C", "ENTER", OPTION="5")
    check(message(s) == "This option is not installed ...", "typing hidden option 5 says not installed", message(s))

    users = admin.send("COADM01C", "ENTER", OPTION="1")
    check(users["program"] == "COUSR00C" and users["fields"].get("USRID01") == "ADMIN001",
          "option 1 opens the user list at ADMIN001", users["fields"])

    admin.send("COUSR00C", "PF3")
    added = admin.send("COADM01C", "ENTER", OPTION="2")
    check(added["program"] == "COUSR01C", "option 2 opens user add", added["program"])
    s = admin.send("COUSR01C", "ENTER", FNAME="Drive", LNAME="Test", USERID="drive01", PASSWD="secret1", USRTYPE="u")
    check(message(s) == "User DRIVE01 has been added ...", "add stores DRIVE01 upper-cased", message(s))
    pwd = stored_password(base, "DRIVE01")
    check(pwd.startswith("$2") and len(pwd) == 60, "DRIVE01 password is stored as a bcrypt hash", pwd[:7])

    user = Client(base, transcript, "drive01")
    status, page, url = user.sign_on("DRIVE01", "SECRET1")
    check(url.endswith("/screens/COMEN01C"), "new type-U user signs on to the main menu (password case-insensitive)", url)
    status, _, _ = user.follow("GET", "/api/screens/COADM01C", headers={"Accept": "application/json"})
    check(status == 403, "a type-U user gets 403 on the admin menu", status)

    wrong = Client(base, transcript, "wrong")
    status, page, url = wrong.sign_on("ADMIN001", "NOPE")
    check("Wrong Password. Try again ..." in page and url.endswith("/signon"), "wrong password stays on sign-on", url)

    admin.send("COUSR01C", "PF3")
    admin.send("COADM01C", "ENTER", OPTION="3")
    s = admin.send("COUSR02C", "ENTER", USRIDIN="drive01")
    check(s["fields"].get("LNAME") == "Test" and s["fields"].get("PASSWD", "") == "",
          "update loads DRIVE01 without exposing the password", s["fields"])
    s = admin.send("COUSR02C", "PF5", USRIDIN="DRIVE01", FNAME="Drive", LNAME="Tested", PASSWD="", USRTYPE="U")
    check(message(s) == "User DRIVE01 has been updated ...", "PF5 updates the last name, blank password kept", message(s))
    again = Client(base, transcript, "drive01-again")
    check(again.sign_on("DRIVE01", "secret1")[2].endswith("/screens/COMEN01C"), "kept password still signs on")

    admin.send("COUSR02C", "PF3")
    page = admin.send("COADM01C", "ENTER", OPTION="1")
    row = next(k[-2:] for k, v in page["fields"].items() if re.fullmatch(r"USRID\d\d", k) and v == "DRIVE01")
    s = admin.send("COUSR00C", "ENTER", **{f"SEL00{row}": "d", f"USRID{row}": "DRIVE01"})
    check(s["program"] == "COUSR03C" and message(s) == "Press PF5 key to delete this user ...",
          "selecting d on the DRIVE01 row opens delete with the user loaded", s)
    s = admin.send("COUSR03C", "PF5", USRIDIN="DRIVE01")
    check(message(s) == "User DRIVE01 has been deleted ...", "PF5 deletes DRIVE01", message(s))
    check(stored_password(base, "DRIVE01") == "", "DRIVE01 row is gone")

    back = admin.send("COUSR03C", "PF3")
    check(back["program"] == "COUSR00C", "PF3 on delete returns to the user list it came from", back["program"])
    admin.send("COUSR00C", "PF3")
    body = json.dumps({"aid": "PF3", "fields": {}}).encode()
    status, text, _ = admin.follow("POST", "/api/screens/COADM01C", body, {
        "Content-Type": "application/json", "Accept": "application/json", "X-XSRF-TOKEN": admin.xsrf()})
    check(status == 200 and json.loads(text).get("signedOff") is True, "PF3 on the admin menu signs off", text[:200])
    status, _, url = admin.follow("GET", "/screens/COADM01C")
    check(url.endswith("/signon"), "after sign-off the admin menu needs a new sign-on", url)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--out", type=Path)
    args = ap.parse_args()
    transcript = []
    try:
        drive(args.base, transcript)
        drive_accounts(args.base, transcript)
        drive_cards(args.base, transcript)
    finally:
        if args.out:
            args.out.mkdir(parents=True, exist_ok=True)
            (args.out / "http-transcript.json").write_text(json.dumps(transcript, indent=2))
            (args.out / "drive.txt").write_text("\n".join(results) + "\n")
    print(f"{sum(r.startswith('PASS') for r in results)} checks passed")


if __name__ == "__main__":
    sys.exit(main())
