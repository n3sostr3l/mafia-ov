package ru.polemica.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Service
class TournamentArchive {
  record Options(boolean seating, boolean photos, boolean roles, boolean progress, boolean theme) {}

  record Profile(String id, String name, String photo) {}

  record Match(int tableNumber, int gameNumber, GameState state) {}

  record Manifest(
      String format,
      int version,
      String name,
      String externalId,
      List<Profile> players,
      List<Match> games,
      Options included) {}

  private final GameService service;
  private final GameRepository games;
  private final TournamentPlayerRepository players;
  private final ObjectMapper mapper;
  private final ImageController images;
  private final Path root;
  private final GameRules rules;
  private final GameValidator validator;

  TournamentArchive(
      GameService service,
      GameRepository games,
      TournamentPlayerRepository players,
      ObjectMapper mapper,
      ImageController images,
      GameRules rules,
      GameValidator validator,
      @Value("${app.uploads}") String root) {
    this.service = service;
    this.games = games;
    this.players = players;
    this.mapper = mapper;
    this.images = images;
    this.root = Path.of(root);
    this.rules = rules;
    this.validator = validator;
  }

  byte[] export(String id, Options options) throws IOException {
    var t = service.getTournament(id);
    var profiles =
        service.tournamentPlayers(id).stream()
            .map(p -> new Profile(p.id(), p.name(), options.photos ? p.photo() : null))
            .toList();
    var matches = new ArrayList<Match>();
    var photoIds = new HashSet<String>();
    profiles.forEach(p -> {
      if (p.photo != null) photoIds.add(p.photo);
    });
    if (options.seating)
      for (var g : games.findByTournamentIdOrderByTableNumberAscGameNumberAsc(id)) {
        var s = service.view(g, false).state();
        s = select(s, options);
        collectPhotos(s, photoIds);
        matches.add(new Match(g.tableNumber, g.gameNumber, s));
      }
    var manifest =
        new Manifest("polemica-studio", 1, t.name, t.externalId, profiles, matches, options);
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("tournament.json"));
      zip.write(mapper.writeValueAsBytes(manifest));
      zip.closeEntry();
      for (String photo : photoIds) {
        if (!photo.matches("[a-f0-9-]{36}")) throw ApiErrors.bad("Некорректная фотография.");
        zip.putNextEntry(new ZipEntry("photos/" + photo + ".png"));
        Files.copy(root.resolve(photo + ".png"), zip);
        zip.closeEntry();
      }
    }
    if (bytes.size() > 50 * 1024 * 1024)
      throw ApiErrors.bad("Архив превышает 50 МБ. Экспортируйте без фотографий.");
    return bytes.toByteArray();
  }

  @Transactional
  Tournament importArchive(MultipartFile file, Options options) throws IOException {
    var entries = new HashMap<String, byte[]>();
    long total = 0;
    try (var zip = new ZipInputStream(file.getInputStream())) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) continue;
        if (entries.size() >= 1001
            || !(entry.getName().equals("tournament.json")
                || entry.getName().matches("photos/[a-f0-9-]{36}\\.png")))
          throw ApiErrors.bad("Недопустимые файлы в архиве.");
        byte[] bytes = zip.readNBytes(10 * 1024 * 1024 + 1);
        total += bytes.length;
        if (bytes.length > 10 * 1024 * 1024
            || total > 100 * 1024 * 1024
            || entries.put(entry.getName(), bytes) != null)
          throw ApiErrors.bad("Архив слишком большой или содержит повторяющиеся файлы.");
      }
    } catch (ZipException e) {
      throw ApiErrors.bad("Выберите ZIP-архив Polemica Studio.");
    }
    if (!entries.containsKey("tournament.json"))
      throw ApiErrors.bad("В архиве нет tournament.json.");
    Manifest m;
    try {
      m = mapper.readValue(entries.get("tournament.json"), Manifest.class);
    } catch (Exception e) {
      throw ApiErrors.bad("Не удалось прочитать описание турнира.");
    }
    if (!"polemica-studio".equals(m.format)
        || m.version != 1
        || m.name == null
        || m.name.isBlank()
        || m.name.length() > 150
        || m.players == null
        || m.players.size() > 500
        || m.games == null
        || m.games.size() > 500)
      throw ApiErrors.bad("Неподдерживаемый или некорректный архив турнира.");
    var photoMap = new HashMap<String, String>();
    var referenced = new HashSet<String>();
    if (options.photos) {
      m.players.forEach(p -> {
        if (p.photo != null) referenced.add(p.photo);
      });
      for (var g : m.games)
        if (options.seating && g.state != null) collectPhotos(select(g.state, options), referenced);
      for (String old : referenced) {
        byte[] bytes = entries.get("photos/" + old + ".png");
        if (bytes == null) throw ApiErrors.bad("В архиве отсутствует фотография: " + old);
        photoMap.put(old, images.upload(new ArchiveImage(bytes)).get("id"));
      }
    }
    var t =
        service.createTournament(
            m.name + (m.name.length() <= 141 ? " (импорт)" : ""), m.externalId);
    var profileMap = new HashMap<String, String>();
    for (var p : m.players) {
      if (p.id == null || profileMap.containsKey(p.id))
        throw ApiErrors.bad("Повторяющийся игрок в архиве.");
      profileMap.put(
          p.id, service.createTournamentPlayer(t.id, p.name, photoMap.get(p.photo)).id());
    }
    if (options.seating)
      for (var match : m.games) {
        if (match.state == null || match.state.players == null || match.state.players.size() != 10)
          throw ApiErrors.bad("В рассадке должно быть 10 мест.");
        var s = select(match.state, options);
        for (var p : s.players) {
          p.tournamentPlayerId = profileMap.get(p.tournamentPlayerId);
          p.photo = photoMap.get(p.photo);
          p.importedPhoto = photoMap.get(p.importedPhoto);
        }
        s.theme.rolePhotos.replaceAll((role, photo) -> photoMap.get(photo));
        s.theme.rolePhotos.values().removeIf(Objects::isNull);
        rules.prepare(s);
        validator.validate(s);
        service.validateRoster(s);
        var created = service.create(t.id, match.tableNumber, match.gameNumber, false, null);
        var g = games.findById(created.id()).orElseThrow();
        service.saveState(g, s, "Импортирована партия из архива");
      }
    return t;
  }

  private GameState select(GameState source, Options options) throws IOException {
    if (source == null
        || source.players == null
        || source.players.size() != 10
        || source.players.contains(null)
        || source.theme == null
        || source.checks == null
        || source.rounds == null) throw ApiErrors.bad("Некорректное состояние игры в архиве.");
    GameState s = mapper.readValue(mapper.writeValueAsBytes(source), GameState.class);
    if (!options.progress) {
      var fresh = GameState.initial(false);
      fresh.players = s.players;
      fresh.theme = s.theme;
      s = fresh;
      for (var p : s.players) {
        p.status = GameState.Status.ALIVE;
        p.pu = false;
        p.testament = null;
      }
    }
    if (!options.roles) {
      s.players.forEach(p -> p.role = GameState.Role.CIVILIAN);
      s.checks.clear();
      s.showRoles = false;
      s.winner = null;
      s.phaseBeforeFinish = null;
      if (s.phase == GameState.Phase.FINISHED) s.phase = GameState.Phase.NIGHT;
    }
    if (!options.theme) s.theme = new GameState.Theme();
    if (!options.photos) {
      s.players.forEach(p -> {
        p.photo = null;
        p.importedPhoto = null;
      });
      s.theme.rolePhotos.clear();
      s.theme.useRolePhotos = false;
    }
    long duration =
        s.phase == GameState.Phase.VOTING
                && s.rounds.get(s.round) != null
                && "SHOOTOUT".equals(s.rounds.get(s.round).ballotStage)
            ? 30000
            : 60000;
    if (s.speechEndsAt != null)
      s.speechRemainingMs =
          Math.max(1, Math.min(duration, s.speechEndsAt - System.currentTimeMillis()));
    s.speechEndsAt = null;
    return s;
  }

  private void collectPhotos(GameState s, Set<String> ids) {
    for (var p : s.players) {
      if (p.photo != null && !p.photo.isBlank()) ids.add(p.photo);
      if (p.importedPhoto != null && !p.importedPhoto.isBlank()) ids.add(p.importedPhoto);
    }
    ids.addAll(s.theme.rolePhotos.values());
  }

  private record ArchiveImage(byte[] bytes) implements MultipartFile {
    public String getName() {
      return "file";
    }

    public String getOriginalFilename() {
      return "photo.png";
    }

    public String getContentType() {
      return "image/png";
    }

    public boolean isEmpty() {
      return bytes.length == 0;
    }

    public long getSize() {
      return bytes.length;
    }

    public byte[] getBytes() {
      return bytes;
    }

    public InputStream getInputStream() {
      return new ByteArrayInputStream(bytes);
    }

    public void transferTo(File dest) throws IOException {
      Files.write(dest.toPath(), bytes);
    }
  }
}

