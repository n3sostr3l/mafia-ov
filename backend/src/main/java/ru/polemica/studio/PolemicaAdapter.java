package ru.polemica.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

interface PolemicaAdapter {
  record Capabilities(
      boolean connected, String status, List<String> confirmedFields, String message) {}

  Capabilities capabilities();
}

@RestController
class IntegrationController implements PolemicaAdapter {
  private static final String POLEMICA_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:160.0) Gecko/20100101 Firefox/160.0";
  private final String baseUrl;
  private final String authorization;
  private final String apiToken;
  private final String cookie;
  private final ConcurrentHashMap<String, String> competitionTokens = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> competitionCookies = new ConcurrentHashMap<>();
  private final GameService games;
  private final GameRepository gameRepository;
  private final TournamentPlayerRepository tournamentPlayers;
  private final TournamentRepository tournaments;
  private final UploadRepository uploads;
  private final ObjectMapper mapper;
  private final Path uploadRoot;
  private final HttpClient http;

  IntegrationController(
      GameService games,
      GameRepository gameRepository,
      TournamentPlayerRepository tournamentPlayers,
      TournamentRepository tournaments,
      UploadRepository uploads,
      ObjectMapper mapper,
      @Value("${app.uploads}") String uploadPath,
      @Value("${app.polemica.base-url:https://app.polemicagame.com}") String baseUrl,
      @Value("${app.polemica.authorization:}") String authorization,
      @Value("${app.polemica.api-token:}") String apiToken,
      @Value("${app.polemica.cookie:}") String cookie)
      throws IOException {
    this.games = games;
    this.gameRepository = gameRepository;
    this.tournamentPlayers = tournamentPlayers;
    this.tournaments = tournaments;
    this.uploads = uploads;
    this.mapper = mapper;
    this.uploadRoot = Path.of(uploadPath).toAbsolutePath().normalize();
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.authorization = authorization;
    this.apiToken = apiToken;
    this.cookie = cookie;
    Files.createDirectories(this.uploadRoot);
    this.http = HttpClient.newHttpClient();
  }

  @Override
  @GetMapping("/api/v1/integration/status")
  public Capabilities capabilities() {
    return new Capabilities(
        true,
        "EXTERNAL",
        List.of("competition.members", "competition.games", "competition.game.detail"),
        "Подключение к app.polemicagame.com: импорт соревнований и партиями.");
  }

  public String competitionName(String competitionId) {
    if (competitionId == null || competitionId.isBlank()) return null;
    for (String path : List.of(
        "/v1/competitions/" + competitionId,
        "/v1/competitions/" + competitionId + "/detail")) {
      try {
        JsonNode root = get(path);
        for (String key : List.of(
            "name", "title", "competitionName", "tournamentName", "label")) {
          if (root.has(key) && root.get(key).isTextual()) {
            String name = root.get(key).asText().trim();
            if (!name.isBlank()) return name;
          }
        }
      } catch (ResponseStatusException e) {
        // Continue only through the small set of documented-looking aliases.
      }
    }
    return null;
  }

  record LoginResult(boolean authenticated, String message) {}

  @PostMapping("/api/v1/polemica/login")
  LoginResult login(@RequestBody Map<String, String> body) {
    String competitionId = body.getOrDefault("competitionId", "").trim();
    String login = body.getOrDefault("login", body.getOrDefault("username", "")).trim();
    String password = body.getOrDefault("password", "");
    if (login.isBlank() || password.isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Введите логин и пароль Polemica.");

    try {
      String webBase = baseUrl.replace("app.polemicagame.com", "polemicagame.com");
      var pageRequest = HttpRequest.newBuilder(URI.create(webBase + "/"))
          .timeout(java.time.Duration.ofSeconds(15))
          .header("Accept", "text/html")
          .header("User-Agent", POLEMICA_USER_AGENT)
          .GET()
          .build();
      var page = http.send(pageRequest, HttpResponse.BodyHandlers.ofString());
      if (page.statusCode() != 200)
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не удалось открыть страницу входа Polemica.");

      String csrf = null;
      var csrfPattern = java.util.regex.Pattern.compile(
          "<meta\\s+name=[\"']csrf-token[\"']\\s+content=[\"']([^\"']+)",
          java.util.regex.Pattern.CASE_INSENSITIVE);
      var csrfMatch = csrfPattern.matcher(page.body());
      if (csrfMatch.find()) csrf = csrfMatch.group(1);
      if (csrf == null)
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Polemica не вернула CSRF-токен.");

      List<String> csrfCookies = page.headers().allValues("set-cookie");
      var loginRequestBuilder = HttpRequest.newBuilder(URI.create(webBase + "/auth/login"))
          .timeout(java.time.Duration.ofSeconds(15))
          .header("Accept", "application/json")
          .header("Content-Type", "application/json")
          .header("Origin", webBase)
          .header("Referer", webBase + "/")
          .header("User-Agent", POLEMICA_USER_AGENT)
          .header("X-CSRF-Token", csrf);
      String csrfCookie = cookieHeader(csrfCookies);
      if (!csrfCookie.isBlank()) loginRequestBuilder.header("Cookie", csrfCookie);
      String payload = mapper.writeValueAsString(Map.of("username", login, "password", password));
      var loginResponse = http.send(
          loginRequestBuilder.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
          HttpResponse.BodyHandlers.ofString());

      if (loginResponse.statusCode() == 401 || loginResponse.statusCode() == 403
          || loginResponse.statusCode() == 404) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "Polemica отклонила запрос входа (HTTP "
                + loginResponse.statusCode()
                + "). Проверьте пароль и CSRF-сессию.");
      }
      if (loginResponse.statusCode() != 200)
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY, "Polemica ответила " + loginResponse.statusCode());

      JsonNode root = mapper.readTree(loginResponse.body());
      if (root.has("success") && !root.path("success").asBoolean(false))
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "Polemica отклонила учётные данные (код "
                + root.path("status").asText("не указан")
                + ").");

      String token = null;
      for (String key : List.of(
          "accessToken", "access_token", "token", "jwt", "idToken", "bearerToken")) {
        if (root.has(key) && root.get(key).isTextual()) token = root.get(key).asText();
        if (root.has("data") && root.get("data").isObject()
            && root.get("data").has(key) && root.get("data").get(key).isTextual())
          token = root.get("data").get(key).asText();
      }

      var allCookies = new ArrayList<String>(csrfCookies);
      allCookies.addAll(loginResponse.headers().allValues("set-cookie"));
      String combinedCookie = cookieHeader(allCookies);
      if (token != null) competitionTokens.put(competitionId, token);
      if (!combinedCookie.isBlank()) competitionCookies.put(competitionId, combinedCookie);
      boolean loginAccepted = root.path("success").asBoolean(false) || token != null;
      if (loginAccepted && (token != null || !combinedCookie.isBlank())) {
        get("/v1/competitions/" + competitionId + "/members");
        return new LoginResult(true, "Авторизация Polemica выполнена.");
      }
    } catch (ResponseStatusException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Запрос авторизации прерван.");
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Polemica недоступна.");
    }
    throw new ResponseStatusException(
        HttpStatus.UNAUTHORIZED,
        "Polemica не вернула подтверждение успешного входа.");
  }

  @PostMapping("/api/v1/tournaments/{id}/polemica/import")
  Map<String, Object> importTournament(@PathVariable String id) {
    var tournament =
        tournaments.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (tournament.externalId == null || tournament.externalId.isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У турнира не задан ID Polemica.");

    var members = getArray("/v1/competitions/" + tournament.externalId + "/members");
    var memberProfiles = importMembers(tournament.externalId, tournament.id, members);
    var summaries = getArray("/v1/competitions/" + tournament.externalId + "/games");
    if (members.isEmpty() && summaries.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY,
          "Polemica вернула пустые списки игр и игроков. Проверьте ID соревнования и доступ аккаунта.");

    int imported = 0;
    int skippedGames = 0;

    for (JsonNode summary : iterable(summaries)) {
      int table = integer(summary, 1, "table", "tableNumber");
      int num = integer(summary, 1, "num", "gameNumber");
      long externalId = longValue(summary, "id", "gameId");
      if (externalId == 0) {
        skippedGames++;
        continue;
      }

      var existing = gameRepository
          .findByTournamentIdOrderByTableNumberAscGameNumberAsc(tournament.id).stream()
          .filter(g -> g.tableNumber == table && g.gameNumber == num)
          .findFirst();
      Game game;
      if (existing.isPresent()) {
        game = existing.get();
      } else {
        var created = games.create(tournament.id, table, num, false, null);
        game = gameRepository.findById(created.id()).orElseThrow();
      }

      var detail = get(
          "/v1/competitions/" + tournament.externalId + "/games/" + externalId + "?version=4");
      syncGame(game, tournament.id, detail, memberProfiles);
      imported++;
    }

    return Map.of(
        "imported", imported,
        "members", memberProfiles.byName().size(),
        "gameRows", summaries.size(),
        "memberRows", members.size(),
        "skippedGames", skippedGames);
  }

  @PostMapping("/api/v1/games/{id}/polemica/refresh")
  GameService.GameView refreshGame(@PathVariable String id) {
    var game = games.getGame(id);
    var tournament =
        tournaments.findById(game.tournamentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (tournament.externalId == null || tournament.externalId.isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У турнира не задан ID Polemica.");

    var summaries = getArray("/v1/competitions/" + tournament.externalId + "/games");
    JsonNode summary = null;
    for (JsonNode item : iterable(summaries)) {
      if (integer(item, 1, "table", "tableNumber") == game.tableNumber
          && integer(item, 1, "num", "gameNumber") == game.gameNumber) {
        summary = item;
        break;
      }
    }
    if (summary == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Партия не найдена в Polemica.");

    var detail = get(
        "/v1/competitions/" + tournament.externalId + "/games/"
            + longValue(summary, "id", "gameId")
            + "?version=4");
    var memberProfiles = getMembers(tournament.externalId, tournament.id);
    return syncGame(game, tournament.id, detail, memberProfiles);
  }

  private record MemberProfiles(
      Map<Integer, String> byPosition, Map<String, String> byName) {}

  private MemberProfiles importMembers(String competitionId, String tournamentId, JsonNode members) {
    var byPosition = new LinkedHashMap<Integer, String>();
    var byName = new LinkedHashMap<String, String>();
    for (JsonNode member : iterable(members)) {
      int position = integer(member, 0, "position", "seat");
      String name = memberName(member);
      if (name.isBlank()) continue;
      var profile = tournamentPlayers
          .findByTournamentIdAndNormalizedName(tournamentId, normalize(name));
      String photoId = profile.map(p -> p.photoId).orElse(null);
      String avatar = memberAvatar(member);
      if (!avatar.isBlank()) photoId = downloadAvatar(avatar);
      if (profile.isPresent()) {
        var updated = games.updateTournamentPlayer(tournamentId, profile.get().id, name, photoId);
        byName.put(normalize(name), updated.id());
        if (position >= 1 && position <= 10) byPosition.put(position, updated.id());
      } else {
        var created = games.createTournamentPlayer(tournamentId, name, photoId);
        byName.put(normalize(name), created.id());
        if (position >= 1 && position <= 10) byPosition.put(position, created.id());
      }
    }
    return new MemberProfiles(byPosition, byName);
  }

  private MemberProfiles getMembers(String competitionId, String tournamentId) {
    return importMembers(competitionId, tournamentId, getArray("/v1/competitions/" + competitionId + "/members"));
  }

  private GameService.GameView syncGame(
      Game game, String tournamentId, JsonNode detail, MemberProfiles memberProfiles) {
    var state = games.state(game);
    String previousJson;
    String nextJson;
    try {
      previousJson = mapper.writeValueAsString(state);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    if (state.players.size() != 10) state.players = GameState.initial(false).players;

    var externalPlayers = detail.path("players");
    for (JsonNode player : iterable(externalPlayers)) {
      int position = player.path("position").asInt(0);
      if (position < 1 || position > 10) continue;
      var target = state.players.get(position - 1);
      target.seat = position;
      String name = memberName(player);
      if (!name.isBlank()) target.name = name;
      target.tournamentPlayerId = memberProfiles.byPosition().get(position);
      target.role = externalRole(player.path("role").asInt(2));
      if (target.tournamentPlayerId == null) {
        target.tournamentPlayerId = memberProfiles.byName().get(normalize(target.name));
      }
      if (target.tournamentPlayerId == null) {
        var profile = tournamentPlayers
            .findByTournamentIdAndNormalizedName(tournamentId, normalize(target.name));
        if (profile.isPresent()) target.tournamentPlayerId = profile.get().id;
      }
    }

    int day = detail.path("stage").path("day").asInt(detail.path("phase").asInt(1));
    state.round = Math.max(1, day);
    String stage = text(detail.path("stage"), "type");
    state.phase = switch (stage) {
      case "shooting", "donCheck", "comCheck" -> GameState.Phase.NIGHT;
      default -> GameState.Phase.DAY;
    };
    state.speaker = detail.path("stage").has("player")
        ? detail.path("stage").path("player").asInt()
        : null;

    state.checks = new ArrayList<>();
    for (JsonNode check : iterable(detail.path("checks"))) {
      int role = check.path("role").asInt(-1);
      if (role != 0 && role != 3) continue;
      var c = new GameState.Check();
      c.id = UUID.randomUUID().toString();
      c.by = role == 0 ? GameState.Role.DON : GameState.Role.SHERIFF;
      c.seat = check.path("player").asInt(0);
      c.round = check.path("night").asInt(1);
      c.checker = state.players.stream()
          .filter(p -> p.role == c.by)
          .map(p -> p.seat)
          .findFirst()
          .orElse(null);
      c.result = role == 0
          ? (c.checker != null && state.players.stream()
              .anyMatch(p -> p.seat == c.seat && p.role == GameState.Role.SHERIFF)
              ? "SHERIFF" : "NOT_SHERIFF")
          : (state.players.stream()
              .anyMatch(p -> p.seat == c.seat
                  && (p.role == GameState.Role.MAFIA || p.role == GameState.Role.DON))
              ? "BLACK" : "RED");
      c.published = true;
      state.checks.add(c);
    }

    var round = state.rounds.computeIfAbsent(state.round, k -> new GameState.RoundData());
    round.nominees = new ArrayList<>();
    round.votes = new TreeMap<>();
    round.initialVotes = new TreeMap<>();
    round.revoteVotes = new TreeMap<>();
    round.liftVoters = new ArrayList<>();
    round.ballotStage = "INITIAL";

    Integer highestNum = null;
    for (JsonNode vote : iterable(detail.path("votes"))) {
      int voteDay = vote.path("day").asInt(state.round);
      if (voteDay != state.round) continue;
      if (vote.has("num")) {
        int num = vote.path("num").asInt();
        highestNum = highestNum == null ? num : Math.max(highestNum, num);
      } else {
        int voter = vote.path("voter").asInt(0);
        if (voter > 0) round.liftVoters.add(voter);
      }
    }
    for (JsonNode vote : iterable(detail.path("votes"))) {
      int voteDay = vote.path("day").asInt(state.round);
      if (voteDay != state.round) continue;
      int voter = vote.path("voter").asInt(0);
      int candidate = vote.path("candidate").asInt(0);
      if (candidate > 0) round.nominees.add(candidate);
      if (highestNum != null && vote.path("num").asInt(-1) == highestNum
          && voter > 0 && candidate > 0) round.votes.put(voter, candidate);
    }
    round.nominees = round.nominees.stream().distinct().toList();
    if (highestNum != null && !round.votes.isEmpty()) {
      round.initialVotes = new TreeMap<>(round.votes);
      round.ballotStage = highestNum >= 2 ? "REVOTE" : "SHOOTOUT";
    }
    if (!round.liftVoters.isEmpty()) round.ballotStage = "LIFT";
    state.nominees = new ArrayList<>(round.nominees);

    JsonNode timer = detail.path("timer");
    if (timer.isObject() && timer.has("started")) {
      state.speechRemainingMs = timer.path("duration").asLong(60000);
      state.speechEndsAt = parseInstant(text(timer, "started")) + state.speechRemainingMs;
    } else {
      state.speechEndsAt = null;
    }

    JsonNode result = detail.get("result");
    state.winner = result != null && !result.isNull()
        ? (result.asInt() == 1 ? "CITY" : result.asInt() == 0 ? "MAFIA" : null)
        : null;
    if (state.winner != null) state.phase = GameState.Phase.FINISHED;

    var snapshot = new GameState.PolemicaSnapshot();
    snapshot.stageType = stage;
    snapshot.stageDay = day;
    snapshot.stagePlayer = detail.path("stage").has("player")
        ? detail.path("stage").path("player").asInt()
        : null;
    snapshot.externalResult = result == null || result.isNull() ? null : result.asInt();
    snapshot.externalVersion = detail.path("version").isNumber()
        ? detail.path("version").asInt() : null;
    for (JsonNode shot : iterable(detail.path("shots"))) {
      var s = new GameState.PolemicaShot();
      s.night = shot.path("night").asInt(1);
      s.shooter = shot.path("shooter").asInt(0);
      s.victim = shot.path("victim").asInt(0);
      snapshot.shots.add(s);
    }
    for (JsonNode vote : iterable(detail.path("votes"))) {
      var v = new GameState.PolemicaVote();
      v.day = vote.path("day").asInt(state.round);
      v.num = vote.has("num") ? vote.path("num").asInt() : null;
      v.voter = vote.path("voter").asInt(0);
      v.candidate = vote.path("candidate").asInt(0);
      snapshot.votes.add(v);
    }
    state.polemica = snapshot;

    try {
      nextJson = mapper.writeValueAsString(state);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    if (previousJson.equals(nextJson)) return games.view(game, false);

    game.updatedAt = java.time.Instant.now();
    return games.saveState(game, state, "Обновлено из Polemica");
  }

  private JsonNode getArray(String path) {
    var root = get(path);
    JsonNode array = findArray(root, 0);
    if (array != null) return array;
    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Некорректный ответ Polemica.");
  }

  private static JsonNode findArray(JsonNode node, int depth) {
    if (node == null || depth > 3) return null;
    if (node.isArray()) return node;
    if (!node.isObject()) return null;
    for (String key : List.of("items", "members", "games", "competitions", "data", "result", "rows", "content")) {
      JsonNode child = node.get(key);
      if (child == null) continue;
      if (child.isArray()) return child;
      JsonNode nested = findArray(child, depth + 1);
      if (nested != null) return nested;
    }
    return null;
  }

  private JsonNode get(String path) {
    try {
      var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
          .timeout(java.time.Duration.ofSeconds(15))
          .header("Accept", "application/json");
      String competitionId = competitionIdFromPath(path);
      String competitionToken = competitionId == null ? null : competitionTokens.get(competitionId);
      String competitionCookie = competitionId == null ? null : competitionCookies.get(competitionId);
      if (competitionToken != null) builder.header("Authorization", "Bearer " + competitionToken);
      else if (!authorization.isBlank()) builder.header("Authorization", authorization);
      else if (!apiToken.isBlank()) builder.header("Authorization", "Bearer " + apiToken);
      String effectiveCookie = competitionCookie != null ? competitionCookie : cookie;
      if (!effectiveCookie.isBlank()) builder.header("Cookie", effectiveCookie);
      var request = builder.GET().build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 401)
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "Polemica требует авторизацию. Введите логин и пароль Polemica.");
      if (response.statusCode() != 200)
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY, "Polemica ответила " + response.statusCode());
      return mapper.readTree(response.body());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Запрос к Polemica прерван.");
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Polemica недоступна.");
    }
  }

  private String downloadAvatar(String avatar) {
    try {
      String url = avatar.startsWith("http") ? avatar : baseUrl + (avatar.startsWith("/") ? avatar : "/" + avatar);
      var builder = HttpRequest.newBuilder(URI.create(url))
          .timeout(java.time.Duration.ofSeconds(15))
          .header("Accept", "image/*");
      String cachedToken = competitionTokens.values().stream().findFirst().orElse(null);
      String cachedCookie = competitionCookies.values().stream().findFirst().orElse(null);
      if (cachedToken != null) builder.header("Authorization", "Bearer " + cachedToken);
      else if (!authorization.isBlank()) builder.header("Authorization", authorization);
      else if (!apiToken.isBlank()) builder.header("Authorization", "Bearer " + apiToken);
      String effectiveCookie = cachedCookie != null ? cachedCookie : cookie;
      if (!effectiveCookie.isBlank()) builder.header("Cookie", effectiveCookie);
      var request = builder.GET().build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200 || response.body().length > 5 * 1024 * 1024) return null;
      BufferedImage original;
      try (var in = new ByteArrayInputStream(response.body())) {
        original = ImageIO.read(in);
      }
      if (original == null) return null;
      double scale = Math.min(1, 1024.0 / Math.max(original.getWidth(), original.getHeight()));
      var clean = new BufferedImage(
          Math.max(1, (int) (original.getWidth() * scale)),
          Math.max(1, (int) (original.getHeight() * scale)),
          BufferedImage.TYPE_INT_ARGB);
      Graphics2D graphics = clean.createGraphics();
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.drawImage(original, 0, 0, clean.getWidth(), clean.getHeight(), null);
      graphics.dispose();
      var upload = new Upload();
      upload.id = UUID.randomUUID().toString();
      upload.createdAt = java.time.Instant.now();
      ImageIO.write(clean, "png", uploadRoot.resolve(upload.id + ".png").toFile());
      uploads.save(upload);
      return upload.id;
    } catch (Exception e) {
      return null;
    }
  }

  private static Iterable<JsonNode> iterable(JsonNode node) {
    return node == null || node.isNull() ? List.of() : () -> node.elements();
  }

  private static String text(JsonNode node, String... keys) {
    if (node == null) return "";
    for (String key : keys) {
      if (node.has(key) && !node.get(key).isNull()) return node.get(key).asText("");
    }
    return "";
  }

  /** Competition members nest identity under {@code player}; game seats may also keep top-level fields. */
  static JsonNode memberIdentity(JsonNode member) {
    if (member == null || member.isNull()) return member;
    for (String key : List.of("player", "user")) {
      JsonNode nested = member.get(key);
      if (nested != null && nested.isObject()) return nested;
    }
    return member;
  }

  static String memberName(JsonNode member) {
    String nested = text(memberIdentity(member), "username", "name", "nickname");
    if (!nested.isBlank()) return nested;
    return text(member, "username", "name", "nickname");
  }

  static String memberAvatar(JsonNode member) {
    String nested = text(memberIdentity(member), "avatar", "photo", "avatarUrl");
    if (!nested.isBlank()) return nested;
    return text(member, "avatar", "photo", "avatarUrl");
  }

  private static int integer(JsonNode node, int fallback, String... keys) {
    for (String key : keys) {
      if (node.has(key) && node.get(key).canConvertToInt()) return node.get(key).asInt();
    }
    return fallback;
  }

  private static long longValue(JsonNode node, String... keys) {
    for (String key : keys) {
      if (node.has(key) && node.get(key).canConvertToLong()) return node.get(key).asLong();
      if (node.has(key) && node.get(key).isTextual()) {
        try {
          return Long.parseLong(node.get(key).asText());
        } catch (NumberFormatException ignored) {
          continue;
        }
      }
    }
    return 0;
  }

  private static String normalize(String name) {
    return name.trim().toLowerCase(Locale.ROOT);
  }

  private static String cookieHeader(List<String> setCookies) {
    var cookies = new LinkedHashMap<String, String>();
    for (String setCookie : setCookies) {
      if (setCookie == null || setCookie.isBlank()) continue;
      String pair = setCookie.split(";", 2)[0].trim();
      int separator = pair.indexOf('=');
      if (separator > 0) cookies.put(pair.substring(0, separator), pair.substring(separator + 1));
    }
    return cookies.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(java.util.stream.Collectors.joining("; "));
  }

  private static String competitionIdFromPath(String path) {
    var marker = "/competitions/";
    int start = path.indexOf(marker);
    if (start < 0) return null;
    start += marker.length();
    int end = path.indexOf('/', start);
    return end < 0 ? path.substring(start).replaceAll("[^0-9A-Za-z_-].*$", "") : path.substring(start, end);
  }

  private static GameState.Role externalRole(int role) {
    return switch (role) {
      case 0 -> GameState.Role.DON;
      case 1 -> GameState.Role.MAFIA;
      case 3 -> GameState.Role.SHERIFF;
      default -> GameState.Role.CIVILIAN;
    };
  }

  private static long parseInstant(String text) {
    if (text == null || text.isBlank()) return java.time.Instant.now().toEpochMilli();
    try {
      return java.time.Instant.parse(text).toEpochMilli();
    } catch (Exception e) {
      try {
        return java.time.LocalDateTime.parse(text)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
      } catch (Exception ignored) {
        return java.time.Instant.now().toEpochMilli();
      }
    }
  }
}
