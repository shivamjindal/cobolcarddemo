import { Route, Routes } from "react-router-dom";
import { BmsScreen, TextScreen } from "./BmsScreen";
import { QueuePage } from "./QueuePage";
import { useConversation } from "./useConversation";

export function App() {
  return (
    <Routes>
      <Route path="/mq" element={<QueuePage />} />
      <Route path="/:screen?" element={<ConversationScreen />} />
    </Routes>
  );
}

function ConversationScreen() {
  const conversation = useConversation();
  const { view, banner, busy } = conversation;
  return (
    <main className="app">
      {banner ? (
        <p role="status" className="banner">
          {banner}
        </p>
      ) : null}
      {view.kind === "loading" ? <p className="status">Connecting...</p> : null}
      {view.kind === "broken" ? (
        <section className="broken">
          <p>{view.message}</p>
          <StartButton busy={busy} onRestart={conversation.restart} />
        </section>
      ) : null}
      {view.kind === "ended" ? (
        <section className="ended">
          <pre>{view.text}</pre>
          <StartButton busy={busy} onRestart={conversation.restart} />
        </section>
      ) : null}
      {view.kind === "screen" ? (
        <BmsScreen
          key={`${view.reply.screen}:${view.reply.turn}`}
          layout={view.layout}
          reply={view.reply}
          busy={busy}
          onAction={conversation.press}
        />
      ) : null}
      {view.kind === "text" ? (
        <TextScreen
          key={`${view.reply.screen}:${view.reply.turn}`}
          reply={view.reply}
          busy={busy}
          onAction={conversation.press}
        />
      ) : null}
    </main>
  );
}

function StartButton(props: { busy: boolean; onRestart: () => void }) {
  return (
    <button type="button" onClick={props.onRestart} disabled={props.busy} autoFocus>
      Start CC00
    </button>
  );
}
