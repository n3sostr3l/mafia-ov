package ru.polemica.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class GameService {
  private final GameRepository games;
  private final TournamentRepository tournaments;
  private final EventRepository events;
  final ObjectMapper mapper;
  final GameValidator validator;
  final ApplicationEventPublisher publisher;
  final GameRules rules;

  GameService(
      GameRepository games,
      TournamentRepository tournaments,
      EventRepository events,
      ObjectMapper mapper,
      GameValidator validator,
      ApplicationEventPublisher publisher,
      GameRules rules) {
    this.games = games;
    this.tournaments = tournaments;
    this.events = events;
    this.mapper = mapper;
    this.validator = validator;
    this.publisher = publisher;
    this.rules = rules;
  }

  record Changed(String id, boolean tokenRotated) {}

  record GameView(
      String id,
      String tournamentId,
      String tournamentName,
      int tableNumber,
      int gameNumber,
      long revision,
      Instant updatedAt,
      GameState state) {}

  record Summary(String id, int tableNumber, int gameNumber, long revision, Instant updatedAt) {}

  List<Tournament> tournaments() {
    return tournaments.findAllByOrderByCreatedAtDesc();
  }

  @Transactional
  Tournament createTournament(String name, String externalId) {
    var t = new Tournament();
    t.id = UUID.randomUUID().toString();
    t.name = name.strip();
    t.externalId = externalId;
    t.createdAt = Instant.now();
    return tournaments.save(t);
  }

  List<Summary> summaries(String id) {
    getTournament(id);
    return games.findByTournamentIdOrderByTableNumberAscGameNumberAsc(id).stream()
        .map(g -> new Summary(g.id, g.tableNumber, g.gameNumber, g.revision, g.updatedAt))
        .toList();
  }

  List<Map<String, Object>> history(String id) {
    getGame(id);
    return events.findTop50ByGameIdOrderByRevisionDesc(id).stream()
        .map(e ->
            Map.<String, Object>of(
                "id",
                e.id,
                "revision",
                e.revision,
                "description",
                e.description,
                "createdAt",
                e.createdAt))
        .toList();
  }

  Game getGame(String id) {
    return games.findById(id).orElseThrow(ApiErrors::missing);
  }

  Tournament getTournament(String id) {
    return tournaments.findById(id).orElseThrow(ApiErrors::missing);
  }

  Game publicGame(String id, String token) {
    var g = games.findById(id).orElseThrow(ApiErrors::missing);
    if (token == null
        || !MessageDigest.isEqual(
            g.overlayToken.getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8))) throw ApiErrors.missing();
    return g;
  }

  GameState state(Game g) {
    try {
      var s = mapper.readValue(g.stateJson, GameState.class);
      rules.prepare(s);
      return s;
    } catch (Exception e) {
      throw new IllegalStateException("Stored game is invalid", e);
    }
  }

  String json(GameState s) {
    try {
      return mapper.writeValueAsString(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  GameView view(Game g, boolean publicView) {
    var s = state(g);
    if (publicView) {
      for (var p : s.players) {
        boolean revealed = s.showRoles;
        String image = effectivePhoto(s, p, revealed);
        p.role = revealed ? p.role : GameState.Role.UNKNOWN;
        p.photo = image;
        p.importedPhoto = null;
        p.revealRole = false;
      }
      s.checks = s.showChecks ? s.checks : List.of();
      s.checks.forEach(c -> c.checker = null);
      s.theme.rolePhotos = Map.of();
      s.theme.useRolePhotos = false;
    }
    var t = tournaments.findById(g.tournamentId).orElseThrow(ApiErrors::missing);
    return new GameView(
        g.id, g.tournamentId, t.name, g.tableNumber, g.gameNumber, g.revision, g.updatedAt, s);
  }

  String effectivePhoto(GameState s, GameState.Player p, boolean reveal) {
    if (p.photo != null && !p.photo.isBlank()) return p.photo;
    if (reveal && s.theme.useRolePhotos && s.theme.rolePhotos.get(p.role) != null)
      return s.theme.rolePhotos.get(p.role);
    return p.importedPhoto;
  }

  @Transactional
  GameView create(String tid, int table, int number, boolean demo) {
    getTournament(tid);
    if (table < 1 || table > 100 || number < 1 || number > 10000)
      throw ApiErrors.bad("Проверьте номер стола и партии.");
    var g = new Game();
    g.id = UUID.randomUUID().toString();
    g.tournamentId = tid;
    g.tableNumber = table;
    g.gameNumber = number;
    g.overlayToken = OverlayTokens.randomToken();
    g.stateJson = json(GameState.initial(demo));
    g.updatedAt = Instant.now();
    games.saveAndFlush(g);
    event(g, "Партия создана");
    return view(g, false);
  }

  @Transactional
  GameView update(String id, long revision, GameState state, String description) {
    var g = games.lockById(id).orElseThrow(ApiErrors::missing);
    if (g.revision != revision)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Партия изменилась в другой вкладке. Загрузите актуальное состояние.");
    validator.validate(state);
    rules.update(state, state(g), Instant.now());
    validator.validate(state);
    return saveState(g, state, description);
  }

  private GameView saveState(Game g, GameState state, String description) {
    g.stateJson = json(state);
    g.speechDeadline = state.speechEndsAt == null ? null : Instant.ofEpochMilli(state.speechEndsAt);
    g.updatedAt = Instant.now();
    games.saveAndFlush(g);
    event(g, description);
    publisher.publishEvent(new Changed(g.id, false));
    return view(g, false);
  }

  @Transactional
  GameView action(String id, long revision, String type, Integer seat, Integer candidate) {
    var g = games.lockById(id).orElseThrow(ApiErrors::missing);
    if (g.revision != revision)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Партия изменилась. Повторите действие после обновления.");
    var s = state(g);
    rules.action(s, type, seat, candidate, Instant.now());
    validator.validate(s);
    return saveState(
        g,
        s,
        switch (type) {
          case "PHASE_NEXT", "PHASE_PREVIOUS" -> "Переход по фазам";
          case "VOTE" -> "Голос игрока " + seat;
          case "VOTE_ELIMINATE" -> "Выбыл по голосованию игрок " + seat;
          default -> "Управление минутой речи";
        });
  }

  @Transactional
  public void advanceDue(String id, Instant deadline) {
    var g = games.lockById(id).orElseThrow(ApiErrors::missing);
    var now = Instant.now();
    if (g.speechDeadline == null
        || !g.speechDeadline.equals(deadline)
        || g.speechDeadline.isAfter(now)) return;
    var s = state(g);
    rules.expire(s, now);
    validator.validate(s);
    saveState(g, s, "Минута завершена: автоматический переход речи");
  }

  @Transactional
  GameView restore(String id, String eventId, long revision) {
    getGame(id);
    var e =
        events.findById(eventId).filter(x -> x.gameId.equals(id)).orElseThrow(ApiErrors::missing);
    try {
      var g = games.lockById(id).orElseThrow(ApiErrors::missing);
      if (g.revision != revision)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Партия изменилась.");
      var restored = mapper.readValue(e.stateJson, GameState.class);
      rules.prepare(restored);
      restored.speechEndsAt = null; // Restoring history never revives an expired clock.
      rules.normalizeChecks(restored);
      validator.validate(restored);
      return saveState(g, restored, "Восстановлена версия " + e.revision);
    } catch (com.fasterxml.jackson.core.JsonProcessingException x) {
      throw new IllegalStateException(x);
    }
  }

  @Transactional
  String rotate(String id) {
    var g = games.lockById(id).orElseThrow(ApiErrors::missing);
    g.overlayToken = OverlayTokens.randomToken();
    games.saveAndFlush(g);
    publisher.publishEvent(new Changed(g.id, true));
    return g.overlayToken;
  }

  private void event(Game g, String description) {
    var e = new GameEvent();
    e.id = UUID.randomUUID().toString();
    e.gameId = g.id;
    e.revision = g.revision;
    e.description =
        description == null || description.isBlank()
            ? "Состояние обновлено"
            : description.substring(0, Math.min(200, description.length()));
    e.stateJson = g.stateJson;
    e.createdAt = g.updatedAt;
    events.save(e);
  }
}
