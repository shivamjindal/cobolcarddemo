import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, expect, test, vi } from "vitest";
import { QueueView } from "./QueuePage";
import type { QueueViewProps } from "./QueuePage";
import {
  REPLY_QUEUE,
  REQUEST_QUEUE,
  SAMPLE_REQUEST,
  browse,
  parseMessages,
  putMessage,
  putProblem,
  showControls,
  takeNext,
} from "./queues";

const draft = { queue: REQUEST_QUEUE, data: SAMPLE_REQUEST, correlId: "WEB0001", replyTo: REPLY_QUEUE };

const reply = {
  msgId: "CDMQ00000000000000000042",
  correlId: "WEB0001",
  replyTo: "",
  data: "9680294154603697,TXN000000000001,093000,00,0000,         25.00,\u0000",
};

function view(overrides: Partial<QueueViewProps>): string {
  const props: QueueViewProps = {
    draft,
    replyQueue: REPLY_QUEUE,
    replies: null,
    status: "",
    signedOff: false,
    busy: false,
    onDraft: () => undefined,
    onReplyQueue: () => undefined,
    onSend: () => undefined,
    onRefresh: () => undefined,
    onTake: () => undefined,
    ...overrides,
  };
  return renderToStaticMarkup(<QueueView {...props} />);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

test("checks a put before it goes out", () => {
  expect(putProblem(draft)).toBeNull();
  expect(putProblem({ ...draft, queue: " " })).toContain("request queue");
  expect(putProblem({ ...draft, correlId: "C".repeat(25) })).toContain("correlation id");
  expect(putProblem({ ...draft, data: "\u20ac" })).toContain("latin-1");
  expect(putProblem({ ...draft, replyTo: "R".repeat(49) })).toContain("reply queue");
});

test("shows low-values and control bytes", () => {
  expect(showControls("A\u0000B\u0009")).toBe("A\u2400B\u2409");
  expect(showControls("plain")).toBe("plain");
});

test("parses browsed messages and refuses other shapes", () => {
  expect(parseMessages([reply])).toEqual([reply]);
  expect(() => parseMessages({})).toThrow("messages");
  expect(() => parseMessages([{ ...reply, data: 1 }])).toThrow("message.data");
});

test("puts, browses and takes through the queue API", async () => {
  const calls: { url: string; init: RequestInit }[] = [];
  const replies = [
    new Response(null, { status: 201 }),
    new Response(JSON.stringify([reply]), { status: 200 }),
    new Response(JSON.stringify(reply), { status: 200 }),
    new Response(null, { status: 204 }),
    new Response("", { status: 403 }),
    new Response(JSON.stringify({ detail: "MQPUT1 failed" }), { status: 422 }),
  ];
  vi.stubGlobal("fetch", (url: string, init: RequestInit) => {
    calls.push({ url, init });
    const next = replies.shift();
    if (!next) throw new Error("unexpected fetch");
    return Promise.resolve(next);
  });

  expect(await putMessage({ ...draft, replyTo: ` ${REPLY_QUEUE} ` })).toEqual({ kind: "ok", value: null });
  expect(calls[0]?.url).toBe(`/api/mq/queues/${REQUEST_QUEUE}/messages`);
  expect(calls[0]?.init.method).toBe("POST");
  expect(JSON.parse(String(calls[0]?.init.body))).toEqual({
    data: SAMPLE_REQUEST,
    correlId: "WEB0001",
    replyTo: REPLY_QUEUE,
  });

  expect(await browse(REPLY_QUEUE)).toEqual({ kind: "ok", value: [reply] });
  expect(calls[1]?.init.method).toBe("GET");
  expect(await takeNext(REPLY_QUEUE)).toEqual({ kind: "ok", value: reply });
  expect(calls[2]?.url).toBe(`/api/mq/queues/${REPLY_QUEUE}/get`);
  expect(await takeNext(REPLY_QUEUE)).toEqual({ kind: "ok", value: null });
  expect(await browse(REPLY_QUEUE)).toEqual({ kind: "signed-off" });
  expect(await putMessage(draft)).toEqual({ kind: "problem", message: "MQPUT1 failed" });
});

test("renders the request form and the reply queue", () => {
  const html = view({ replies: [reply], status: "Reply received on AWS.M2.CARDDEMO.PAUTH.REPLY." });
  expect(html).toContain("MQ authorization requests");
  expect(html).toContain(`value="${REQUEST_QUEUE}"`);
  expect(html).toContain(SAMPLE_REQUEST);
  expect(html).toContain("Send request");
  expect(html).toContain("Take next");
  expect(html).toContain("WEB0001");
  expect(html).toContain("         25.00,\u2400");
  expect(html).toContain('role="status"');
  expect(html).not.toContain('role="alert"');
});

test("asks for an admin sign-on and says when the reply queue is empty", () => {
  const html = view({ replies: [], signedOff: true, busy: true });
  expect(html).toContain('role="alert"');
  expect(html).toContain('href="/"');
  expect(html).toContain(`No messages on ${REPLY_QUEUE}.`);
  expect(html).toContain('aria-busy="true"');
  expect(html).toContain('disabled=""');
});
