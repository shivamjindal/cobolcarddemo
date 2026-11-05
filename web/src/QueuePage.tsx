import { useCallback, useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import {
  REPLY_QUEUE,
  REQUEST_QUEUE,
  SAMPLE_REQUEST,
  browse,
  putMessage,
  putProblem,
  showControls,
  takeNext,
} from "./queues";
import type { PutInput, QueueMessage, QueueResult } from "./queues";

const POLL_MS = 300;
const POLL_TRIES = 15;

export type QueueViewProps = {
  draft: PutInput;
  replyQueue: string;
  replies: readonly QueueMessage[] | null;
  status: string;
  signedOff: boolean;
  busy: boolean;
  onDraft: (draft: PutInput) => void;
  onReplyQueue: (queue: string) => void;
  onSend: () => void;
  onRefresh: () => void;
  onTake: () => void;
};

/** Sends an authorization request to the request queue and shows what is waiting on the reply queue. */
export function QueuePage() {
  const [draft, setDraft] = useState<PutInput>({
    queue: REQUEST_QUEUE,
    data: SAMPLE_REQUEST,
    correlId: "WEB0001",
    replyTo: REPLY_QUEUE,
  });
  const [replyQueue, setReplyQueue] = useState(REPLY_QUEUE);
  const [replies, setReplies] = useState<readonly QueueMessage[] | null>(null);
  const [status, setStatus] = useState("");
  const [signedOff, setSignedOff] = useState(false);
  const [busy, setBusy] = useState(false);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  const settle = useCallback(<T,>(result: QueueResult<T>): T | undefined => {
    if (result.kind === "ok") {
      setSignedOff(false);
      return result.value;
    }
    if (result.kind === "signed-off") setSignedOff(true);
    else setStatus(result.message);
    return undefined;
  }, []);

  const refresh = useCallback(async (): Promise<readonly QueueMessage[] | undefined> => {
    const messages = settle(await browse(replyQueue));
    if (messages && alive.current) setReplies(messages);
    return messages;
  }, [replyQueue, settle]);

  useEffect(() => {
    void refresh().catch((err: unknown) => setStatus(String(err)));
  }, [refresh]);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    try {
      await work();
    } catch (err: unknown) {
      setStatus(err instanceof Error ? err.message : String(err));
    } finally {
      if (alive.current) setBusy(false);
    }
  }, []);

  const send = () =>
    run(async () => {
      const problem = putProblem(draft);
      if (problem) {
        setStatus(problem);
        return;
      }
      const before = replies?.length ?? 0;
      if (settle(await putMessage(draft)) === undefined) return;
      setStatus(`Sent to ${draft.queue.trim()}. Waiting for the reply...`);
      // The trigger starts CP00 after the put commits, so the reply arrives a moment later.
      for (let i = 0; i < POLL_TRIES && alive.current; i++) {
        await new Promise((resolve) => setTimeout(resolve, POLL_MS));
        const messages = await refresh();
        if (messages && messages.length > before) {
          setStatus(`Reply received on ${replyQueue.trim()}.`);
          return;
        }
      }
      setStatus(`No new reply on ${replyQueue.trim()} yet. Refresh to look again.`);
    });

  const take = () =>
    run(async () => {
      const taken = settle(await takeNext(replyQueue));
      if (taken === undefined) return;
      setStatus(taken ? `Took the message ${taken.correlId || taken.msgId} off ${replyQueue.trim()}.` : "The reply queue is empty.");
      await refresh();
    });

  return (
    <QueueView
      draft={draft}
      replyQueue={replyQueue}
      replies={replies}
      status={status}
      signedOff={signedOff}
      busy={busy}
      onDraft={setDraft}
      onReplyQueue={setReplyQueue}
      onSend={() => void send()}
      onRefresh={() => void run(async () => void (await refresh()))}
      onTake={() => void take()}
    />
  );
}

export function QueueView(props: QueueViewProps) {
  const { draft, onDraft } = props;
  const submit = (event: FormEvent) => {
    event.preventDefault();
    props.onSend();
  };
  return (
    <main className="app mq">
      <h1>MQ authorization requests</h1>
      {props.signedOff ? (
        <p role="alert" className="banner">
          The queues are for administrators. <a href="/">Sign on</a> as an admin user, then come back to /mq.
        </p>
      ) : null}
      <form className="mq-form" onSubmit={submit} aria-busy={props.busy}>
        <label>
          Request queue
          <input
            value={draft.queue}
            maxLength={48}
            onChange={(e) => onDraft({ ...draft, queue: e.target.value })}
          />
        </label>
        <label>
          Reply to
          <input
            value={draft.replyTo}
            maxLength={48}
            onChange={(e) => onDraft({ ...draft, replyTo: e.target.value })}
          />
        </label>
        <label>
          Correlation id
          <input
            value={draft.correlId}
            maxLength={24}
            onChange={(e) => onDraft({ ...draft, correlId: e.target.value })}
          />
        </label>
        <label>
          Request (date,time,card,type,expiry,message type,source,processing code,amount,MCC,country,POS
          mode,merchant id,name,city,state,zip,transaction id)
          <textarea
            value={draft.data}
            rows={3}
            spellCheck={false}
            onChange={(e) => onDraft({ ...draft, data: e.target.value })}
          />
        </label>
        <div className="bms-actions">
          <button type="submit" disabled={props.busy}>
            Send request
          </button>
        </div>
      </form>
      <section className="mq-replies" aria-label="Reply queue">
        <label>
          Reply queue
          <input value={props.replyQueue} maxLength={48} onChange={(e) => props.onReplyQueue(e.target.value)} />
        </label>
        <div className="bms-actions">
          <button type="button" onClick={props.onRefresh} disabled={props.busy}>
            Refresh
          </button>
          <button type="button" onClick={props.onTake} disabled={props.busy}>
            Take next
          </button>
        </div>
        {props.status ? (
          <p role="status" className="status">
            {props.status}
          </p>
        ) : null}
        {props.replies === null ? null : props.replies.length === 0 ? (
          <p className="status">No messages on {props.replyQueue.trim()}.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th scope="col">Correlation id</th>
                <th scope="col">Message</th>
              </tr>
            </thead>
            <tbody>
              {props.replies.map((message, index) => (
                <tr key={`${message.msgId}:${index}`}>
                  <td>{message.correlId}</td>
                  <td>
                    <code>{showControls(message.data)}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}
