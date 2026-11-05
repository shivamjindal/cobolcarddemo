import { renderToStaticMarkup } from "react-dom/server";
import { expect, test } from "vitest";
import { parseMap, parseReply } from "./api";
import { BmsScreen, TextScreen } from "./BmsScreen";
import { cosgnMap, cosgnReply } from "./cosgn0a.fixture";

test("paints the reply on the map and does not paint BMS initial", () => {
  const layout = parseMap(cosgnMap);
  const reply = parseReply(cosgnReply);
  if (reply.kind !== "screen") throw new Error("screen");
  const shown = {
    ...reply,
    fields: { ...reply.fields, PASSWD: "secret" },
  };
  const html = renderToStaticMarkup(
    <BmsScreen layout={layout} reply={shown} busy={false} onAction={() => undefined} />,
  );
  expect(html).toContain('aria-label="USERID"');
  expect(html).toContain('maxLength="8"');
  expect(html).toContain('type="password"');
  expect(html).toContain('aria-label="PASSWD"');
  expect(html).toContain("secret");
  expect(html).not.toContain("________");
  expect(html).not.toContain('aria-label="ERRMSG"');
  expect(html).toContain("Please enter User ID");
  expect(html).toContain("CC00");
  expect(html).toContain("Tran :");
  expect(html).toContain("grid-row:19");
  expect(html).toContain("44 / span 8");
  expect(html).toContain("grid-row:23");
  expect(html).toContain("2 / span 78");
  expect(html).toContain("Enter sign-on");
  expect(html).toContain("F3 exit");
  expect(html).toContain('class="GREEN"');
  expect(html).toContain('class="RED"');

  const locked = renderToStaticMarkup(
    <BmsScreen layout={layout} reply={shown} busy onAction={() => undefined} />,
  );
  expect(locked).toContain('aria-busy="true"');
  expect(locked).toContain('readOnly=""');
  expect(locked).toContain("Keyboard locked");
});

test("takes protection from the reply, not from the map", () => {
  const layout = parseMap(cosgnMap);
  const reply = parseReply(cosgnReply);
  if (reply.kind !== "screen") throw new Error("screen");
  const html = renderToStaticMarkup(
    <BmsScreen
      layout={layout}
      reply={{
        ...reply,
        protectedFields: ["TRNNAME", "SYSID", "USERID"],
        hiddenFields: [],
      }}
      busy={false}
      onAction={() => undefined}
    />,
  );
  expect(html).not.toContain('aria-label="USERID"');
  expect(html).toContain('data-field="USERID"');
  expect(html).toContain('aria-label="ERRMSG"');
  expect(html).toContain('aria-label="PASSWD"');
  expect(html).not.toContain('type="password"');
});

test("shows a text reply with its keys", () => {
  const reply = parseReply({
    kind: "text",
    turn: 4,
    screen: "pending",
    text: "COCRDLIC is not migrated yet.",
    actions: [{ name: "back", key: "PF3" }],
  });
  if (reply.kind !== "text") throw new Error("text");
  const html = renderToStaticMarkup(<TextScreen reply={reply} busy={false} onAction={() => undefined} />);
  expect(html).toContain("COCRDLIC is not migrated yet.");
  expect(html).toContain("F3 back");
});
