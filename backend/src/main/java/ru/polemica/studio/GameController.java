package ru.polemica.studio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
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
      @Min(1) @Max(100) int tableNumber, @Min(1) @Max(10000) int gameNumber, boolean demo) {}

  record UpdateBody(
      @Min(0) long revision, @NotNull GameState state, @Size(max = 200) String description) {}

  record ActionBody(
      @Min(0) long revision, @NotBlank String type, Integer seat, Integer candidate) {}

  @PostMapping("/games/{id}/actions")
  GameService.GameView action(
      Authentication auth, @PathVariable String id, @Valid @RequestBody ActionBody body) {
    return service.action(
        id, auth.getName(), body.revision(), body.type(), body.seat(), body.candidate());
  }

  record TournamentView(String id, String name, String externalId, Instant createdAt) {}

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "ok");
  }

  @GetMapping("/tournaments")
  Map<String, Object> tournaments(
      Authentication auth,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "LOCAL") String source) {
    if (source.equals("POLEMICA"))
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "API Polemica не подключён: необходимы подтверждённый адрес и условия доступа.");
    var items =
        service.tournamentsFor(auth.getName()).stream()
            .filter(t ->
                t.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
                    || (t.externalId != null && t.externalId.contains(query)))
            .map(this::tournamentView)
            .toList();
    return Map.of("items", items, "total", items.size());
  }

  @PostMapping("/tournaments")
  TournamentView createTournament(Authentication auth, @Valid @RequestBody TournamentBody body) {
    return tournamentView(service.createTournament(auth.getName(), body.name(), body.externalId()));
  }

  @GetMapping("/tournaments/{id}")
  Map<String, Object> tournament(Authentication auth, @PathVariable String id) {
    var t = service.ownedTournament(id, auth.getName());
    return Map.of("tournament", tournamentView(t), "games", service.summaries(id, auth.getName()));
  }

  @PostMapping("/tournaments/{id}/games")
  GameService.GameView createGame(
      Authentication auth, @PathVariable String id, @Valid @RequestBody GameBody body) {
    return service.create(id, auth.getName(), body.tableNumber(), body.gameNumber(), body.demo());
  }

  @GetMapping("/games/{id}")
  GameService.GameView game(Authentication auth, @PathVariable String id) {
    return service.view(service.owned(id, auth.getName()), false);
  }

  @PutMapping("/games/{id}")
  GameService.GameView update(
      Authentication auth, @PathVariable String id, @Valid @RequestBody UpdateBody body) {
    return service.update(id, auth.getName(), body.revision(), body.state(), body.description());
  }

  @GetMapping("/games/{id}/events")
  List<Map<String, Object>> events(Authentication auth, @PathVariable String id) {
    return service.history(id, auth.getName());
  }

  @PostMapping("/games/{id}/restore/{eventId}")
  GameService.GameView restore(
      Authentication auth,
      @PathVariable String id,
      @PathVariable String eventId,
      @RequestBody Map<String, Long> body) {
    return service.restore(id, auth.getName(), eventId, body.getOrDefault("revision", -1L));
  }

  @GetMapping("/games/{id}/overlay-link")
  Map<String, String> link(Authentication auth, @PathVariable String id) {
    return Map.of("token", service.owned(id, auth.getName()).overlayToken);
  }

  @PostMapping("/games/{id}/overlay-link")
  Map<String, String> rotate(Authentication auth, @PathVariable String id) {
    return Map.of("token", service.rotate(id, auth.getName()));
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
