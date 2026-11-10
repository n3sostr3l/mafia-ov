package ru.polemica.studio;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

@Entity
@Table(name = "tournaments")
class Tournament {
  @Id String id;

  String name;

  @Column(name = "external_id")
  String externalId;

  @Column(name = "created_at")
  Instant createdAt;
}

interface TournamentRepository extends JpaRepository<Tournament, String> {
  List<Tournament> findAllByOrderByCreatedAtDesc();
}

@Entity
@Table(name = "games")
class Game {
  @Column(name = "speech_deadline")
  Instant speechDeadline;

  @Id String id;

  @Column(name = "tournament_id")
  String tournamentId;

  @Column(name = "table_number")
  int tableNumber;

  @Column(name = "game_number")
  int gameNumber;

  @Column(name = "overlay_token")
  String overlayToken;

  @Column(name = "state_json", columnDefinition = "TEXT")
  String stateJson;

  @Version long revision;

  @Column(name = "updated_at")
  Instant updatedAt;
}

interface GameRepository extends JpaRepository<Game, String> {
  List<Game> findTop100BySpeechDeadlineLessThanEqual(Instant now);

  List<Game> findByTournamentIdOrderByTableNumberAscGameNumberAsc(String tournamentId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select g from Game g where g.id = :id")
  Optional<Game> lockById(@Param("id") String id);
}

@Entity
@Table(name = "game_events")
class GameEvent {
  @Id String id;

  @Column(name = "game_id")
  String gameId;

  long revision;
  String description;

  @Column(name = "state_json", columnDefinition = "TEXT")
  String stateJson;

  @Column(name = "created_at")
  Instant createdAt;
}

interface EventRepository extends JpaRepository<GameEvent, String> {
  List<GameEvent> findTop50ByGameIdOrderByRevisionDesc(String gameId);
}

@Entity
@Table(name = "uploads")
class Upload {
  @Id String id;

  @Column(name = "created_at")
  Instant createdAt;
}

interface UploadRepository extends JpaRepository<Upload, String> {}
