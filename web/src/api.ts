import { actionFor, isKey } from "./keys";
import type { ActionRef, Key } from "./keys";
import { buildActBody } from "./model";
import type { BmsColor, MapField, MapLayout, MapLiteral } from "./model";

export type { ActionRef, Key } from "./keys";
export type { MapField, MapLayout, MapLiteral } from "./model";

export type ScreenReply = {
  kind: "screen";
  turn: number;
  screen: string;
  map: { mapset: string; name: string };
  fields: Readonly<Record<string, string>>;
  colors: Readonly<Record<string, BmsColor>>;
  /** Every field the user cannot type into now. Programs change protection at run time, so the map's attributes are not enough. */
  protectedFields: readonly string[];
  hiddenFields: readonly string[];
  cursor: string | null;
  actions: readonly ActionRef[];
};

export type TextReply = {
  kind: "text";
  turn: number;
  screen: string;
  text: string;
  actions: readonly ActionRef[];
};

export type EndedReply = {
  kind: "ended";
  turn: number;
  text: string;
};

export type Reply = ScreenReply | TextReply | EndedReply;

export type ActResult =
  | { kind: "ok"; reply: Reply }
  | { kind: "conflict"; reply: Reply }
  | { kind: "unauthorized" }
  | { kind: "problem"; status: number; message: string };

const mapCache = new Map<string, Promise<MapLayout>>();

export function readCookie(header: string, name: string): string {
  for (const part of header.split(";")) {
    const trimmed = part.trim();
    const eq = trimmed.indexOf("=");
    if (eq === -1) continue;
    if (trimmed.slice(0, eq) !== name) continue;
    return decodeURIComponent(trimmed.slice(eq + 1));
  }
  return "";
}

export async function currentSession(): Promise<Reply | null> {
  const res = await fetch("/api/session", {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(await readProblem(res));
  return parseReply(await readJson(res));
}

export async function loadOrStart(): Promise<Reply> {
  const current = await currentSession();
  if (current) return current;
  return startSession();
}

export async function startSession(transid = "CC00"): Promise<Reply> {
  return postSession(transid, true);
}

export async function act(input: {
  screen: string;
  actions: readonly ActionRef[];
  turn: number;
  key: Key;
  fields: Readonly<Record<string, string>>;
}): Promise<ActResult> {
  const action = actionFor(input.actions, input.key);
  const body = buildActBody({
    turn: input.turn,
    action,
    key: input.key,
    fields: input.fields,
  });
  const res = await post(
    `/api/screens/${encodeURIComponent(input.screen)}/${encodeURIComponent(action)}`,
    body,
  );
  if (res.status === 200) return { kind: "ok", reply: parseReply(await readJson(res)) };
  if (res.status === 409) return { kind: "conflict", reply: parseReply(await readJson(res)) };
  if (res.status === 401) return { kind: "unauthorized" };
  return { kind: "problem", status: res.status, message: await readProblem(res) };
}

export function fetchMap(name: string): Promise<MapLayout> {
  const hit = mapCache.get(name);
  if (hit) return hit;
  const pending = requestMap(name).catch((err: unknown) => {
    mapCache.delete(name);
    throw err;
  });
  mapCache.set(name, pending);
  return pending;
}

export function parseReply(input: unknown): Reply {
  if (!isRecord(input)) throw new Error("reply");
  if (input.kind === "ended") return parseEnded(input);
  if (input.kind === "screen") return parseScreen(input);
  if (input.kind === "text") return parseText(input);
  throw new Error("reply.kind");
}

export function parseMap(input: unknown): MapLayout {
  if (!isRecord(input)) throw new Error("map");
  if (!Array.isArray(input.fields) || !Array.isArray(input.literals)) throw new Error("map fields");
  return {
    mapset: requiredName(input, "mapset"),
    name: requiredName(input, "name"),
    fields: input.fields.map(parseField),
    literals: input.literals.map(parseLiteral),
  };
}

async function requestMap(name: string): Promise<MapLayout> {
  const res = await fetch(`/api/maps/${encodeURIComponent(name)}`, {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
  if (!res.ok) throw new Error(await readProblem(res));
  const layout = parseMap(await readJson(res));
  if (layout.name !== name) throw new Error(`map ${name} came back as ${layout.name}`);
  return layout;
}

async function postSession(transid: string, retry: boolean): Promise<Reply> {
  if (csrfToken() === "") await currentSession();
  const res = await post("/api/session", { transid });
  // A missing or rotated CSRF cookie fails this POST. One GET refreshes it. Screen actions are not retried.
  if (res.status === 403 && retry) {
    await currentSession();
    return postSession(transid, false);
  }
  if (!res.ok) throw new Error(await readProblem(res));
  return parseReply(await readJson(res));
}

function post(path: string, body: unknown): Promise<Response> {
  return fetch(path, {
    method: "POST",
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": csrfToken(),
    },
    body: JSON.stringify(body),
  });
}

function csrfToken(): string {
  if (typeof document === "undefined") return "";
  return readCookie(document.cookie, "XSRF-TOKEN");
}

async function readJson(res: Response): Promise<unknown> {
  const value: unknown = await res.json();
  return value;
}

async function readProblem(res: Response): Promise<string> {
  const text = await res.text();
  if (!text) return `HTTP ${res.status}`;
  try {
    const json: unknown = JSON.parse(text);
    if (isRecord(json)) {
      if (typeof json.detail === "string" && json.detail) return json.detail;
      if (typeof json.title === "string" && json.title) return json.title;
      if (typeof json.message === "string" && json.message) return json.message;
    }
  } catch {
    return text;
  }
  return text;
}

function parseScreen(obj: Record<string, unknown>): ScreenReply {
  if (!isRecord(obj.map)) throw new Error("map");
  return {
    kind: "screen",
    turn: parseTurn(obj.turn),
    screen: requiredName(obj, "screen"),
    map: { mapset: requiredName(obj.map, "mapset"), name: requiredName(obj.map, "name") },
    fields: parseStringRecord(obj.fields, "fields"),
    colors: parseColorRecord(obj.colors),
    protectedFields: parseNames(obj.protectedFields, "protectedFields"),
    hiddenFields: parseNames(obj.hiddenFields, "hiddenFields"),
    cursor: parseCursor(obj.cursor),
    actions: parseActions(obj.actions),
  };
}

function parseText(obj: Record<string, unknown>): TextReply {
  return {
    kind: "text",
    turn: parseTurn(obj.turn),
    screen: requiredName(obj, "screen"),
    text: requiredText(obj, "text"),
    actions: parseActions(obj.actions),
  };
}

function parseEnded(obj: Record<string, unknown>): EndedReply {
  return { kind: "ended", turn: parseTurn(obj.turn), text: requiredText(obj, "text") };
}

function parseField(value: unknown): MapField {
  if (!isRecord(value)) throw new Error("field");
  return {
    name: requiredName(value, "name"),
    row: parseCell(value.row, "row"),
    col: parseCell(value.col, "col"),
    length: parseCell(value.length, "length"),
    initial: optionalString(value, "initial"),
    color: optionalColor(value, "color"),
  };
}

function parseLiteral(value: unknown): MapLiteral {
  if (!isRecord(value)) throw new Error("literal");
  return {
    row: parseCell(value.row, "row"),
    col: parseCell(value.col, "col"),
    text: requiredText(value, "text"),
    color: optionalColor(value, "color"),
  };
}

function parseActions(value: unknown): ActionRef[] {
  if (!Array.isArray(value)) throw new Error("actions");
  return value.map(parseAction);
}

function parseAction(value: unknown): ActionRef {
  if (!isRecord(value)) throw new Error("action");
  const name = requiredName(value, "name");
  if (!isKey(value.key)) throw new Error("action.key");
  return { name, key: value.key };
}

function parseNames(value: unknown, label: string): string[] {
  if (!Array.isArray(value)) throw new Error(label);
  return value.map((item) => {
    if (typeof item !== "string") throw new Error(label);
    return item;
  });
}

function parseStringRecord(value: unknown, label: string): Record<string, string> {
  if (!isRecord(value)) throw new Error(label);
  const out: Record<string, string> = {};
  for (const [name, field] of Object.entries(value)) {
    if (typeof field !== "string") throw new Error(`${label}.${name}`);
    out[name] = field;
  }
  return out;
}

function parseColorRecord(value: unknown): Record<string, BmsColor> {
  if (!isRecord(value)) throw new Error("colors");
  const out: Record<string, BmsColor> = {};
  for (const [name, color] of Object.entries(value)) {
    out[name] = parseColorValue(color);
  }
  return out;
}

function parseCursor(value: unknown): string | null {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value === "string") return value;
  throw new Error("cursor");
}

function parseTurn(value: unknown): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0) throw new Error("turn");
  return value;
}

