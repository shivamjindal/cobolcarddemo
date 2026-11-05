export const KEYS = [
  "ENTER",
  "CLEAR",
  "PA1",
  "PA2",
  "PA3",
  "PF1",
  "PF2",
  "PF3",
  "PF4",
  "PF5",
  "PF6",
  "PF7",
  "PF8",
  "PF9",
  "PF10",
  "PF11",
  "PF12",
  "PF13",
  "PF14",
  "PF15",
  "PF16",
  "PF17",
  "PF18",
  "PF19",
  "PF20",
  "PF21",
  "PF22",
  "PF23",
  "PF24",
] as const;

export type Key = (typeof KEYS)[number];

export type ActionRef = {
  name: string;
  key: Key;
};

export const INVALID_KEY = "invalid-key";

const KEY_SET: ReadonlySet<string> = new Set(KEYS);
const PF_KEY = /^F([1-9]|1[0-2])$/;

export function isKey(value: unknown): value is Key {
  return typeof value === "string" && KEY_SET.has(value);
}

/** Enter, F1-F12, Shift+F1-F12, and Escape. Anything else is typing. */
export function keyOf(event: { key: string; shiftKey: boolean }): Key | null {
  if (event.key === "Enter" || event.key === "NumpadEnter") return "ENTER";
  if (event.key === "Escape") return "CLEAR";
  const match = PF_KEY.exec(event.key);
  if (!match) return null;
  const n = Number(match[1]) + (event.shiftKey ? 12 : 0);
  const name = `PF${n}`;
  return isKey(name) ? name : null;
}

/** Capture runs before the browser handles F-keys and before a focused button activates. */
export function takeAidKey(
  event: { key: string; shiftKey: boolean; preventDefault(): void },
  busy: boolean,
  onAction: (key: Key) => void,
): void {
  const key = keyOf(event);
  if (!key) return;
  event.preventDefault();
  if (!busy) onAction(key);
}

export function actionFor(actions: readonly ActionRef[], key: Key): string {
  for (const action of actions) {
    if (action.key === key) return action.name;
  }
  return INVALID_KEY;
}

export function label(action: ActionRef): string {
  if (action.key === "ENTER") return `Enter ${action.name}`;
  if (action.key.startsWith("PF")) return `F${action.key.slice(2)} ${action.name}`;
  return `${action.key} ${action.name}`;
}
