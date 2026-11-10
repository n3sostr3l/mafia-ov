package ru.polemica.studio;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Authoritative local game mechanics. Does not calculate tournament points or ratings. */
@Component
class GameRules {
  static final long SPEECH_MS = 60000;

  GameState.RoundData current(GameState s) {
    return s.rounds.computeIfAbsent(s.round, ignored -> new GameState.RoundData());
  }

  void prepare(GameState s) {
    if (s.schemaVersion < 2 && s.firstDeparted == null) {
      var previous =
          s.players.stream().filter(p -> p.pu && p.status != GameState.Status.ALIVE).toList();
      if (previous.size() == 1) s.firstDeparted = previous.getFirst().seat;
    }
    s.schemaVersion = 2;
    s.players.forEach(p -> p.pu = Objects.equals(s.firstDeparted, p.seat));
    if (s.theme.sheriff == null) s.theme.sheriff = s.theme.special;
    if (s.rounds == null) s.rounds = new TreeMap<>();
    if (!s.rounds.containsKey(s.round)) current(s).nominees = new ArrayList<>(s.nominees);
    if (s.phase == GameState.Phase.VOTING && current(s).eligibleVoters.isEmpty())
      current(s).eligibleVoters =
          new ArrayList<>(
              s.players.stream()
                  .filter(p -> p.status == GameState.Status.ALIVE)
                  .map(p -> p.seat)
                  .toList());
  }

  void update(GameState s, GameState old, Instant now) {
    prepare(s);
    prepare(old);
    if (s.phase != old.phase || s.round != old.round)
      throw ApiErrors.bad("Для перехода по фазам используйте перемотчик.");
    // Timers are changed only by server commands, never by a client snapshot.
    s.speaker = old.speaker;
    s.speechEndsAt = old.speechEndsAt;
    s.speechRemainingMs = old.speechRemainingMs;
    s.winner = old.winner;
    s.phaseBeforeFinish = old.phaseBeforeFinish;
    for (var entry : old.rounds.entrySet())
      if (entry.getKey() != s.round) s.rounds.put(entry.getKey(), entry.getValue());
    s.rounds.put(s.round, old.rounds.get(old.round));
    for (Integer seat : s.nominees) {
      if (!old.nominees.contains(seat) && player(s, seat).status != GameState.Status.ALIVE)
        throw ApiErrors.bad("Выбывшего игрока нельзя выставить на голосование.");
    }
    if (s.firstDeparted == null && old.firstDeparted == null) {
      s.firstDeparted =
          s.players.stream()
              .filter(p ->
                  p.status != GameState.Status.ALIVE
                      && player(old, p.seat).status == GameState.Status.ALIVE)
              .map(p -> p.seat)
              .findFirst()
              .orElse(null);
    }
    if (s.firstDeparted != null && player(s, s.firstDeparted).status == GameState.Status.ALIVE)
      s.firstDeparted = null;
    normalizeChecks(s);
    current(s).nominees = new ArrayList<>(s.nominees);
    current(s).votes.entrySet().removeIf(e -> !s.nominees.contains(e.getValue()));
    if (current(s).eliminated != null
        && player(s, current(s).eliminated).status == GameState.Status.ALIVE)
      current(s).eliminated = null;
    for (var p : s.players)
      if (s.phase == GameState.Phase.VOTING
          && p.status == GameState.Status.VOTED
          && player(old, p.seat).status != GameState.Status.VOTED
          && s.nominees.contains(p.seat)) current(s).eliminated = p.seat;
    if (s.speaker != null && player(s, s.speaker).status != GameState.Status.ALIVE)
      nextSpeech(s, now.toEpochMilli());
    finish(s, old.winner);
  }

  void normalizeChecks(GameState s) {
    for (var c : s.checks) {
      if (c.checker == null) {
        var checkers = s.players.stream().filter(p -> p.role == c.by).toList();
        if (checkers.size() != 1)
          throw ApiErrors.bad("Назначьте единственного Дона и Шерифа перед добавлением проверки.");
        c.checker = checkers.getFirst().seat;
      }
      var role = player(s, c.checker).role;
      if (role != GameState.Role.DON && role != GameState.Role.SHERIFF)
        throw ApiErrors.bad(
            "Проверять может только назначенный Дон или Шериф. Удалите его проверки перед сменой"
                + " роли.");
      c.by = role;
      var target = player(s, c.seat);
      c.result =
          role == GameState.Role.DON
              ? (target.role == GameState.Role.SHERIFF ? "SHERIFF" : "NOT_SHERIFF")
              : (mafia(target) ? "BLACK" : "RED");
      c.published = true; // Compatibility field: visibility is controlled for all checks together.
    }
    s.players.forEach(p -> p.revealRole = false);
  }

