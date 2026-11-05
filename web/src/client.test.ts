import { expect, test } from "vitest";
import { parseMap, parseReply, readCookie } from "./api";
import { cosgnMap, cosgnReply } from "./cosgn0a.fixture";
import { actionFor, keyOf, label } from "./keys";
import { buildActBody, displayValue, gridPlacement, rememberTyped } from "./model";
import type { MapField, MapLayout } from "./model";

function screenReply() {
  const reply = parseReply(cosgnReply);
  if (reply.kind !== "screen") throw new Error("screen");
  return reply;
}

function field(layout: MapLayout, name: string): MapField {
  const found = layout.fields.find((item) => item.name === name);
  if (!found) throw new Error(name);
  return found;
}

test("maps aid keys and leaves typing alone", () => {
  expect(keyOf({ key: "Enter", shiftKey: false })).toBe("ENTER");
  expect(keyOf({ key: "NumpadEnter", shiftKey: false })).toBe("ENTER");
  expect(keyOf({ key: "Escape", shiftKey: false })).toBe("CLEAR");
  expect(keyOf({ key: "F3", shiftKey: false })).toBe("PF3");
  expect(keyOf({ key: "F12", shiftKey: false })).toBe("PF12");
  expect(keyOf({ key: "F1", shiftKey: true })).toBe("PF13");
  expect(keyOf({ key: "F12", shiftKey: true })).toBe("PF24");
  expect(keyOf({ key: "a", shiftKey: false })).toBeNull();
  expect(keyOf({ key: "F13", shiftKey: false })).toBeNull();
  expect(keyOf({ key: "Tab", shiftKey: false })).toBeNull();
});

test("resolves a key through the reply and falls back to invalid-key", () => {
  const reply = screenReply();
  expect(actionFor(reply.actions, "ENTER")).toBe("sign-on");
  expect(actionFor(reply.actions, "PF3")).toBe("exit");
  expect(actionFor(reply.actions, "PF1")).toBe("invalid-key");
  expect(actionFor(reply.actions, "CLEAR")).toBe("invalid-key");
  const enter = reply.actions[0];
  const exit = reply.actions[1];
  if (!enter || !exit) throw new Error("actions");
  expect(label(enter)).toBe("Enter sign-on");
  expect(label(exit)).toBe("F3 exit");
});

test("tracks only typed unprotected fields and sends an erased field as empty", () => {
  const layout = parseMap(cosgnMap);
  const reply = screenReply();
  expect(displayValue(reply.fields, {}, "PASSWD")).toBe("________");
  expect(displayValue(reply.fields, { PASSWD: "" }, "PASSWD")).toBe("");
  expect(displayValue(reply.fields, {}, "USERID")).toBe("");
  expect(displayValue(reply.fields, {}, "SYSID")).toBe("");
  expect(field(layout, "SYSID").initial).toBe("        ");

  const userid = field(layout, "USERID");
  const passwd = field(layout, "PASSWD");
  expect(rememberTyped({}, userid, "123456789")).toEqual({ USERID: "12345678" });
  expect(rememberTyped({}, userid, "ab\ncd")).toEqual({ USERID: "abcd" });
  expect(rememberTyped({}, passwd, "")).toEqual({ PASSWD: "" });

  const typed = rememberTyped(rememberTyped({}, userid, "USER0001"), passwd, "");
  expect(buildActBody({ turn: 1, action: "sign-on", key: "ENTER", fields: typed })).toEqual({
    turn: 1,
    fields: { USERID: "USER0001", PASSWD: "" },
  });
  expect(buildActBody({ turn: 1, action: "sign-on", key: "ENTER", fields: {} })).toEqual({
    turn: 1,
    fields: {},
  });
  expect(
    buildActBody({ turn: 4, action: "invalid-key", key: "CLEAR", fields: { USERID: "A" } }),
  ).toEqual({ turn: 4, fields: {}, key: "CLEAR" });
  expect(
    buildActBody({ turn: 4, action: "invalid-key", key: "PF1", fields: { USERID: "A" } }),
  ).toEqual({ turn: 4, fields: { USERID: "A" }, key: "PF1" });
  expect(buildActBody({ turn: 4, action: "exit", key: "PA2", fields: { USERID: "A" } })).toEqual({
    turn: 4,
    fields: {},
  });
});