function parseCell(value: unknown, label: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 1) throw new Error(label);
  return value;
}

function optionalString(obj: Record<string, unknown>, key: string): string {
  const value = obj[key];
  if (value === undefined || value === null) return "";
  if (typeof value !== "string") throw new Error(key);
  return value;
}

function optionalColor(obj: Record<string, unknown>, key: string): BmsColor {
  const value = obj[key];
  if (value === undefined || value === null) return "DEFAULT";
  return parseColorValue(value);
}

function parseColorValue(value: unknown): BmsColor {
  if (typeof value !== "string") throw new Error("color");
  const color = normalizeColor(value);
  if (!color) throw new Error(`color ${value}`);
  return color;
}

function normalizeColor(value: string): BmsColor | null {
  switch (value.trim().toUpperCase()) {
    case "BLUE":
      return "BLUE";
    case "RED":
      return "RED";
    case "GREEN":
      return "GREEN";
    case "YELLOW":
    case "YELLO":
      return "YELLOW";
    case "TURQUOISE":
    case "TURQ":
      return "TURQUOISE";
    case "NEUTRAL":
    case "NEUTR":
      return "NEUTRAL";
    case "PINK":
      return "PINK";
    case "DEFAULT":
    case "DFCOL":
      return "DEFAULT";
    default:
      return null;
  }
}

function requiredName(obj: Record<string, unknown>, key: string): string {
  const value = obj[key];
  if (typeof value !== "string" || value === "") throw new Error(key);
  return value;
}

function requiredText(obj: Record<string, unknown>, key: string): string {
  const value = obj[key];
  if (typeof value !== "string") throw new Error(key);
  return value;
}

function isRecord(input: unknown): input is Record<string, unknown> {
  return typeof input === "object" && input !== null && !Array.isArray(input);
}
