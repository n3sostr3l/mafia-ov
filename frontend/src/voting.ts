import { State, RoundData } from "./model";
const round = (s: State) => s.rounds[s.round];
export const ballotCandidates = (s: State) =>
  (round(s).ballotStage ?? "INITIAL") === "INITIAL"
    ? s.nominees
    : (round(s).runoffCandidates ?? []);
const stop = (s: State) => {
  s.speaker = null;
  s.speechEndsAt = null;
  s.speechRemainingMs = 60000;
};
export function nextShootout(s: State, now: number) {
  const r = round(s);
  r.shootoutSpoken ??= [];
  if (s.speaker !== null && !r.shootoutSpoken.includes(s.speaker))
    r.shootoutSpoken.push(s.speaker);
  const next = r.runoffCandidates?.find(
    (c) =>
      !r.shootoutSpoken!.includes(c) && s.players[c - 1].status === "ALIVE",
  );
  if (next !== undefined) {
    s.speaker = next;
    s.speechRemainingMs = 30000;
    s.speechEndsAt = now + 30000;
  } else {
    stop(s);
    r.ballotStage = "REVOTE";
    r.votes = {};
    r.revoteVotes = {};
  }
}
function complete(s: State, removed: number[], split: boolean) {
  const r = round(s);
  r.eliminatedSeats = [...removed];
  r.eliminated = removed.length === 1 ? removed[0] : null;
  r.splitLift = split;
  removed.forEach((seat) => {
    s.players[seat - 1].status = "VOTED";
    s.firstDeparted ??= seat;
  });
  r.ballotStage = "COMPLETED";
  stop(s);
  s.phase = "NIGHT";
}
export function ballotAction(
  s: State,
  type: string,
  seat?: number | null,
  candidate?: number | null,
  now = Date.now(),
) {
  const r = round(s);
  if (s.phase !== "VOTING" || s.winner)
    throw new Error("Действие доступно во время голосования.");
  if (type === "VOTE") {
    if (
      !["INITIAL", "REVOTE"].includes(r.ballotStage ?? "INITIAL") ||
      !r.eligibleVoters.includes(seat!) ||
      s.players[seat! - 1].status !== "ALIVE"
    )
      throw new Error("Сейчас этот игрок не может голосовать.");
    if (candidate == null) delete r.votes[seat!];
    else {
      if (
        !ballotCandidates(s).includes(candidate) ||
        s.players[candidate - 1].status !== "ALIVE"
      )
        throw new Error("Выберите кандидатуру текущего голосования.");
      if (r.votes[seat!] != null && r.votes[seat!] !== candidate)
        throw new Error(
          "Игрок уже проголосовал. Сначала снимите предыдущий голос.",
        );
      r.votes[seat!] = candidate;
    }
    if (r.ballotStage === "REVOTE") r.revoteVotes = { ...r.votes };
  } else if (type === "VOTE_FINISH" || type === "VOTE_ELIMINATE") {
    if (!["INITIAL", "REVOTE"].includes(r.ballotStage ?? "INITIAL"))
      throw new Error("Это голосование уже завершено.");
    const candidates = ballotCandidates(s),
      count = (c: number) =>
        Object.values(r.votes).filter((v) => v === c).length;
    const max = Math.max(0, ...candidates.map(count)),
      leaders = candidates.filter((c) => count(c) === max);
    if (
      type === "VOTE_ELIMINATE" &&
      (max === 0 || leaders.length !== 1 || !leaders.includes(seat!))
    )
      throw new Error("Выбывает только единственный лидер голосования.");
    if (r.ballotStage === "REVOTE") r.revoteVotes = { ...r.votes };
    else r.initialVotes = { ...r.votes };
    if (!max) complete(s, [], false);
    else if (leaders.length === 1) complete(s, leaders, false);
    else {
      r.runoffCandidates = leaders;
      if (r.ballotStage !== "REVOTE") {
        r.ballotStage = "SHOOTOUT";
        r.shootoutSpoken = [];
        s.speaker = null;
        nextShootout(s, now);
      } else {
        r.ballotStage = "LIFT";
        r.liftVoters = [];
        stop(s);
      }
    }
  } else if (type === "LIFT_VOTE") {
    if (
      r.ballotStage !== "LIFT" ||
      !r.eligibleVoters.includes(seat!) ||
      s.players[seat! - 1].status !== "ALIVE"
    )
      throw new Error("Игрок не может участвовать в подъёме.");
    r.liftVoters ??= [];
    r.liftVoters = r.liftVoters.includes(seat!)
      ? r.liftVoters.filter((v) => v !== seat)
      : [...r.liftVoters, seat!];
  } else if (type === "LIFT_FINISH") {
    if (r.ballotStage !== "LIFT")
      throw new Error("Сначала завершите переголосование с равенством.");
    complete(
      s,
      (r.liftVoters?.length ?? 0) >=
        Math.floor(s.players.filter((p) => p.status === "ALIVE").length / 2)
        ? r.runoffCandidates!
        : [],
      true,
    );
  }
}
export function cardVoters(s: State, seat: number): number[] {
  const current = round(s);
  if (
    s.phase === "VOTING" &&
    ["INITIAL", "REVOTE"].includes(current?.ballotStage ?? "INITIAL") &&
    ballotCandidates(s).includes(seat)
  )
    return voters(current, seat);
  const history = Object.entries(s.rounds)
    .filter(([number]) => Number(number) <= s.round)
    .sort(([a], [b]) => Number(b) - Number(a));
  for (const [, r] of history)
    if (
      (r.eliminatedSeats ?? (r.eliminated ? [r.eliminated] : [])).includes(
        seat,
      ) &&
      !r.splitLift
    )
      return voters(r, seat);
  return [];
}
const voters = (r: RoundData, seat: number) =>
  Object.entries(r.votes)
    .filter(([, target]) => target === seat)
    .map(([voter]) => Number(voter));
