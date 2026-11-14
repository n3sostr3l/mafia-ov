import assert from "node:assert/strict";
const base = new URL(process.env.APP_BASE_URL || "http://127.0.0.1:4200");
assert(["127.0.0.1", "localhost"].includes(base.hostname));
let key = "";
async function request(path, method = "GET", body, expected = 200) {
  const r = await fetch(new URL("/api/v1" + path, base), {
    method,
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(key ? { "X-Tournament-Key": key } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await r.json();
  assert.equal(r.status, expected, JSON.stringify(data));
  return data;
}
async function wait(check, message) {
  const until = Date.now() + 10000;
  while (!check() && Date.now() < until)
    await new Promise((r) => setTimeout(r, 30));
  assert(check(), message);
}
const t = await request("/tournaments", "POST", {
  name: "Проверка ключей WebSocket",
  password: "test-secret",
});
await request("/tournaments/" + t.id, "GET", undefined, 401);
key = (
  await request("/tournaments/" + t.id + "/unlock", "POST", {
    password: "test-secret",
  })
).key;
const g = await request("/tournaments/" + t.id + "/games", "POST", {
  tableNumber: 1,
  gameNumber: 1,
  demo: true,
});
const { token } = await request("/games/" + g.id + "/overlay-link");
const ws = (suffix = "") =>
  new WebSocket(
    new URL("/ws/games/" + g.id + suffix, base).href.replace(/^http/, "ws"),
  );
let denied = false,
  closed = false;
const management = [],
  publicFrames = [];
const bad = ws();
bad.addEventListener("error", () => {
  denied = true;
});
const editor = ws("?editKey=" + key);
editor.addEventListener("message", (e) => management.push(JSON.parse(e.data)));
editor.addEventListener("close", () => {
  closed = true;
});
const obs = ws("?token=" + token);
obs.addEventListener("message", (e) => publicFrames.push(JSON.parse(e.data)));
try {
  await wait(
    () => denied && management.length && publicFrames.length,
    "Protected editor requires its key and OBS remains open.",
  );
  assert(management[0].state.players.some((p) => p.role === "SHERIFF"));
  assert(publicFrames[0].state.players.every((p) => p.role === "UNKNOWN"));
  const old = key;
  key = (
    await request("/tournaments/" + t.id + "/password", "PUT", {
      password: "changed-secret",
    })
  ).key;
  await wait(
    () => closed,
    "Changing password must close the old editor socket.",
  );
  key = old;
  await request("/games/" + g.id, "GET", undefined, 401);
  key = (
    await request("/tournaments/" + t.id + "/unlock", "POST", {
      password: "changed-secret",
    })
  ).key;
  assert.equal(obs.readyState, WebSocket.OPEN);
  obs.send("ping");
  await wait(
    () => publicFrames.length >= 2,
    "Public OBS socket survives a password change.",
  );
  await request("/tournaments/" + t.id, "DELETE");
  await wait(
    () => obs.readyState === WebSocket.CLOSED,
    "Deleting a tournament closes its OBS socket.",
  );
  console.log(
    "PASS: protected HTTP/editor WebSocket, key scope and revocation, public OBS without password, deletion closes live sockets.",
  );
} finally {
  bad.close();
  editor.close();
  obs.close();
}
