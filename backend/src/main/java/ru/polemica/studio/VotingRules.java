package ru.polemica.studio;

import java.util.*;

/** The complete ballot, tie speeches, runoff and lift decision stay in the round snapshot. */
final class VotingRules {
  static GameState.RoundData round(GameState s) {
    return s.rounds.get(s.round);
  }

  static List<Integer> candidates(GameState s) {
    var r = round(s);
    return "INITIAL".equals(r.ballotStage) ? s.nominees : r.runoffCandidates;
  }

  static void action(GameState s, String type, Integer seat, Integer candidate, long now) {
    var r = round(s);
    if (s.phase != GameState.Phase.VOTING || s.winner != null)
      throw ApiErrors.bad("Действие доступно во время голосования.");
    switch (type) {
      case "VOTE" -> {
        if (!List.of("INITIAL", "REVOTE").contains(r.ballotStage))
          throw ApiErrors.bad("Сейчас нельзя отмечать голоса за кандидатуры.");
        if (!r.eligibleVoters.contains(seat)
            || GameRules.player(s, seat).status != GameState.Status.ALIVE)
          throw ApiErrors.bad("Выбывший игрок не может голосовать.");
        if (candidate == null) r.votes.remove(seat);
        else {
          if (!candidates(s).contains(candidate)
              || GameRules.player(s, candidate).status != GameState.Status.ALIVE)
            throw ApiErrors.bad("Выберите кандидатуру текущего голосования.");
          if (r.votes.containsKey(seat) && !r.votes.get(seat).equals(candidate))
            throw ApiErrors.bad("Игрок уже проголосовал. Сначала снимите предыдущий голос.");
          r.votes.put(seat, candidate);
        }
        if ("REVOTE".equals(r.ballotStage)) r.revoteVotes = new TreeMap<>(r.votes);
      }
      case "VOTE_FINISH", "VOTE_ELIMINATE" -> {
        if (!List.of("INITIAL", "REVOTE").contains(r.ballotStage))
          throw ApiErrors.bad("Это голосование уже завершено.");
        int max =
            candidates(s).stream()
                .mapToInt(c -> Collections.frequency(new ArrayList<>(r.votes.values()), c))
                .max()
                .orElse(0);
        var leaders =
            candidates(s).stream()
                .filter(c -> Collections.frequency(new ArrayList<>(r.votes.values()), c) == max)
                .toList();
        if ("VOTE_ELIMINATE".equals(type)
            && (max == 0 || leaders.size() != 1 || !leaders.contains(seat)))
          throw ApiErrors.bad("Выбывает только единственный лидер голосования.");
        if ("INITIAL".equals(r.ballotStage)) r.initialVotes = new TreeMap<>(r.votes);
        else r.revoteVotes = new TreeMap<>(r.votes);
        if (max == 0) complete(s, List.of(), false);
        else if (leaders.size() == 1) complete(s, leaders, false);
        else {
          r.runoffCandidates = new ArrayList<>(leaders);
          if ("INITIAL".equals(r.ballotStage)) {
            r.ballotStage = "SHOOTOUT";
            r.shootoutSpoken.clear();
            s.speaker = null;
            nextShootout(s, now);
          } else {
            r.ballotStage = "LIFT";
            r.liftVoters.clear();
            stop(s);
          }
        }
      }
      case "LIFT_VOTE" -> {
        if (!"LIFT".equals(r.ballotStage)
            || !r.eligibleVoters.contains(seat)
            || GameRules.player(s, seat).status != GameState.Status.ALIVE)
          throw ApiErrors.bad("Выберите живого участника голосования за подъём.");
        if (r.liftVoters.contains(seat)) r.liftVoters.remove(seat);
        else r.liftVoters.add(seat);
      }
      case "LIFT_FINISH" -> {
        if (!"LIFT".equals(r.ballotStage))
          throw ApiErrors.bad("Сначала завершите переголосование с равенством.");
        long alive = s.players.stream().filter(p -> p.status == GameState.Status.ALIVE).count();
        complete(s, r.liftVoters.size() >= alive / 2 ? r.runoffCandidates : List.of(), true);
      }
      default -> throw ApiErrors.bad("Неизвестное действие голосования.");
    }
  }

  static void nextShootout(GameState s, long now) {
    var r = round(s);
    if (s.speaker != null && !r.shootoutSpoken.contains(s.speaker)) r.shootoutSpoken.add(s.speaker);
    Integer next =
        r.runoffCandidates.stream()
            .filter(c ->
                !r.shootoutSpoken.contains(c)
                    && GameRules.player(s, c).status == GameState.Status.ALIVE)
            .findFirst()
            .orElse(null);
    if (next != null) {
      s.speaker = next;
      s.speechRemainingMs = 30000;
      s.speechEndsAt = now + 30000;
    } else {
      stop(s);
      r.ballotStage = "REVOTE";
      r.votes.clear();
      r.revoteVotes.clear();
    }
  }

  private static void complete(GameState s, List<Integer> removed, boolean split) {
    var r = round(s);
    r.eliminatedSeats = new ArrayList<>(removed);
    r.eliminated = removed.size() == 1 ? removed.getFirst() : null;
    r.splitLift = split;
    for (Integer seat : removed) {
      GameRules.player(s, seat).status = GameState.Status.VOTED;
      if (s.firstDeparted == null) s.firstDeparted = seat;
    }
    r.ballotStage = "COMPLETED";
    stop(s);
    s.phase = GameState.Phase.NIGHT;
  }

  private static void stop(GameState s) {
    s.speaker = null;
    s.speechEndsAt = null;
    s.speechRemainingMs = 60000;
  }
}
