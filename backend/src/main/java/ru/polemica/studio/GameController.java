package ru.polemica.studio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class GameController {
  private final GameService service;

  GameController(GameService service) {
    this.service = service;
  }

  record TournamentBody(
      @NotBlank @Size(max = 150) String name, @Size(max = 100) String externalId) {}

  record GameBody(
      @Min(1) @Max(100) int tableNumber,
      @Min(1) @Max(10000) int gameNumber,
      boolean demo,
      @Size(min = 10, max = 10) List<String> playerIds) {}

  record TournamentPlayerBody(
      @NotBlank @Size(max = 50) String name, @Size(max = 36) String photo) {}

  record UpdateBody(
      @Min(0) long revision, @NotNull GameState state, @Size(max = 200) String description) {}

  record ActionBody(
      @Min(0) long revision, @NotBlank String type, Integer seat, Integer candidate) {}

  @PostMapping("/games/{id}/actions")
  GameService.GameView action(@PathVariable String id, @Valid @RequestBody ActionBody body) {
    return service.action(id, body.revision(), body.type(), body.seat(), body.candidate());
  }

  record TournamentView(String id, String name, String externalId, Instant createdAt) {}

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "ok");
  }

  @GetMapping("/tournaments")
  Map<String, Object> tournaments(
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "LOCAL") String source) {
    if (source.equals("POLEMICA"))
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "API Polemica не подключён: необходимы подтверждённый адрес и условия доступа.");
    var items =
        service.tournaments().stream()
            .filter(t ->
                t.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
                    || (t.externalId != null && t.externalId.contains(query)))
            .map(this::tournamentView)
            .toList();
    return Map.of("items", items, "total", items.size());
  }

  @PostMapping("/tournaments")
  TournamentView createTournament(@Valid @RequestBody TournamentBody body) {
    return tournamentView(service.createTournament(body.name(), body.externalId()));
  }

  @GetMapping("/tournaments/{id}")
  Map<String, Object> tournament(@PathVariable String id) {
    var t = service.getTournament(id);
    return Map.of(
        "tournament", tournamentView(t),
        "games", service.summaries(id),
        "players", service.tournamentPlayers(id));
  }

  @PostMapping("/tournaments/{id}/players")
  GameService.TournamentPlayerView createTournamentPlayer(
      @PathVariable String id, @Valid @RequestBody TournamentPlayerBody body) {
    return service.createTournamentPlayer(id, body.name(), body.photo());
  }

  @PutMapping("/tournaments/{id}/players/{playerId}")
  GameService.TournamentPlayerView updateTournamentPlayer(
      @PathVariable String id,
      @PathVariable String playerId,
      @Valid @RequestBody TournamentPlayerBody body) {
    return service.updateTournamentPlayer(id, playerId, body.name(), body.photo());
  }

  @PostMapping("/tournaments/{id}/games")
  GameService.GameView createGame(@PathVariable String id, @Valid @RequestBody GameBody body) {
    return service.create(
        id, body.tableNumber(), body.gameNumber(), body.demo(), body.playerIds());
  }

  @GetMapping("/games/{id}")
  GameService.GameView game(@PathVariable String id) {
    return service.view(service.getGame(id), false);
  }

  @PutMapping("/games/{id}")
  GameService.GameView update(@PathVariable String id, @Valid @RequestBody UpdateBody body) {
    return service.update(id, body.revision(), body.state(), body.description());
  }

  @GetMapping("/games/{id}/events")
  List<Map<String, Object>> events(@PathVariable String id) {
    return service.history(id);
  }

  @PostMapping("/games/{id}/restore/{eventId}")
  GameService.GameView restore(
      @PathVariable String id, @PathVariable String eventId, @RequestBody Map<String, Long> body) {
    return service.restore(id, eventId, body.getOrDefault("revision", -1L));
  }

  @GetMapping("/games/{id}/overlay-link")
  Map<String, String> link(@PathVariable String id) {
    return Map.of("token", service.getGame(id).overlayToken);
  }

  @PostMapping("/games/{id}/overlay-link")
  Map<String, String> rotate(@PathVariable String id) {
    return Map.of("token", service.rotate(id));
  }

  @GetMapping("/public/games/{id}")
  ResponseEntity<GameService.GameView> publicGame(
      @PathVariable String id, @RequestParam String token) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.view(service.publicGame(id, token), true));
  }

  private TournamentView tournamentView(Tournament t) {
    return new TournamentView(t.id, t.name, t.externalId, t.createdAt);
  }
}