  void action(GameState s, String type, Integer seat, Integer candidate, Instant now) {
    prepare(s);
    String previousWinner = s.winner;
    switch (type) {
      case "PHASE_NEXT" -> phase(s, 1, now.toEpochMilli());
      case "PHASE_PREVIOUS" -> phase(s, -1, now.toEpochMilli());
      case "SPEECH_START" -> startSpeech(s, seat, now.toEpochMilli());
      case "SPEECH_NEXT" -> {
        requireDay(s);
        nextSpeech(s, now.toEpochMilli());
      }
      case "SPEECH_PAUSE" -> {
        requireDay(s);
        if (s.speechEndsAt != null)
          s.speechRemainingMs = Math.max(0, s.speechEndsAt - now.toEpochMilli());
        s.speechEndsAt = null;
      }
      case "SPEECH_RESUME" -> {
        requireDay(s);
        if (s.speaker == null) throw ApiErrors.bad("Сначала выберите выступающего.");
        if (s.speechEndsAt == null)
          s.speechEndsAt = now.toEpochMilli() + Math.max(1, s.speechRemainingMs);
      }
      case "VOTE" -> {
        if (s.phase != GameState.Phase.VOTING)
          throw ApiErrors.bad("Голоса фиксируются в фазе голосования.");
        var round = current(s);
        if (!round.eligibleVoters.contains(seat))
          throw ApiErrors.bad("Выбывший игрок не может голосовать.");
        if (candidate == null) round.votes.remove(seat);
        else {
          if (!s.nominees.contains(candidate) || !round.eligibleVoters.contains(candidate))
            throw ApiErrors.bad("Выберите кандидатуру, участвовавшую в голосовании этого раунда.");
          round.votes.put(seat, candidate);
        }
      }
      case "VOTE_ELIMINATE" -> {
        if (s.phase != GameState.Phase.VOTING
            || s.winner != null
            || !s.nominees.contains(seat)
            || player(s, seat).status != GameState.Status.ALIVE)
          throw ApiErrors.bad("Выберите живого кандидата на текущем голосовании.");
        player(s, seat).status = GameState.Status.VOTED;
        current(s).eliminated = seat;
        if (s.firstDeparted == null) s.firstDeparted = seat;
      }
      default -> throw ApiErrors.bad("Неизвестное действие.");
    }
    finish(s, previousWinner);
  }

  void phase(GameState s, int direction, long now) {
    current(s).nominees = new ArrayList<>(s.nominees);
    stopSpeech(s);
    switch (s.phase) {
      case PREPARATION -> {
        if (direction < 0) return;
        if (!rolesReady(s))
          throw ApiErrors.bad("Для начала игры назначьте 6 мирных, 2 мафии, Дона и Шерифа.");
        s.phase = GameState.Phase.DAY;
      }
      case DAY -> {
        if (direction > 0) s.phase = GameState.Phase.VOTING;
        else if (s.round > 1) {
          s.round--;
          s.phase = GameState.Phase.NIGHT;
        } else s.phase = GameState.Phase.PREPARATION;
      }
      case VOTING -> s.phase = direction > 0 ? GameState.Phase.NIGHT : GameState.Phase.DAY;
      case NIGHT -> {
        if (direction > 0) {
          if (s.round >= 100) throw ApiErrors.bad("Достигнут последний раунд.");
          s.round++;
          s.phase = GameState.Phase.DAY;
        } else s.phase = GameState.Phase.VOTING;
      }
      case FINISHED -> {
        if (direction > 0) return;
        s.phase = s.phaseBeforeFinish == null ? GameState.Phase.NIGHT : s.phaseBeforeFinish;
      }
    }
    var r = current(s);
    s.nominees = new ArrayList<>(r.nominees);
    if (s.phase == GameState.Phase.VOTING && r.eligibleVoters.isEmpty())
      r.eligibleVoters =
          new ArrayList<>(
              s.players.stream()
                  .filter(p -> p.status == GameState.Status.ALIVE)
                  .map(p -> p.seat)
                  .toList());
    // Rewinding reviews saved data without restarting a clock. A forward day starts the minute.
    if (direction > 0 && s.phase == GameState.Phase.DAY && s.winner == null) {
      int first = (s.round - 1) % 10 + 1;
      for (int offset = 0; offset < 10; offset++) {
        int number = (first - 1 + offset) % 10 + 1;
        if (player(s, number).status == GameState.Status.ALIVE && !r.spokenSeats.contains(number)) {
          startSpeech(s, number, now);
          break;
        }
      }
    }
  }

