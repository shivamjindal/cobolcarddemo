import { problemMessage, readCookie } from "./api";

export const REQUEST_QUEUE = "AWS.M2.CARDDEMO.PAUTH.REQUEST";
export const REPLY_QUEUE = "AWS.M2.CARDDEMO.PAUTH.REPLY";

/** Card 9680294154603697 is on account 1, which has room for 25.00 in the sample data. */
export const SAMPLE_REQUEST =
  "261105,093000,9680294154603697,0100,1128,0100,POS001,000000,25.00,5411,USA,01," +
  "MER000000000001,FRESH MART,SEATTLE,WA,98101,TXN000000000001";

export const MAX_DATA = 4096;
const ID_LENGTH = 24;
const QUEUE_NAME_LENGTH = 48;

export type QueueMessage = {
  msgId: string;
  correlId: string;
  replyTo: string;
  data: string;
};

export type PutInput = {
  queue: string;
  data: string;
  correlId: string;
  replyTo: string;
};

export type QueueResult<T> =
  | { kind: "ok"; value: T }
  | { kind: "signed-off" }
  | { kind: "problem"; message: string };

/** What the server would refuse, said before the request goes out. Null when the put is fine. */
export function putProblem(input: PutInput): string | null {
  if (!validQueue(input.queue)) return "The request queue name is 1 to 48 characters.";
  if (input.replyTo.trim() !== "" && input.replyTo.length > QUEUE_NAME_LENGTH)
    return "The reply queue name is up to 48 characters.";
  if (!isLatin1(input.data) || input.data.length > MAX_DATA)
    return `The message is up to ${MAX_DATA} latin-1 characters.`;
  if (!isLatin1(input.correlId) || input.correlId.length > ID_LENGTH)
    return "The correlation id is up to 24 latin-1 characters.";
  return null;
}

/** Low-values and other control bytes made visible, so a reply's trailing low-value shows. */
export function showControls(text: string): string {
  return text.replace(/[\u0000-\u001f]/g, (c) =>
    c === "\u0000" ? "\u2400" : String.fromCharCode(0x2400 + c.charCodeAt(0)),
  );
}

export function parseMessages(input: unknown): QueueMessage[] {
  if (!Array.isArray(input)) throw new Error("messages");
  return input.map(parseMessage);
}

export function parseMessage(input: unknown): QueueMessage {
  if (typeof input !== "object" || input === null || Array.isArray(input)) throw new Error("message");
  const obj = input as Record<string, unknown>;
  return {
    msgId: text(obj, "msgId"),
    correlId: text(obj, "correlId"),
    replyTo: text(obj, "replyTo"),
    data: text(obj, "data"),
  };
}

export async function putMessage(input: PutInput): Promise<QueueResult<null>> {
  const res = await send(`${queuePath(input.queue)}/messages`, "POST", {
    data: input.data,
    correlId: input.correlId,
    replyTo: input.replyTo.trim(),
  });
  if (res.status === 201) return { kind: "ok", value: null };
  return failure(res);
}

export async function browse(queue: string): Promise<QueueResult<QueueMessage[]>> {
  const res = await send(`${queuePath(queue)}/messages`, "GET");
  if (res.ok) return { kind: "ok", value: parseMessages(await res.json()) };
  return failure(res);
}

/** Takes the oldest message off the queue; null when it is empty. */
export async function takeNext(queue: string): Promise<QueueResult<QueueMessage | null>> {
  const res = await send(`${queuePath(queue)}/get`, "POST", {});
  if (res.status === 204) return { kind: "ok", value: null };
  if (res.ok) return { kind: "ok", value: parseMessage(await res.json()) };
  return failure(res);
}

function queuePath(queue: string): string {
  return `/api/mq/queues/${encodeURIComponent(queue.trim())}`;
}

function send(path: string, method: "GET" | "POST", body?: unknown): Promise<Response> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method === "POST") {
    headers["Content-Type"] = "application/json";
    headers["X-XSRF-TOKEN"] = typeof document === "undefined" ? "" : readCookie(document.cookie, "XSRF-TOKEN");
  }
  return fetch(path, {
    method,
    credentials: "same-origin",
    headers,
    body: body === undefined ? null : JSON.stringify(body),
  });
}

async function failure(res: Response): Promise<QueueResult<never>> {
  if (res.status === 401 || res.status === 403) return { kind: "signed-off" };
  return { kind: "problem", message: problemMessage(await res.text(), res.status) };
}

function validQueue(name: string): boolean {
  return name.trim() !== "" && name.length <= QUEUE_NAME_LENGTH;
}

function isLatin1(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    if (value.charCodeAt(i) > 0xff) return false;
  }
  return true;
}

function text(obj: Record<string, unknown>, key: string): string {
  const value = obj[key];
  if (typeof value !== "string") throw new Error(`message.${key}`);
  return value;
}
