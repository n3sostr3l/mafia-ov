package ru.polemica.studio;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class GameRulesTest {
  final GameRules rules = new GameRules();
  final Instant now = Instant.parse("2026-11-10T12:00:00Z");

  GameState game() {
    var s = GameState.initial(true);
    rules.prepare(s);
    return s;
  }

  GameState copy(GameState s) {
    return new ObjectMapper().convertValue(s, GameState.class);
  }

  @Test
  void checksAlwaysFollowAssignedRoles() {
    var s = game();
    for (var role : List.of(GameState.Role.SHERIFF, GameState.Role.DON)) {
      for (int seat : List.of(1, 2, 4, 6)) {
        var c = new GameState.Check();
        c.id = UUID.randomUUID().toString();
        c.by = role;
        c.seat = seat;
        c.result = "FORGED";
        s.checks.add(c);
      }
    }
    rules.normalizeChecks(s);
    assertThat(s.checks.stream().map(c -> c.result))
        .containsExactly(
            "RED", "BLACK", "RED", "BLACK", "NOT_SHERIFF", "NOT_SHERIFF", "SHERIFF", "NOT_SHERIFF");
    assertThat(s.checks.get(0).checker).isEqualTo(4);
    assertThat(s.checks.get(4).checker).isEqualTo(6);
  }

  @Test
  void winsForCityAndMafiaAndCorrectionReopensGame() {
    var s = game();
    for (var p : s.players) if (GameRules.mafia(p)) p.status = GameState.Status.VOTED;
    rules.finish(s, null);
    assertThat(s.winner).isEqualTo("CITY");
    assertThat(s.phase).isEqualTo(GameState.Phase.FINISHED);
    assertThat(s.speaker).isNull();
    s.players.get(1).status = GameState.Status.ALIVE;
    rules.finish(s, "CITY");
    assertThat(s.winner).isNull();
    assertThat(s.phase).isEqualTo(GameState.Phase.DAY);
    s = game();
    for (int seat : List.of(1, 3, 5, 7)) s.players.get(seat - 1).status = GameState.Status.SHOT;
    rules.finish(s, null);
    assertThat(s.winner).isEqualTo("MAFIA");
  }

  @Test
  void incompletePreparationDoesNotProduceFalseVictory() {
    var s = GameState.initial(false);
    rules.prepare(s);
    rules.finish(s, null);
    assertThat(s.winner).isNull();
    assertThatThrownBy(() -> rules.action(s, "PHASE_NEXT", null, null, now))
        .hasMessageContaining("назначьте");
  }

  @Test
  void phaseRewindRestoresNominationsAndVotesAcrossRounds() {
    var s = game();
    rules.action(s, "PHASE_NEXT", null, null, now); // Day 2 -> vote 2
    rules.action(s, "VOTE", 1, 2, now);
    rules.action(s, "PHASE_NEXT", null, null, now); // Night 2
    rules.action(s, "PHASE_NEXT", null, null, now); // Day 3
    assertThat(s.round).isEqualTo(3);
    assertThat(s.nominees).isEmpty();
    s.nominees.add(7);
    rules.action(s, "PHASE_PREVIOUS", null, null, now); // Night 2
    assertThat(s.round).isEqualTo(2);
    assertThat(s.nominees).containsExactly(2, 6, 9);
    rules.action(s, "PHASE_PREVIOUS", null, null, now);
    assertThat(rules.current(s).votes).containsEntry(1, 2);
    rules.action(s, "PHASE_NEXT", null, null, now);
    rules.action(s, "PHASE_NEXT", null, null, now);
    assertThat(s.nominees).containsExactly(7);
  }

  @Test
  void rejectsDepartedNomineesAndVotersAndKeepsOneVotePerVoter() {
    var old = game();
    var s = copy(old);
    s.nominees.add(3);
    final var invalid = s;
    assertThatThrownBy(() -> rules.update(invalid, old, now)).hasMessageContaining("Выбывшего");
    s = game();
    rules.action(s, "PHASE_NEXT", null, null, now);
    final var voting = s;
    assertThatThrownBy(() -> rules.action(voting, "VOTE", 3, 2, now))
        .hasMessageContaining("Выбывший");
    rules.action(s, "VOTE", 1, 2, now);
    rules.action(s, "VOTE", 1, 6, now);
    assertThat(rules.current(s).votes).hasSize(1).containsEntry(1, 6);
    rules.action(s, "VOTE_ELIMINATE", 6, null, now);
    assertThat(s.players.get(5).status).isEqualTo(GameState.Status.VOTED);
    assertThat(rules.current(s).votes).containsEntry(1, 6);
    assertThat(rules.current(s).eliminated).isEqualTo(6);
  }

  @Test
  void firstDepartedIsSingleSeatAndCanBeCorrected() {
    var old = game();
    var s = copy(old);
    s.players.get(4).status = GameState.Status.SHOT;
    rules.update(s, old, now);
    assertThat(s.firstDeparted).isEqualTo(3);
    old = copy(s);
    s.firstDeparted = 5;
    rules.update(s, old, now);
    assertThat(s.firstDeparted).isEqualTo(5);
    assertThat(s.players.stream().filter(p -> p.pu).map(p -> p.seat)).containsExactly(5);
    old = copy(s);
    s.players.get(4).status = GameState.Status.ALIVE;
    rules.update(s, old, now);
    assertThat(s.firstDeparted).isNull();
    assertThat(s.players.stream().filter(p -> p.pu)).isEmpty();
  }

  @Test
  void migratesLegacyPuWithoutAllowingTwoFirstDepartedPlayers() {
    var s = GameState.initial(true);
    s.schemaVersion = 0;
    s.firstDeparted = null;
    s.players.get(2).pu = true;
    rules.prepare(s);
    assertThat(s.firstDeparted).isEqualTo(3);
    s = GameState.initial(true);
    s.schemaVersion = 0;
    s.firstDeparted = null;
    s.players.get(2).pu = true;
    s.players.get(4).status = GameState.Status.SHOT;
    s.players.get(4).pu = true;
    rules.prepare(s);
    assertThat(s.firstDeparted).isNull();
    assertThat(s.players.stream().filter(p -> p.pu)).isEmpty();
  }

  @Test
  void minuteAdvancesSkipsDepartedPlayersAndEndsCircleAtVoting() {
    var s = game();
    s.round = 1;
    rules.prepare(s);
    rules.startSpeech(s, 2, now.toEpochMilli());
    rules.expire(s, now.plusSeconds(60));
    assertThat(s.speaker).isEqualTo(4);
    assertThat(s.speechEndsAt).isEqualTo(now.plusSeconds(120).toEpochMilli());
    rules.expire(s, now.plusSeconds(600));
    assertThat(s.phase).isEqualTo(GameState.Phase.VOTING);
    assertThat(s.speaker).isNull();
    assertThat(s.speechEndsAt).isNull();
    assertThat(rules.current(s).spokenSeats).hasSize(9).doesNotContain(3);
  }

  @Test
  void pauseAndResumePreserveRemainingTimeAndCannotDoubleResume() {
    var s = game();
    rules.startSpeech(s, 4, now.toEpochMilli());
    rules.action(s, "SPEECH_PAUSE", null, null, now.plusSeconds(17));
    assertThat(s.speechRemainingMs).isEqualTo(43000);
    assertThat(s.speechEndsAt).isNull();
    rules.expire(s, now.plusSeconds(100));
    assertThat(s.speaker).isEqualTo(4);
    rules.action(s, "SPEECH_RESUME", null, null, now.plusSeconds(100));
    long end = s.speechEndsAt;
    rules.action(s, "SPEECH_RESUME", null, null, now.plusSeconds(101));
    assertThat(s.speechEndsAt).isEqualTo(end);
  }

  @Test
  void eliminatingCurrentSpeakerAdvancesImmediatelyAndStoppingAtVictoryCancelsClock() {
    var old = game();
    rules.startSpeech(old, 4, now.toEpochMilli());
    var s = copy(old);
    s.players.get(3).status = GameState.Status.VOTED;
    rules.update(s, old, now.plusSeconds(5));
    assertThat(s.speaker).isEqualTo(5);
    old = copy(s);
    for (var p : s.players) if (GameRules.mafia(p)) p.status = GameState.Status.VOTED;
    rules.update(s, old, now.plusSeconds(10));
    assertThat(s.winner).isEqualTo("CITY");
    assertThat(s.speechEndsAt).isNull();
  }
}