  void startSpeech(GameState s, Integer seat, long now) {
    requireDay(s);
    if (player(s, seat).status != GameState.Status.ALIVE)
      throw ApiErrors.bad("Выбывшему игроку нельзя передать слово.");
    s.speaker = seat;
    s.speechRemainingMs = SPEECH_MS;
    s.speechEndsAt = now + SPEECH_MS;
  }

  void expire(GameState s, Instant now) {
    while (s.speechEndsAt != null && s.speechEndsAt <= now.toEpochMilli()) {
      long endedAt = s.speechEndsAt;
      nextSpeech(s, endedAt);
    }
  }

  void nextSpeech(GameState s, long now) {
    int previous = s.speaker == null ? (s.round - 2 + 10) % 10 + 1 : s.speaker;
    var spoken = current(s).spokenSeats;
    if (s.speaker != null && !spoken.contains(s.speaker)) spoken.add(s.speaker);
    for (int offset = 1; offset <= 10; offset++) {
      int next = (previous - 1 + offset) % 10 + 1;
      if (player(s, next).status == GameState.Status.ALIVE && !spoken.contains(next)) {
        startSpeech(s, next, now);
        return;
      }
    }
    phase(s, 1, now);
  }

  void finish(GameState s, String previousWinner) {
    s.players.forEach(p -> p.pu = Objects.equals(s.firstDeparted, p.seat));
    String winner = null;
    if (s.phase != GameState.Phase.PREPARATION && rolesReady(s)) {
      long black =
          s.players.stream().filter(p -> p.status == GameState.Status.ALIVE && mafia(p)).count();
      long red =
          s.players.stream().filter(p -> p.status == GameState.Status.ALIVE && !mafia(p)).count();
      if (black == 0) winner = "CITY";
      else if (black >= red) winner = "MAFIA";
    }
    s.winner = winner;
    if (winner != null) {
      if (!winner.equals(previousWinner)) {
        s.phaseBeforeFinish = s.phase;
        s.phase = GameState.Phase.FINISHED;
      }
      stopSpeech(s);
    } else if (s.phase == GameState.Phase.FINISHED) {
      s.phase = s.phaseBeforeFinish == null ? GameState.Phase.DAY : s.phaseBeforeFinish;
      s.phaseBeforeFinish = null;
    }
  }

  boolean rolesReady(GameState s) {
    return s.players.stream().filter(p -> p.role == GameState.Role.DON).count() == 1
        && s.players.stream().filter(p -> p.role == GameState.Role.SHERIFF).count() == 1
        && s.players.stream().filter(p -> p.role == GameState.Role.MAFIA).count() == 2
        && s.players.stream().filter(p -> p.role == GameState.Role.CIVILIAN).count() == 6;
  }

  static boolean mafia(GameState.Player p) {
    return p.role == GameState.Role.MAFIA || p.role == GameState.Role.DON;
  }

  static GameState.Player player(GameState s, Integer seat) {
    return s.players.stream()
        .filter(p -> Objects.equals(p.seat, seat))
        .findFirst()
        .orElseThrow(() -> ApiErrors.bad("Номер игрока: от 1 до 10."));
  }

  private void requireDay(GameState s) {
    if (s.phase != GameState.Phase.DAY || s.winner != null)
      throw ApiErrors.bad("Выступления доступны во время дня активной партии.");
  }

  private void stopSpeech(GameState s) {
    s.speaker = null;
    s.speechEndsAt = null;
    s.speechRemainingMs = SPEECH_MS;
  }
}
