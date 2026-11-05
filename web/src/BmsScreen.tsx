import { useEffect, useRef, useState } from "react";
import type { KeyboardEvent as ReactKeyboardEvent, MouseEvent as ReactMouseEvent } from "react";
import type { ScreenReply, TextReply } from "./api";
import { label, takeAidKey } from "./keys";
import type { ActionRef, Key } from "./keys";
import { colorClass, displayValue, gridPlacement, rememberTyped } from "./model";
import type { MapLayout } from "./model";

export function BmsScreen(props: {
  layout: MapLayout;
  reply: ScreenReply;
  busy: boolean;
  onAction: (key: Key, fields: Readonly<Record<string, string>>) => void;
}) {
  const { layout, reply, busy, onAction } = props;
  const [typed, setTyped] = useState<Readonly<Record<string, string>>>({});
  const gridRef = useRef<HTMLDivElement>(null);
  const locked = new Set(reply.protectedFields);
  const hidden = new Set(reply.hiddenFields);

  useEffect(() => {
    const grid = gridRef.current;
    if (!grid) return;
    focusCursor(grid, reply.cursor);
  }, [reply.cursor, reply.turn]);

  function onKeyDown(event: ReactKeyboardEvent) {
    takeAidKey(event, busy, (key) => onAction(key, typed));
  }

  return (
    <div className="bms" onKeyDownCapture={onKeyDown}>
      <div
        className="bms-grid"
        ref={gridRef}
        tabIndex={-1}
        aria-busy={busy}
        aria-label="Screen"
        onMouseDown={onGridMouseDown}
      >
        {layout.literals.map((lit, index) =>
          lit.text === "" ? null : (
            <span
              key={`l${index}`}
              className={colorClass(lit.color)}
              style={gridPlacement(lit.row, lit.col, lit.text.length)}
            >
              {lit.text}
            </span>
          ),
        )}
        {layout.fields.map((field) => {
          const value = displayValue(reply.fields, typed, field.name);
          const color = colorClass(reply.colors[field.name] ?? field.color);
          const style = gridPlacement(field.row, field.col, field.length);
          if (locked.has(field.name)) {
            return (
              <span key={field.name} data-field={field.name} className={color} style={style}>
                {hidden.has(field.name) ? "" : value}
              </span>
            );
          }
          return (
            <input
              key={field.name}
              data-field={field.name}
              aria-label={field.name}
              className={color}
              style={style}
              value={value}
              maxLength={field.length}
              type={hidden.has(field.name) ? "password" : "text"}
              autoComplete="off"
              autoCapitalize="off"
              spellCheck={false}
              readOnly={busy}
              onChange={(event) => {
                if (busy) return;
                const next = event.target.value;
                setTyped((current) => rememberTyped(current, field, next));
              }}
            />
          );
        })}
      </div>
      <ActionBar actions={reply.actions} busy={busy} onAction={(key) => onAction(key, typed)} />
    </div>
  );
}

/** SEND TEXT: no fields, but the conversation goes on, so the keys still act. */
export function TextScreen(props: {
  reply: TextReply;
  busy: boolean;
  onAction: (key: Key, fields: Readonly<Record<string, string>>) => void;
}) {
  const { reply, busy, onAction } = props;
  const textRef = useRef<HTMLPreElement>(null);

  useEffect(() => {
    textRef.current?.focus();
  }, [reply.turn]);

  function onKeyDown(event: ReactKeyboardEvent) {
    takeAidKey(event, busy, (key) => onAction(key, {}));
  }

  return (
    <div className="bms" onKeyDownCapture={onKeyDown}>
      <pre className="bms-text" ref={textRef} tabIndex={-1} aria-label="Screen" aria-busy={busy}>
        {reply.text}
      </pre>
      <ActionBar actions={reply.actions} busy={busy} onAction={(key) => onAction(key, {})} />
    </div>
  );
}

function ActionBar(props: { actions: readonly ActionRef[]; busy: boolean; onAction: (key: Key) => void }) {
  return (
    <>
      <div className="bms-actions">
        {props.actions.map((action) => (
          <button
            key={`${action.key}:${action.name}`}
            type="button"
            disabled={props.busy}
            onClick={() => props.onAction(action.key)}
          >
            {label(action)}
          </button>
        ))}
      </div>
      {props.busy ? <p className="lock">Keyboard locked</p> : null}
    </>
  );
}

function onGridMouseDown(event: ReactMouseEvent<HTMLDivElement>) {
  if (event.target instanceof HTMLInputElement) return;
  // A click on a literal would drop focus to the document, and PF keys would stop arriving.
  event.preventDefault();
  event.currentTarget.focus();
}

function focusCursor(grid: HTMLElement, cursor: string | null) {
  if (cursor) {
    const input = grid.querySelector<HTMLInputElement>(`input[data-field="${CSS.escape(cursor)}"]`);
    if (input) {
      input.focus();
      return;
    }
  }
  grid.focus();
}
