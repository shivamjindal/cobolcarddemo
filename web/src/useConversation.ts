import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { act, fetchMap, loadOrStart, startSession } from "./api";
import type { Reply, ScreenReply, TextReply } from "./api";
import type { Key } from "./keys";
import type { MapLayout } from "./model";

export type ConversationView =
  | { kind: "loading" }
  | { kind: "screen"; reply: ScreenReply; layout: MapLayout }
  | { kind: "text"; reply: TextReply }
  | { kind: "ended"; text: string }
  | { kind: "broken"; message: string };

export type Conversation = {
  view: ConversationView;
  banner: string | null;
  busy: boolean;
  press: (key: Key, fields: Readonly<Record<string, string>>) => void;
  restart: () => void;
};

let initialLoad: Promise<Reply> | null = null;

export function useConversation(): Conversation {
  const [view, setView] = useState<ConversationView>({ kind: "loading" });
  const [banner, setBanner] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const alive = useRef(true);
  const navigate = useNavigate();

  const show = useCallback(async (reply: Reply) => {
    if (reply.kind === "ended") {
      setView({ kind: "ended", text: reply.text });
      return;
    }
    if (reply.kind === "text") {
      setView({ kind: "text", reply });
      return;
    }
    const layout = await fetchMap(reply.map.name);
    setView({ kind: "screen", reply, layout });
  }, []);

  useEffect(() => {
    alive.current = true;
    let cancelled = false;
    loadInitial()
      .then((reply) => {
        if (!cancelled) return show(reply);
        return undefined;
      })
      .catch((err: unknown) => {
        if (!cancelled) setView({ kind: "broken", message: messageOf(err) });
      });
    return () => {
      cancelled = true;
      alive.current = false;
    };
  }, [show]);

  const screenName =
    view.kind === "screen" || view.kind === "text" ? view.reply.screen : view.kind === "ended" ? "" : null;
  useEffect(() => {
    if (screenName === null) return;
    navigate(screenName === "" ? "/" : `/${encodeURIComponent(screenName)}`, { replace: true });
  }, [screenName, navigate]);

  const press = useCallback(
    (key: Key, fields: Readonly<Record<string, string>>) => {
      if ((view.kind !== "screen" && view.kind !== "text") || inFlight.current) return;
      const current = view.reply;
      inFlight.current = true;
      setBusy(true);
      setBanner(null);
      void act({
        screen: current.screen,
        actions: current.actions,
        turn: current.turn,
        key,
        fields,
      })
        .then(async (result) => {
          if (!alive.current) return;
          switch (result.kind) {
            case "ok":
              await show(result.reply);
              return;
            case "conflict":
              await show(result.reply);
              if (alive.current) setBanner("The screen changed. Showing the current one.");
              return;
            case "unauthorized":
              try {
                const fresh = await startSession();
                await show(fresh);
                if (alive.current) setBanner("Session ended. Starting CC00.");
              } catch (err: unknown) {
                if (alive.current) setView({ kind: "broken", message: messageOf(err) });
              }
              return;
            case "problem":
              setBanner(result.message);
              return;
            default: {
              const neverResult: never = result;
              return neverResult;
            }
          }
        })
        .catch((err: unknown) => {
          if (alive.current) setBanner(messageOf(err));
        })
        .finally(() => {
          inFlight.current = false;
          if (alive.current) setBusy(false);
        });
    },
    [view, show],
  );

  const restart = useCallback(() => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setBanner(null);
    void startSession()
      .then((reply) => show(reply))
      .catch((err: unknown) => {
        if (alive.current) setView({ kind: "broken", message: messageOf(err) });
      })
      .finally(() => {
        inFlight.current = false;
        if (alive.current) setBusy(false);
      });
  }, [show]);

  return { view, banner, busy, press, restart };
}

function loadInitial(): Promise<Reply> {
  if (initialLoad) return initialLoad;
  // StrictMode runs this effect twice. One shared promise keeps the second run from posting CC00 again.
  const pending = loadOrStart().finally(() => {
    setTimeout(() => {
      if (initialLoad === pending) initialLoad = null;
    }, 0);
  });
  initialLoad = pending;
  return pending;
}

function messageOf(err: unknown): string {
  if (err instanceof Error && err.message) return err.message;
  return "The request failed.";
}
