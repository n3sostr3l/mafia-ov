package ru.polemica.studio;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class VotingRulesTest {
  final GameRules rules = new GameRules();
  final Instant now = Instant.parse("2026-11-14T12:00:00Z");

  GameState voting() {
    var s = GameState.initial(true);
    rules.prepare(s);
    rules.action(s, "PHASE_NEXT", null, null, now);
    return s;
  }

  void act(GameState s, String type, Integer seat, Integer candidate) {
    rules.action(s, type, seat, candidate, now);
  }

  GameState tied() {
    var s = voting();
    act(s, "VOTE", 1, 2);
    act(s, "VOTE", 4, 6);
    act(s, "VOTE_FINISH", null, null);
    return s;
  }

  GameState lift() {
    var s = tied();
    act(s, "SPEECH_NEXT", null, null);
    act(s, "SPEECH_NEXT", null, null);
    act(s, "VOTE", 1, 2);
    act(s, "VOTE", 4, 6);
    act(s, "VOTE_FINISH", null, null);
    return s;
  }

  @Test
  void uniqueLeaderIsAutomaticallyEliminatedAndTheBallotCannotBeFinishedTwice() {
    var s = voting();
    act(s, "VOTE", 1, 2);
    act(s, "VOTE", 4, 2);
    act(s, "VOTE", 5, 6);
    act(s, "VOTE_FINISH", null, null);
    assertThat(s.players.get(1).status).isEqualTo(GameState.Status.VOTED);
    assertThat(s.players.get(5).status).isEqualTo(GameState.Status.ALIVE);
    assertThat(s.phase).isEqualTo(GameState.Phase.NIGHT);
    assertThat(rules.current(s).eliminatedSeats).containsExactly(2);
    assertThat(rules.current(s).initialVotes).containsEntry(5, 6).containsEntry(1, 2);
    assertThatThrownBy(() -> act(s, "VOTE_FINISH", null, null)).hasMessageContaining("голосования");
  }

  @Test
  void tieStartsThirtySecondSpeechesOnlyForLeadersAndExpiryStartsANewBallot() {
    var s = tied();
    assertThat(rules.current(s).ballotStage).isEqualTo("SHOOTOUT");
    assertThat(s.speaker).isEqualTo(2);
    assertThat(s.speechEndsAt).isEqualTo(now.plusSeconds(30).toEpochMilli());
    assertThatThrownBy(() -> act(s, "SPEECH_START", 9, null)).hasMessageContaining("лидеры");
    rules.expire(s, now.plusSeconds(30));
    assertThat(s.speaker).isEqualTo(6);
    rules.expire(s, now.plusSeconds(60));
    assertThat(s.speaker).isNull();
    assertThat(rules.current(s).ballotStage).isEqualTo("REVOTE");
    assertThat(rules.current(s).votes).isEmpty();
    assertThat(rules.current(s).initialVotes).hasSize(2);
    assertThatThrownBy(() -> act(s, "VOTE", 1, 9)).hasMessageContaining("кандидатуру");
    act(s, "VOTE", 1, 6);
    act(s, "VOTE_FINISH", null, null);
    assertThat(rules.current(s).eliminated).isEqualTo(6);
  }

  @Test
  void speechesCanBePausedResumedAndSkipped() {
    var s = tied();
    rules.action(s, "SPEECH_PAUSE", null, null, now.plusSeconds(5));
    assertThat(s.speechRemainingMs).isEqualTo(25000);
    rules.action(s, "SPEECH_RESUME", null, null, now.plusSeconds(10));
    assertThat(s.speechEndsAt).isEqualTo(now.plusSeconds(35).toEpochMilli());
    act(s, "SPEECH_NEXT", null, null);
    assertThat(s.speaker).isEqualTo(6);
    act(s, "SPEECH_NEXT", null, null);
    assertThat(rules.current(s).ballotStage).isEqualTo("REVOTE");
  }

  @Test
  void repeatedTieBelowThresholdLeavesEveryoneAndAtThresholdLiftsAll() {
    var s = lift();
    assertThat(rules.current(s).ballotStage).isEqualTo("LIFT");
    for (int n : List.of(1, 2, 4)) act(s, "LIFT_VOTE", n, null);
    act(s, "LIFT_FINISH", null, null);
    assertThat(rules.current(s).eliminatedSeats).isEmpty();
    assertThat(s.players.get(1).status).isEqualTo(GameState.Status.ALIVE);
    s = lift();
    for (int n : List.of(1, 2, 4, 5)) act(s, "LIFT_VOTE", n, null);
    act(s, "LIFT_FINISH", null, null);
    assertThat(rules.current(s).eliminatedSeats).containsExactly(2, 6);
    assertThat(rules.current(s).splitLift).isTrue();
    assertThat(s.players.get(1).status).isEqualTo(GameState.Status.VOTED);
    assertThat(s.players.get(5).status).isEqualTo(GameState.Status.VOTED);
    assertThat(rules.current(s).initialVotes).hasSize(2);
    assertThat(rules.current(s).revoteVotes).hasSize(2);
  }

  @Test
  void emptyBallotDoesNotEliminateEveryoneOrStartSpeeches() {
    var s = voting();
    act(s, "VOTE_FINISH", null, null);
    assertThat(rules.current(s).eliminatedSeats).isEmpty();
    assertThat(s.phase).isEqualTo(GameState.Phase.NIGHT);
  }

  @Test
  void finishingAndRewindingKeepTheResultAndBothBallots() {
    var s = lift();
    act(s, "LIFT_FINISH", null, null);
    act(s, "PHASE_NEXT", null, null);
    act(s, "PHASE_PREVIOUS", null, null);
    act(s, "PHASE_PREVIOUS", null, null);
    assertThat(rules.current(s).ballotStage).isEqualTo("COMPLETED");
    assertThat(rules.current(s).initialVotes).containsEntry(1, 2);
    assertThat(rules.current(s).revoteVotes).containsEntry(4, 6);
  }
}
