import { Game, State, Check, RoundData, normalizeGame } from "./model";

export const roundData = (s: State): RoundData =>
  (s.rounds[s.round] ??= {
    nominees: [],
    votes: {},
    eligibleVoters: [],
    spokenSeats: [],
    eliminated: null,
  });
const mafia = (role: string) => role === "MAFIA" || role === "DON";
export function checkResult(s: State, by: string, seat: number): string {
  const role = s.players.find((p) => p.seat === seat)?.role;
  return by === "DON"
    ? role === "SHERIFF"
      ? "SHERIFF"
      : "NOT_SHERIFF"
    : mafia(role || "")
      ? "BLACK"
      : "RED";
}
export function refreshChecks(s: State) {
  s.checks.forEach((c) => {
    c.checker ??= s.players.find((p) => p.role === c.by)?.seat;
    const checker = s.players.find((p) => p.seat === c.checker);
    if (!checker || !["DON", "SHERIFF"].includes(checker.role))
      throw new Error("Удалите проверки игрока перед сменой его роли.");
    c.by = checker.role as Check["by"];
    c.result = checkResult(s, c.by, c.seat);
    c.published = true;
  });
}
export function finishGame(s: State) {
  s.players.forEach((p) => (p.pu = s.firstDeparted === p.seat));
  const count = (role: string) =>
    s.players.filter((p) => p.role === role).length;
  const ready =
    count("DON") === 1 &&
    count("SHERIFF") === 1 &&
    count("MAFIA") === 2 &&
    count("CIVILIAN") === 6;
  const alive = s.players.filter((p) => p.status === "ALIVE");
  const black = alive.filter((p) => mafia(p.role)).length;
  const winner =
    s.phase !== "PREPARATION" && ready
      ? black === 0
        ? "CITY"
        : black >= alive.length - black
          ? "MAFIA"
          : null
      : null;
  if (winner) {
    if (s.winner !== winner) {
      s.phaseBeforeFinish = s.phase;
      s.phase = "FINISHED";
    }
    s.speaker = null;
    s.speechEndsAt = null;
    s.speechRemainingMs = 60000;
  } else if (s.phase === "FINISHED") {
    s.phase = s.phaseBeforeFinish ?? "DAY";
    s.phaseBeforeFinish = null;
  }
  s.winner = winner;
}
export function startSpeech(s: State, seat: number, now: number) {
  if (s.phase !== "DAY" || s.winner || s.players[seat - 1]?.status !== "ALIVE")
    throw new Error("Выберите живого выступающего во время дня.");
  s.speaker = seat;
  s.speechRemainingMs = 60000;
  s.speechEndsAt = now + 60000;
}
export function stepPhase(s: State, direction: number, now: number) {
  roundData(s).nominees = [...s.nominees];
  s.speaker = null;
  s.speechEndsAt = null;
  s.speechRemainingMs = 60000;
  switch (s.phase) {
    case "PREPARATION":
      if (direction > 0) s.phase = "DAY";
      break;
    case "DAY":
      if (direction > 0) s.phase = "VOTING";
      else if (s.round > 1) {
        s.round--;
        s.phase = "NIGHT";
      } else s.phase = "PREPARATION";
      break;
    case "VOTING":
      s.phase = direction > 0 ? "NIGHT" : "DAY";
      break;
    case "NIGHT":
      if (direction > 0) {
        if (s.round >= 100) throw new Error("Достигнут последний раунд.");
        s.round++;
        s.phase = "DAY";
      } else s.phase = "VOTING";
      break;
    case "FINISHED":
      if (direction < 0) s.phase = s.phaseBeforeFinish ?? "NIGHT";
      break;
  }
  const r = roundData(s);
  s.nominees = [...r.nominees];
  if (s.phase === "VOTING" && !r.eligibleVoters.length)
    r.eligibleVoters = s.players
      .filter((p) => p.status === "ALIVE")
      .map((p) => p.seat);
  if (direction > 0 && s.phase === "DAY" && !s.winner) {
    const first = (s.round - 1) % 10;
    for (let i = 0; i < 10; i++) {
      const seat = ((first + i) % 10) + 1;
      if (
        s.players[seat - 1].status === "ALIVE" &&
        !r.spokenSeats.includes(seat)
      ) {
        startSpeech(s, seat, now);
        break;
      }
    }
  }
}
export function nextSpeech(s: State, now: number) {
  const r = roundData(s),
    previous = s.speaker ?? ((s.round - 2 + 10) % 10) + 1;
  if (s.speaker && !r.spokenSeats.includes(s.speaker))
    r.spokenSeats.push(s.speaker);
  for (let i = 1; i <= 10; i++) {
    const seat = ((previous - 1 + i) % 10) + 1;
    if (
      s.players[seat - 1].status === "ALIVE" &&
      !r.spokenSeats.includes(seat)
    ) {
      startSpeech(s, seat, now);
      return;
    }
  }
  stepPhase(s, 1, now);
}
export function expireSpeech(s: State, now: number) {
  while (s.speechEndsAt !== null && s.speechEndsAt <= now)
    nextSpeech(s, s.speechEndsAt);
}
export function demoAction(
  game: Game,
  type: string,
  seat?: number | null,
  candidate?: number | null,
  now = Date.now(),
) {
  normalizeGame(game);
  const s = game.state;
  switch (type) {
    case "PHASE_NEXT":
      stepPhase(s, 1, now);
      break;
    case "PHASE_PREVIOUS":
      stepPhase(s, -1, now);
      break;
    case "SPEECH_START":
      startSpeech(s, seat!, now);
      break;
    case "SPEECH_NEXT":
      nextSpeech(s, now);
      break;
    case "SPEECH_PAUSE":
      s.speechRemainingMs = Math.max(
        0,
        (s.speechEndsAt ?? now + s.speechRemainingMs) - now,
      );
      s.speechEndsAt = null;
      break;
    case "SPEECH_RESUME":
      if (s.speaker && s.speechEndsAt === null)
        s.speechEndsAt = now + s.speechRemainingMs;
      break;
    case "VOTE": {
      const r = roundData(s);
      if (s.phase !== "VOTING" || !r.eligibleVoters.includes(seat!))
        throw new Error("Этот игрок не участвовал в голосовании раунда.");
      if (candidate == null) delete r.votes[seat!];
      else {
        if (
          !s.nominees.includes(candidate) ||
          !r.eligibleVoters.includes(candidate)
        )
          throw new Error("Выберите кандидатуру этого раунда.");
        r.votes[seat!] = candidate;
      }
      break;
    }
    case "VOTE_ELIMINATE":
      if (
        s.phase !== "VOTING" ||
        !s.nominees.includes(seat!) ||
        s.players[seat! - 1].status !== "ALIVE"
      )
        throw new Error("Выберите живого кандидата.");
      s.players[seat! - 1].status = "VOTED";
      roundData(s).eliminated = seat!;
      s.firstDeparted ??= seat!;
      break;
  }
  finishGame(s);
}