@RestController
@RequestMapping("/api/v1/tournaments")
class ArchiveController {
  private final TournamentArchive archives;

  ArchiveController(TournamentArchive archives) {
    this.archives = archives;
  }

  @GetMapping("/{id}/export")
  ResponseEntity<byte[]> export(
      @PathVariable String id,
      @RequestParam(defaultValue = "true") boolean seating,
      @RequestParam(defaultValue = "true") boolean photos,
      @RequestParam(defaultValue = "false") boolean roles,
      @RequestParam(defaultValue = "false") boolean progress,
      @RequestParam(defaultValue = "false") boolean theme)
      throws IOException {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header("Content-Disposition", "attachment; filename=tournament-" + id + ".zip")
        .cacheControl(CacheControl.noStore())
        .body(
            archives.export(
                id, new TournamentArchive.Options(seating, photos, roles, progress, theme)));
  }

  @PostMapping("/import")
  Map<String, String> importArchive(
      @RequestParam MultipartFile file,
      @RequestParam(defaultValue = "true") boolean seating,
      @RequestParam(defaultValue = "true") boolean photos,
      @RequestParam(defaultValue = "false") boolean roles,
      @RequestParam(defaultValue = "false") boolean progress,
      @RequestParam(defaultValue = "false") boolean theme)
      throws IOException {
    return Map.of(
        "id",
        archives.importArchive(
                file, new TournamentArchive.Options(seating, photos, roles, progress, theme))
            .id);
  }
}
