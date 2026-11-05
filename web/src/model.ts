import { INVALID_KEY } from "./keys";
import type { Key } from "./keys";

export type BmsColor =
  | "BLUE"
  | "RED"
  | "GREEN"
  | "YELLOW"
  | "TURQUOISE"
  | "NEUTRAL"
  | "PINK"
  | "DEFAULT";

/** Named BMS field position. Protection and darkness come with each reply. FSET and IC stay on the server. */
export type MapField = {
  name: string;
  /** 1-based. Row is the BMS line. Column is the first data cell, one past the attribute byte. */
  row: number;
  col: number;
  length: number;
  initial: string;
  color: BmsColor;
};

export type MapLiteral = {
  row: number;
  col: number;
  text: string;
  color: BmsColor;
};

export type MapLayout = {
  mapset: string;
  name: string;
  fields: readonly MapField[];
  literals: readonly MapLiteral[];
};

export type ActBody = {
  turn: number;
  fields: Record<string, string>;
  key?: Key;
};

const NO_DATA: ReadonlySet<Key> = new Set<Key>(["CLEAR", "PA1", "PA2", "PA3"]);

export function colorClass(color: BmsColor): string {
  return color === "DEFAULT" ? "default" : color;
}

export function gridPlacement(row: number, col: number, length: number): {
  gridRow: number;
  gridColumn: string;
} {
  return { gridRow: row, gridColumn: `${col} / span ${length}` };
}

export function displayValue(
  shown: Readonly<Record<string, string>>,
  typed: Readonly<Record<string, string>>,
  name: string,
): string {
  const edited = typed[name];
  if (edited !== undefined) return edited;
  return shown[name] ?? "";
}

export function rememberTyped(
  typed: Readonly<Record<string, string>>,
  field: Pick<MapField, "name" | "length">,
  value: string,
): Readonly<Record<string, string>> {
  const next = value.replace(/\r?\n/g, "").slice(0, field.length);
  if (typed[field.name] === next) return typed;
  return { ...typed, [field.name]: next };
}

export function fieldsForKey(
  key: Key,
  typed: Readonly<Record<string, string>>,
): Record<string, string> {
  if (NO_DATA.has(key)) return {};
  return { ...typed };
}

/**
 * CLEAR and PA send no typed fields so the server can treat them as MAPFAIL.
 * `key` is included only for invalid-key, where CLEAR and an unbound PF key would otherwise look the same.
 */
export function buildActBody(input: {
  turn: number;
  action: string;
  key: Key;
  fields: Readonly<Record<string, string>>;
}): ActBody {
  const sent = fieldsForKey(input.key, input.fields);
  if (input.action === INVALID_KEY) return { turn: input.turn, fields: sent, key: input.key };
  return { turn: input.turn, fields: sent };
}