test("places COSGN0A fields on the data cell, one column past the attribute byte", () => {
  const layout = parseMap(cosgnMap);
  const userid = field(layout, "USERID");
  expect(gridPlacement(userid.row, userid.col, userid.length)).toEqual({
    gridRow: 19,
    gridColumn: "44 / span 8",
  });
  const passwd = field(layout, "PASSWD");
  expect(passwd.initial).toBe("________");
  expect(gridPlacement(passwd.row, passwd.col, passwd.length)).toEqual({
    gridRow: 20,
    gridColumn: "44 / span 8",
  });
  const errmsg = field(layout, "ERRMSG");
  expect(gridPlacement(errmsg.row, errmsg.col, errmsg.length)).toEqual({
    gridRow: 23,
    gridColumn: "2 / span 78",
  });
  const trnname = field(layout, "TRNNAME");
  expect(gridPlacement(trnname.row, trnname.col, trnname.length)).toEqual({
    gridRow: 1,
    gridColumn: "9 / span 4",
  });
  const userLabel = layout.literals.find((lit) => lit.text.startsWith("User ID"));
  if (!userLabel) throw new Error("User ID label");
  expect(userLabel.text).toHaveLength(13);
  expect(userLabel.color).toBe("TURQUOISE");
  expect(gridPlacement(userLabel.row, userLabel.col, userLabel.text.length)).toEqual({
    gridRow: 19,
    gridColumn: "30 / span 13",
  });
});

test("parses screen, text and ended replies, and map color aliases", () => {
  expect(parseReply({ kind: "ended", turn: 2, text: "Thank you" })).toEqual({
    kind: "ended",
    turn: 2,
    text: "Thank you",
  });
  expect(
    parseReply({
      kind: "text",
      turn: 3,
      screen: "pending",
      text: "COCRDLIC is not migrated yet.",
      actions: [{ name: "back", key: "PF3" }],
    }),
  ).toEqual({
    kind: "text",
    turn: 3,
    screen: "pending",
    text: "COCRDLIC is not migrated yet.",
    actions: [{ name: "back", key: "PF3" }],
  });
  const cleared = parseReply({ ...cosgnReply, cursor: null });
  if (cleared.kind !== "screen") throw new Error("screen");
  expect(cleared.cursor).toBeNull();
  expect(cleared.protectedFields).toEqual(["TRNNAME", "SYSID", "ERRMSG"]);
  expect(cleared.hiddenFields).toEqual(["PASSWD"]);
  expect(() => parseReply({ kind: "nope" })).toThrow();
  expect(() => parseReply({ ...cosgnReply, protectedFields: undefined })).toThrow();

  const aliased = parseMap({
    mapset: "COSGN00",
    name: "COSGN0A",
    fields: [
      { name: "TITLE01", row: 1, col: 22, length: 40, attributes: ["ASKIP"], color: "YELLO" },
      { name: "X", row: 1, col: 1, length: 1, attributes: [] },
    ],
    literals: [{ row: 1, col: 2, text: "Tran :", color: "TURQ" }],
  });
  expect(aliased.fields[0]?.color).toBe("YELLOW");
  expect(aliased.fields[1]?.color).toBe("DEFAULT");
  expect(aliased.literals[0]?.color).toBe("TURQUOISE");
  expect(() =>
    parseMap({
      mapset: "COSGN00",
      name: "COSGN0A",
      fields: [{ name: "X", row: 1, col: 1, length: 1, attributes: ["UNPROT"], color: "ORANGE" }],
      literals: [],
    }),
  ).toThrow();
});

test("reads the XSRF cookie", () => {
  expect(readCookie("theme=dark; XSRF-TOKEN=abc%3Ddef", "XSRF-TOKEN")).toBe("abc=def");
  expect(readCookie("", "XSRF-TOKEN")).toBe("");
});
