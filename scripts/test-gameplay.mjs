import assert from "node:assert/strict";
import { test } from "node:test";
import { spawnSync } from "node:child_process";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const result = spawnSync(
  process.execPath,
  [
    path.join(root, "frontend/node_modules/typescript/bin/tsc"),
    path.join(root, "frontend/src/model.ts"),
    path.join(root, "frontend/src/gameplay.ts"),
    "--outDir",
    path.join(root, ".runtime/gameplay-js"),
    "--target",
    "ES2022",
    "--module",
    "commonjs",
    "--skipLibCheck",
  ],
  { encoding: "utf8" },
);
assert.equal(result.status, 0, result.stdout + result.stderr);
const require = createRequire(import.meta.url);
const { demoGame, normalizeGame, project, roleColor } = require(
  path.join(root, ".runtime/gameplay-js/model.js"),
);
const {
  demoAction,
  roundData,
  refreshChecks,
  finishGame,
  expireSpeech,
} = require(path.join(root, ".runtime/gameplay-js/gameplay.js"));
const game = () => normalizeGame(demoGame());
test("legacy PU and the single first-departed seat cannot diverge", () => {
  const g = demoGame();
  g.state.schemaVersion = 0;
  g.state.firstDeparted = null;
  g.state.players[2].pu = true;
  normalizeGame(g);
  assert.equal(g.state.firstDeparted, 3);
  g.state.players[4].status = "SHOT";
  g.state.firstDeparted = 5;
  finishGame(g.state);
  assert.deepEqual(
    g.state.players.filter((p) => p.pu).map((p) => p.seat),
    [5],
  );
});

test("migrates existing games without losing nominations or the custom sheriff color", () => {
  const g = demoGame();
  delete g.state.rounds;
  delete g.state.theme.sheriff;
  delete g.state.theme.don;
  g.state.theme.special = "#abc123";
  normalizeGame(g);
  assert.deepEqual(roundData(g.state).nominees, [2, 6, 9]);
  assert.equal(g.state.theme.sheriff, "#abc123");
  assert.notEqual(
    roleColor(g.state.theme, "SHERIFF"),
    roleColor(g.state.theme, "DON"),
  );
});
test("global switches show/hide all items and public checks never expose checker seats", () => {
  const g = game();
  g.state.players[3].revealRole = true;
  g.state.checks[0].checker = 4;
  g.state.checks[0].published = false;
  let pub = project(g);
  assert(pub.state.players.every((p) => p.role === "UNKNOWN"));
  assert.equal(pub.state.checks.length, 0);
  g.state.showRoles = true;
  g.state.showChecks = true;
  pub = project(g);
  assert(pub.state.players.every((p) => p.role !== "UNKNOWN"));
  assert.equal(pub.state.checks.length, 1);
  assert.equal(pub.state.checks[0].checker, undefined);
});
test("checks follow actual checker and target roles instead of a manually chosen result", () => {
  const g = game();
  g.state.checks.push({
    id: "don",
    by: "DON",
    seat: 4,
    result: "BLACK",
    round: 2,
    published: false,
  });
  refreshChecks(g.state);
  assert.equal(g.state.checks[0].result, "BLACK");
  assert.equal(g.state.checks[1].result, "SHERIFF");
  g.state.players[1].role = "CIVILIAN";
  refreshChecks(g.state);
  assert.equal(g.state.checks[0].result, "RED");
});
test("phase rewind preserves each round nominations and votes", () => {
  const g = game();
  demoAction(g, "PHASE_NEXT");
  demoAction(g, "VOTE", 1, 2);
  demoAction(g, "PHASE_NEXT");
  demoAction(g, "PHASE_NEXT");
  assert.equal(g.state.round, 3);
  assert.deepEqual(g.state.nominees, []);
  g.state.nominees = [7];
  demoAction(g, "PHASE_PREVIOUS");
  demoAction(g, "PHASE_PREVIOUS");
  assert.deepEqual(g.state.nominees, [2, 6, 9]);
  assert.equal(roundData(g.state).votes[1], 2);
  demoAction(g, "PHASE_NEXT");
  demoAction(g, "PHASE_NEXT");
  assert.deepEqual(g.state.nominees, [7]);
});
test("a departed voter is excluded and a new vote replaces the old vote", () => {
  const g = game();
  demoAction(g, "PHASE_NEXT");
  assert.throws(() => demoAction(g, "VOTE", 3, 2));
  demoAction(g, "VOTE", 1, 2);
  demoAction(g, "VOTE", 1, 6);
  assert.deepEqual(roundData(g.state).votes, { 1: 6 });
  demoAction(g, "VOTE_ELIMINATE", 6);
  assert.equal(g.state.players[5].status, "VOTED");
  assert.deepEqual(roundData(g.state).votes, { 1: 6 });
});
test("sixty-second speech skips departed players and the complete circle starts voting", () => {
  const g = game();
  demoAction(g, "SPEECH_START", 2, null, 1000);
  expireSpeech(g.state, 61000);
  assert.equal(g.state.speaker, 4);
  assert.equal(g.state.speechEndsAt, 121000);
  expireSpeech(g.state, 601000);
  assert.equal(g.state.phase, "VOTING");
  assert.equal(g.state.speaker, null);
  assert.equal(roundData(g.state).spokenSeats.length, 9);
});
test("pause and resume retain the remainder of the minute", () => {
  const g = game();
  demoAction(g, "SPEECH_START", 4, null, 1000);
  demoAction(g, "SPEECH_PAUSE", null, null, 18000);
  assert.equal(g.state.speechRemainingMs, 43000);
  expireSpeech(g.state, 101000);
  assert.equal(g.state.speaker, 4);
  demoAction(g, "SPEECH_RESUME", null, null, 101000);
  assert.equal(g.state.speechEndsAt, 144000);
});
test("city and mafia victory stop speech and display a finished game", () => {
  const g = game();
  g.state.players.forEach((p) => {
    if (["DON", "MAFIA"].includes(p.role)) p.status = "VOTED";
  });
  finishGame(g.state);
  assert.equal(g.state.winner, "CITY");
  assert.equal(g.state.phase, "FINISHED");
  assert.equal(g.state.speechEndsAt, null);
  const m = game();
  [1, 3, 5, 7].forEach((seat) => (m.state.players[seat - 1].status = "SHOT"));
  finishGame(m.state);
  assert.equal(m.state.winner, "MAFIA");
});
