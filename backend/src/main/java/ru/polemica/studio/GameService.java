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
  private final TournamentPlayerRepository tournamentPlayers;
  private final EventRepository events;
  private final UploadRepository uploads;
  final ObjectMapper mapper;
  final GameValidator validator;
  final ApplicationEventPublisher publisher;
  final GameRules rules;

  GameService(
      GameRepository games,
      TournamentRepository tournaments,
      TournamentPlayerRepository tournamentPlayers,
      EventRepository events,
      UploadRepository uploads,
      ObjectMapper mapper,
      GameValidator validator,
      ApplicationEventPublisher publisher,
      GameRules rules) {
    this.games = games;
    this.tournaments = tournaments;
    this.tournamentPlayers = tournamentPlayers;
    this.events = events;
    this.uploads = uploads;
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

  record TournamentPlayerView(String id, String name, String photo) {}

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

  @Transactional
  List<TournamentPlayerView> tournamentPlayers(String id) {
    getTournament(id);
    var roster = tournamentPlayers.findByTournamentIdOrderByCreatedAtAsc(id);
    if (roster.isEmpty()) {
      seedRosterFromGames(id);
      roster = tournamentPlayers.findByTournamentIdOrderByCreatedAtAsc(id);
    }
    return roster.stream().map(this::playerView).toList();
  }

  @Transactional
  TournamentPlayerView createTournamentPlayer(String tournamentId, String name, String photoId) {
    getTournament(tournamentId);
    String cleanName = cleanPlayerName(name);
    String normalized = normalizePlayerName(cleanName);
    var existing =
        tournamentPlayers.findByTournamentIdAndNormalizedName(tournamentId, normalized);
    if (existing.isPresent()) return playerView(existing.get());
    validatePhoto(photoId);
    var player = new TournamentPlayer();
    player.id = UUID.randomUUID().toString();
    player.tournamentId = tournamentId;
    player.name = cleanName;
    player.normalizedName = normalized;
    player.photoId = photoId;
    player.createdAt = Instant.now();
    player = tournamentPlayers.saveAndFlush(player);
    propagateProfile(player, normalized);
    return playerView(player);
  }

  @Transactional
  TournamentPlayerView updateTournamentPlayer(
      String tournamentId, String playerId, String name, String photoId) {
    var profile =
        tournamentPlayers
            .findByIdAndTournamentId(playerId, tournamentId)
            .orElseThrow(ApiErrors::missing);
    String previousName = profile.normalizedName;
    String cleanName = cleanPlayerName(name);
    String normalized = normalizePlayerName(cleanName);
    if (tournamentPlayers
        .findByTournamentIdAndNormalizedName(tournamentId, normalized)
        .filter(existing -> !existing.id.equals(profile.id))
        .isPresent()) throw ApiErrors.bad("Игрок с таким ником уже есть в списке турнира.");
    validatePhoto(photoId);

    profile.name = cleanName;
    profile.normalizedName = normalized;
    profile.photoId = photoId == null || photoId.isBlank() ? null : photoId;
    tournamentPlayers.saveAndFlush(profile);
    propagateProfile(profile, previousName);
    return playerView(profile);
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
    applyRoster(g, s);
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
  GameView create(
      String tid, int table, int number, boolean demo, List<String> playerIds) {
    getTournament(tid);
    if (table < 1 || table > 100 || number < 1 || number > 10000)
      throw ApiErrors.bad("Проверьте номер стола и партии.");
    if (playerIds != null
        && (playerIds.size() != 10
            || playerIds.stream().filter(Objects::nonNull).distinct().count()
                != playerIds.stream().filter(Objects::nonNull).count()))
      throw ApiErrors.bad("Выберите не более одного места для каждого игрока.");
    var g = new Game();
    g.id = UUID.randomUUID().toString();
    g.tournamentId = tid;
    g.tableNumber = table;
    g.gameNumber = number;
    g.overlayToken = OverlayTokens.randomToken();
    var state = GameState.initial(demo);
    if (playerIds != null) {
      for (int i = 0; i < playerIds.size(); i++) {
        var id = playerIds.get(i);
        if (id != null && !id.isBlank()) state.players.get(i).tournamentPlayerId = id;
      }
    }
    applyRoster(g, state);
    g.stateJson = json(state);
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
    applyRoster(g, state);
    validator.validate(state);
    rules.update(state, state(g), Instant.now());
    validator.validate(state);
    return saveState(g, state, description);
  }

  private GameView saveState(Game g, GameState state, String description) {
    applyRoster(g, state);
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
      applyRoster(g, restored);
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

  private List<TournamentPlayer> seedRosterFromGames(String tournamentId) {
    var byName = new LinkedHashMap<String, TournamentPlayer>();
    for (var game : games.findByTournamentIdOrderByTableNumberAscGameNumberAsc(tournamentId)) {
      for (var player : state(game).players) {
        if (player.name == null
            || player.name.isBlank()
            || player.name.equals("Игрок " + player.seat)) continue;
        String normalized = normalizePlayerName(player.name);
        var profile = byName.get(normalized);
        if (profile == null) {
          profile =
              tournamentPlayers
                  .findByTournamentIdAndNormalizedName(tournamentId, normalized)
                  .orElseGet(
                      () -> {
                        var created = new TournamentPlayer();
                        created.id = UUID.randomUUID().toString();
                        created.tournamentId = tournamentId;
                        created.name = player.name.strip();
                        created.normalizedName = normalized;
                        created.createdAt = Instant.now();
                        return created;
                      });
          byName.put(normalized, profile);
        }
        if (profile.photoId == null && player.photo != null && !player.photo.isBlank())
          profile.photoId = player.photo;
      }
    }
    return tournamentPlayers.saveAll(byName.values());
  }

  private void applyRoster(Game game, GameState state) {
    var profiles = tournamentPlayers.findByTournamentIdOrderByCreatedAtAsc(game.tournamentId);
    var byId = new HashMap<String, TournamentPlayer>();
    var byName = new HashMap<String, TournamentPlayer>();
    for (var profile : profiles) {
      byId.put(profile.id, profile);
      byName.put(profile.normalizedName, profile);
    }
    for (var player : state.players) {
      var profile =
          player.tournamentPlayerId == null
              ? byName.get(normalizePlayerName(player.name))
              : byId.get(player.tournamentPlayerId);
      if (profile == null) {
        if (player.tournamentPlayerId != null)
          throw ApiErrors.bad("Игрок не найден в списке этого турнира.");
        continue;
      }
      player.tournamentPlayerId = profile.id;
      player.name = profile.name;
      player.photo = profile.photoId;
    }
  }

  private void propagateProfile(TournamentPlayer profile, String previousName) {
    for (var game : games.findByTournamentIdOrderByTableNumberAscGameNumberAsc(
        profile.tournamentId)) {
      var state = state(game);
      boolean changed = false;
      for (var player : state.players) {
        if (profile.id.equals(player.tournamentPlayerId)
            || normalizePlayerName(player.name).equals(previousName)) {
          player.tournamentPlayerId = profile.id;
          player.name = profile.name;
          player.photo = profile.photoId;
          changed = true;
        }
      }
      if (changed) {
        game.stateJson = json(state);
        game.updatedAt = Instant.now();
        games.saveAndFlush(game);
        event(game, "Профиль игрока турнира обновлён: " + profile.name);
        publisher.publishEvent(new Changed(game.id, false));
      }
    }
  }

  private TournamentPlayerView playerView(TournamentPlayer profile) {
    return new TournamentPlayerView(profile.id, profile.name, profile.photoId);
  }

  private static String cleanPlayerName(String name) {
    String clean = name == null ? "" : name.strip();
    if (clean.isBlank() || clean.length() > 50)
      throw ApiErrors.bad("Имя игрока должно содержать от 1 до 50 символов.");
    return clean;
  }

  private static String normalizePlayerName(String name) {
    return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
  }

  private void validatePhoto(String photoId) {
    if (photoId != null && !photoId.isBlank() && !uploads.existsById(photoId))
      throw ApiErrors.bad("Фотография не найдена.");
  }
}
