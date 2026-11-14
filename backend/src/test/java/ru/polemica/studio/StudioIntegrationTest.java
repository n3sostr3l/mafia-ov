package ru.polemica.studio;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "app.uploads=./target/test-uploads"
    })
@AutoConfigureMockMvc
class StudioIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired GameRepository games;

  private JsonNode json(MvcResult result) throws Exception {
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode postJson(String path, Object body, int expected) throws Exception {
    return json(
        mvc.perform(
                post("/api/v1" + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body)))
            .andExpect(status().is(expected))
            .andReturn());
  }

  private JsonNode createGame() throws Exception {
    var t = postJson("/tournaments", Map.of("name", "Общий турнир " + UUID.randomUUID()), 200);
    return postJson(
        "/tournaments/" + t.get("id").asText() + "/games",
        Map.of("tableNumber", 1, "gameNumber", 1, "demo", true),
        200);
  }

  private JsonNode update(JsonNode game, GameState state, int expected) throws Exception {
    return json(
        mvc.perform(
                put("/api/v1/games/" + game.get("id").asText())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of(
                                "revision",
                                game.get("revision").asLong(),
                                "state",
                                state,
                                "description",
                                "Изменение теста"))))
            .andExpect(status().is(expected))
            .andReturn());
  }

  private JsonNode action(JsonNode game, String type, Integer seat, Integer candidate, int expected)
      throws Exception {
    var body = new HashMap<String, Object>();
    body.put("revision", game.get("revision").asLong());
    body.put("type", type);
    if (seat != null) body.put("seat", seat);
    if (candidate != null) body.put("candidate", candidate);
    return postJson("/games/" + game.get("id").asText() + "/actions", body, expected);
  }

  private String token(String id) throws Exception {
    return json(mvc.perform(get("/api/v1/games/" + id + "/overlay-link"))
            .andExpect(status().isOk())
            .andReturn())
        .get("token")
        .asText();
  }

  private JsonNode overlay(String id, String token) throws Exception {
    return json(mvc.perform(get("/api/v1/public/games/" + id).param("token", token))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn())
        .get("state");
  }

  @Test
  void managementApiIsOpenWithoutCookiesCsrfOrAccounts() throws Exception {
    var g = createGame();
    String id = g.get("id").asText();
    mvc.perform(get("/api/v1/games/" + id))
        .andExpect(status().isOk())
        .andExpect(cookie().doesNotExist("JSESSIONID"));
    var items =
        json(mvc.perform(get("/api/v1/tournaments")).andExpect(status().isOk()).andReturn())
            .get("items");
    assertThat(items.toString()).contains(g.get("tournamentId").asText());
    var s = mapper.treeToValue(g.get("state"), GameState.class);
    s.players.getFirst().name = "Без входа";
    g = update(g, s, 200);
    assertThat(g.get("state").get("players").get(0).get("name").asText()).isEqualTo("Без входа");
    mvc.perform(get("/api/v1/auth/me")).andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            options("/api/v1/tournaments")
                .header("Origin", "https://example.test")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "*"));
  }

  @Test
  void automaticChecksAndGlobalVisibilityStillProtectObsProjection() throws Exception {
    var g = createGame();
    var s = mapper.treeToValue(g.get("state"), GameState.class);
    for (var by : List.of(GameState.Role.SHERIFF, GameState.Role.DON)) {
      var c = new GameState.Check();
      c.id = UUID.randomUUID().toString();
      c.by = by;
      c.seat = by == GameState.Role.DON ? 4 : 1;
      c.result = "FORGED";
      c.round = s.round;
      c.published = false;
      s.checks.add(c);
    }
    s.showChecks = true;
    s.players.get(3).revealRole = true;
    g = update(g, s, 200);
    String id = g.get("id").asText(), t = token(id);
    var pub = overlay(id, t);
    assertThat(pub.get("checks").size()).isEqualTo(2);
    assertThat(pub.get("checks").get(0).get("result").asText()).isEqualTo("RED");
    assertThat(pub.get("checks").get(1).get("result").asText()).isEqualTo("SHERIFF");
    for (var c : pub.get("checks")) assertThat(c.get("checker").isNull()).isTrue();
    for (var p : pub.get("players")) assertThat(p.get("role").asText()).isEqualTo("UNKNOWN");
    assertThat(pub.get("theme").get("rolePhotos").size()).isZero();
    assertThat(pub.toString()).doesNotContain("ownerId", "password", "email");
    s = mapper.treeToValue(g.get("state"), GameState.class);
    s.showRoles = true;
    s.showChecks = false;
    g = update(g, s, 200);
    pub = overlay(id, t);
    assertThat(pub.get("checks").size()).isZero();
    for (var p : pub.get("players")) assertThat(p.get("role").asText()).isNotEqualTo("UNKNOWN");
  }

  @Test
  void staleUpdatesAreRejectedAndHistoryCanBeRestoredWithoutSession() throws Exception {
    var g = createGame();
    var s = mapper.treeToValue(g.get("state"), GameState.class);
    s.players.getFirst().name = "Новое имя";
    var changed = update(g, s, 200);
    update(g, s, 409);
    String id = g.get("id").asText();
    var history =
        json(
            mvc.perform(get("/api/v1/games/" + id + "/events"))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(history.size()).isEqualTo(2);
    var restored =
        postJson(
            "/games/" + id + "/restore/" + history.get(1).get("id").asText(),
            Map.of("revision", changed.get("revision").asLong()),
            200);
    assertThat(restored.get("state").get("players").get(0).get("name").asText()).isEqualTo("Алекс");
  }

  @Test
  void actionsPreserveRevisionChecksVotesAndRoundHistory() throws Exception {
    var initial = createGame();
    var g = action(initial, "PHASE_NEXT", null, null, 200);
    action(initial, "PHASE_NEXT", null, null, 409);
    action(g, "VOTE", 3, 2, 400);
    g = action(g, "VOTE", 1, 2, 200);
    g = action(g, "PHASE_NEXT", null, null, 200);
    g = action(g, "PHASE_NEXT", null, null, 200);
    assertThat(g.get("state").get("round").asInt()).isEqualTo(3);
    g = action(g, "PHASE_PREVIOUS", null, null, 200);
    g = action(g, "PHASE_PREVIOUS", null, null, 200);
    assertThat(g.get("state").get("rounds").get("2").get("votes").get("1").asInt()).isEqualTo(2);
    assertThat(g.get("state").get("nominees").size()).isEqualTo(3);
  }

  @Test
  void uploadsCanBeUsedByAnyAnonymousClientButStillValidateFileContents() throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB),
        "png",
        bytes);
    var upload =
        json(
            mvc.perform(
                    multipart("/api/v1/uploads")
                        .file(
                            new MockMultipartFile(
                                "file", "photo.png", "image/png", bytes.toByteArray())))
                .andExpect(status().isOk())
                .andReturn());
    String image = upload.get("id").asText();
    mvc.perform(get("/api/v1/uploads/" + image))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.IMAGE_PNG));
    var g = createGame();
    var s = mapper.treeToValue(g.get("state"), GameState.class);
    s.players.getFirst().photo = image;
    g = update(g, s, 200);
    mvc.perform(
            multipart("/api/v1/uploads")
                .file(
                    new MockMultipartFile(
                        "file", "fake.png", "image/png", "<script>bad</script>".getBytes())))
        .andExpect(status().isBadRequest());
    s.players.getFirst().photo = "not-an-image";
    update(g, s, 400);
  }

  @Test
  void tournamentPlayerSelectionAndProfileEditsApplyAcrossGames() throws Exception {
    var firstGame = createGame();
    var firstState = mapper.treeToValue(firstGame.get("state"), GameState.class);
    firstState.players.getFirst().name = "Игрок турнира";
    firstGame = update(firstGame, firstState, 200);
    String tournamentId = firstGame.get("tournamentId").asText();

    var tournament =
        json(
            mvc.perform(get("/api/v1/tournaments/" + tournamentId))
                .andExpect(status().isOk())
                .andReturn());
    String playerId = tournament.get("players").get(0).get("id").asText();

    var secondGame =
        postJson(
            "/tournaments/" + tournamentId + "/games",
            Map.of(
                "tableNumber",
                2,
                "gameNumber",
                2,
                "demo",
                false,
                "playerIds",
                java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(playerId),
                        java.util.stream.Stream.generate(() -> null).limit(9))
                    .toList()),
            200);
    var photoBytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB),
        "png",
        photoBytes);
    var photo =
        json(
            mvc.perform(
                    multipart("/api/v1/uploads")
                        .file(
                            new MockMultipartFile(
                                "file", "player.png", "image/png", photoBytes.toByteArray())))
                .andExpect(status().isOk())
                .andReturn());

    mvc.perform(
            put("/api/v1/tournaments/" + tournamentId + "/players/" + playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("name", "Новое имя", "photo", photo.get("id").asText()))))
        .andExpect(status().isOk());

    for (String gameId :
        List.of(firstGame, secondGame).stream().map(g -> g.get("id").asText()).toList()) {
      var game =
          json(mvc.perform(get("/api/v1/games/" + gameId)).andExpect(status().isOk()).andReturn());
      assertThat(game.get("state").get("players").get(0).get("name").asText())
          .isEqualTo("Новое имя");
      assertThat(game.get("state").get("players").get(0).get("photo").asText())
          .isEqualTo(photo.get("id").asText());
      assertThat(game.get("state").get("players").get(0).get("tournamentPlayerId").asText())
          .isEqualTo(playerId);
    }

    var duplicate =
        json(
            mvc.perform(
                    post("/api/v1/tournaments/" + tournamentId + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", " новое ИМЯ "))))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(duplicate.get("id").asText()).isEqualTo(playerId);
  }

  @Test
  void uploadAppliesExifOrientationBeforeSavingJpeg() throws Exception {
    var upload =
        json(
            mvc.perform(
                    multipart("/api/v1/uploads")
                        .file(
                            new MockMultipartFile(
                                "file",
                                "portrait.jpg",
                                "image/jpeg",
                                jpegWithExifOrientationSix())))
                .andExpect(status().isOk())
                .andReturn());
    byte[] normalizedBytes =
        mvc.perform(get("/api/v1/uploads/" + upload.get("id").asText()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    var normalized = ImageIO.read(new ByteArrayInputStream(normalizedBytes));

    assertThat(normalized.getWidth()).isEqualTo(72);
    assertThat(normalized.getHeight()).isEqualTo(48);
    var topRight = new Color(normalized.getRGB(64, 8));
    assertThat(topRight.getGreen()).isGreaterThan(topRight.getBlue());
  }

  private static byte[] jpegWithExifOrientationSix() throws Exception {
    var image = new BufferedImage(48, 72, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.BLUE);
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.setColor(Color.GREEN);
    graphics.fillRect(0, 0, 16, 16);
    graphics.dispose();

    var jpeg = new ByteArrayOutputStream();
    ImageIO.write(image, "jpeg", jpeg);
    byte[] encoded = jpeg.toByteArray();
    byte[] exif = {
      0x45, 0x78, 0x69, 0x66, 0, 0, 0x49, 0x49, 0x2a, 0, 8, 0, 0, 0, 1, 0, 0x12, 1, 3, 0, 1, 0, 0,
      0, 6, 0, 0, 0, 0, 0, 0, 0
    };
    var withExif = new ByteArrayOutputStream();
    withExif.write(encoded, 0, 2);
    withExif.write(0xff);
    withExif.write(0xe1);
    int segmentLength = exif.length + 2;
    withExif.write(segmentLength >> 8);
    withExif.write(segmentLength);
    withExif.write(exif);
    withExif.write(encoded, 2, encoded.length - 2);
    return withExif.toByteArray();
  }

  @Test
  void schedulerAdvancesPersistedSpeechWithoutBrowserOrAccount() throws Exception {
    var g = createGame();
    g = action(g, "SPEECH_START", 4, null, 200);
    var entity = games.findById(g.get("id").asText()).orElseThrow();
    var s = mapper.readValue(entity.stateJson, GameState.class);
    s.speechEndsAt = System.currentTimeMillis() - 10;
    entity.speechDeadline = java.time.Instant.ofEpochMilli(s.speechEndsAt);
    entity.stateJson = mapper.writeValueAsString(s);
    games.saveAndFlush(entity);
    long until = System.currentTimeMillis() + 6000;
    Game current;
    do {
      Thread.sleep(50);
      current = games.findById(entity.id).orElseThrow();
    } while (mapper.readValue(current.stateJson, GameState.class).speaker == 4
        && System.currentTimeMillis() < until);
    assertThat(mapper.readValue(current.stateJson, GameState.class).speaker).isEqualTo(5);
    g = json(mvc.perform(get("/api/v1/games/" + entity.id)).andReturn());
    action(g, "SPEECH_PAUSE", null, null, 200);
  }

  @Test
  void overlayLinksStillRotateAndUnknownIdsReturn404() throws Exception {
    var g = createGame();
    String id = g.get("id").asText(), old = token(id);
    postJson("/games/" + id + "/overlay-link", Map.of(), 200);
    mvc.perform(get("/api/v1/public/games/" + id).param("token", old))
        .andExpect(status().isNotFound());
    overlay(id, token(id));
    mvc.perform(get("/api/v1/games/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  void tournamentPasswordGuardsEveryManagementRouteButNotObsAndRotationRevokesKeys()
      throws Exception {
    var g = createGame();
    String id = g.get("id").asText(), tid = g.get("tournamentId").asText(), obs = token(id);
    var key =
        json(mvc.perform(
                    put("/api/v1/tournaments/" + tid + "/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"table-secret\"}"))
                .andExpect(status().isOk())
                .andReturn())
            .get("key")
            .asText();
    for (String path :
        List.of(
            "/tournaments/" + tid,
            "/games/" + id,
            "/games/" + id + "/events",
            "/games/" + id + "/overlay-link",
            "/tournaments/" + tid + "/export"))
      mvc.perform(get("/api/v1" + path))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.tournamentId").value(tid));
    mvc.perform(delete("/api/v1/games/" + id)).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/games/" + id + "/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":0,\"type\":\"PHASE_NEXT\"}"))
        .andExpect(status().isUnauthorized());
    postJson("/tournaments/" + tid + "/unlock", Map.of("password", "wrong"), 401);
    var unlocked =
        postJson("/tournaments/" + tid + "/unlock", Map.of("password", "table-secret"), 200)
            .get("key")
            .asText();
    mvc.perform(get("/api/v1/games/" + id).header("X-Tournament-Key", unlocked))
        .andExpect(status().isOk());
    overlay(id, obs);
    var newer =
        json(mvc.perform(
                    put("/api/v1/tournaments/" + tid + "/password")
                        .header("X-Tournament-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"new-secret\"}"))
                .andExpect(status().isOk())
                .andReturn())
            .get("key")
            .asText();
    mvc.perform(get("/api/v1/games/" + id).header("X-Tournament-Key", unlocked))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/tournaments/" + tid).header("X-Tournament-Key", newer))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("passwordHash"))));
    overlay(id, obs);
  }

  @Test
  void gameLocationsCanBeSwappedAndDeletingResourcesRemovesTheirHistory() throws Exception {
    var a = createGame();
    String tid = a.get("tournamentId").asText(), id = a.get("id").asText();
    var b =
        postJson("/tournaments/" + tid + "/games", Map.of("tableNumber", 2, "gameNumber", 3), 200);
    var move =
        Map.of(
            "revision",
            a.get("revision").asLong(),
            "tableNumber",
            2,
            "gameNumber",
            3,
            "swap",
            false);
    mvc.perform(
            patch("/api/v1/games/" + id + "/location")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(move)))
        .andExpect(status().isConflict());
    move =
        Map.of(
            "revision",
            a.get("revision").asLong(),
            "tableNumber",
            2,
            "gameNumber",
            3,
            "swap",
            true);
    mvc.perform(
            patch("/api/v1/games/" + id + "/location")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(move)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tableNumber").value(2));
    mvc.perform(get("/api/v1/games/" + b.get("id").asText()))
        .andExpect(jsonPath("$.tableNumber").value(1))
        .andExpect(jsonPath("$.gameNumber").value(1));
    mvc.perform(delete("/api/v1/games/" + id)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/games/" + id)).andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/tournaments/" + tid)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/games/" + b.get("id").asText())).andExpect(status().isNotFound());
  }

  @Test
  void archivesRoundTripRealPhotosSeatingAndOptionalRolesWithoutAccessSecrets() throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB),
        "png",
        bytes);
    String photo =
        json(mvc.perform(
                    multipart("/api/v1/uploads")
                        .file(
                            new MockMultipartFile(
                                "file", "photo.png", "image/png", bytes.toByteArray())))
                .andReturn())
            .get("id")
            .asText();
    var g = createGame();
    String tid = g.get("tournamentId").asText();
    postJson("/tournaments/" + tid + "/players", Map.of("name", "Алекс", "photo", photo), 200);
    byte[] archive =
        mvc.perform(
                get("/api/v1/tournaments/" + tid + "/export")
                    .param("roles", "true")
                    .param("progress", "true")
                    .param("theme", "true"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/zip"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    var imported =
        json(
            mvc.perform(
                    multipart("/api/v1/tournaments/import")
                        .file(
                            new MockMultipartFile(
                                "file", "tournament.zip", "application/zip", archive))
                        .param("roles", "true")
                        .param("progress", "true")
                        .param("theme", "true"))
                .andExpect(status().isOk())
                .andReturn());
    String newId = imported.get("id").asText();
    assertThat(newId).isNotEqualTo(tid);
    var tournament =
        json(
            mvc.perform(get("/api/v1/tournaments/" + newId))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(tournament.get("tournament").get("passwordProtected").asBoolean()).isFalse();
    var restored =
        json(
            mvc.perform(get("/api/v1/games/" + tournament.get("games").get(0).get("id").asText()))
                .andReturn());
    assertThat(restored.get("state").get("players").get(1).get("role").asText()).isEqualTo("MAFIA");
    String newPhoto = restored.get("state").get("players").get(0).get("photo").asText();
    assertThat(newPhoto).isNotEqualTo(photo);
    mvc.perform(get("/api/v1/uploads/" + newPhoto))
        .andExpect(status().isOk())
        .andExpect(content().bytes(bytes.toByteArray()));
    byte[] minimal =
        mvc.perform(get("/api/v1/tournaments/" + tid + "/export").param("photos", "false"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    var minId =
        json(mvc.perform(
                    multipart("/api/v1/tournaments/import")
                        .file(
                            new MockMultipartFile(
                                "file", "minimal.zip", "application/zip", minimal))
                        .param("photos", "false"))
                .andExpect(status().isOk())
                .andReturn())
            .get("id")
            .asText();
    var minT = json(mvc.perform(get("/api/v1/tournaments/" + minId)).andReturn());
    var minG =
        json(
            mvc.perform(get("/api/v1/games/" + minT.get("games").get(0).get("id").asText()))
                .andReturn());
    for (var p : minG.get("state").get("players")) {
      assertThat(p.get("role").asText()).isEqualTo("CIVILIAN");
      assertThat(p.get("photo").isNull()).isTrue();
    }
    assertThat(minG.get("state").get("phase").asText()).isEqualTo("PREPARATION");
  }

  @Test
  void invalidArchivesAndDuplicatedSeatingAreRejected() throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
      zip.putNextEntry(new java.util.zip.ZipEntry("../outside.png"));
      zip.write(new byte[] {1});
      zip.closeEntry();
    }
    mvc.perform(
            multipart("/api/v1/tournaments/import")
                .file(
                    new MockMultipartFile(
                        "file", "bad.zip", "application/zip", bytes.toByteArray())))
        .andExpect(status().isBadRequest());
    var g = createGame();
    var tid = g.get("tournamentId").asText();
    var profile = postJson("/tournaments/" + tid + "/players", Map.of("name", "Один"), 200);
    String pid = profile.get("id").asText();
    var s = mapper.treeToValue(g.get("state"), GameState.class);
    s.players.get(0).tournamentPlayerId = pid;
    s.players.get(1).tournamentPlayerId = pid;
    update(g, s, 400);
  }

  @Test
  void protectedTournamentIsCreatedAtomicallyAndInvalidPasswordCreatesNothing() throws Exception {
    var t =
        postJson(
            "/tournaments",
            Map.of("name", "Защищённый с создания", "password", "secret-test"),
            200);
    assertThat(t.get("passwordProtected").asBoolean()).isTrue();
    mvc.perform(get("/api/v1/tournaments/" + t.get("id").asText()))
        .andExpect(status().isUnauthorized());
    long before = json(mvc.perform(get("/api/v1/tournaments")).andReturn()).get("total").asLong();
    postJson("/tournaments", Map.of("name", "Не должен сохраниться", "password", "x"), 400);
    assertThat(json(mvc.perform(get("/api/v1/tournaments")).andReturn()).get("total").asLong())
        .isEqualTo(before);
  }

  @Test
  void serverResumesExpiredShootoutAndStartsRunoffWithoutAnOpenBrowser() throws Exception {
    var g = createGame();
    g = action(g, "PHASE_NEXT", null, null, 200);
    g = action(g, "VOTE", 1, 2, 200);
    g = action(g, "VOTE", 4, 6, 200);
    g = action(g, "VOTE_FINISH", null, null, 200);
    assertThat(g.get("state").get("rounds").get("2").get("ballotStage").asText())
        .isEqualTo("SHOOTOUT");
    var entity = games.findById(g.get("id").asText()).orElseThrow();
    var s = mapper.readValue(entity.stateJson, GameState.class);
    s.speechEndsAt = System.currentTimeMillis() - 65000;
    entity.speechDeadline = java.time.Instant.ofEpochMilli(s.speechEndsAt);
    entity.stateJson = mapper.writeValueAsString(s);
    games.saveAndFlush(entity);
    long until = System.currentTimeMillis() + 6000;
    do {
      Thread.sleep(50);
      entity = games.findById(entity.id).orElseThrow();
      s = mapper.readValue(entity.stateJson, GameState.class);
    } while (!"REVOTE".equals(s.rounds.get(2).ballotStage) && System.currentTimeMillis() < until);
    assertThat(s.rounds.get(2).ballotStage).isEqualTo("REVOTE");
    assertThat(s.rounds.get(2).initialVotes).hasSize(2);
    assertThat(s.rounds.get(2).votes).isEmpty();
    assertThat(s.speechEndsAt).isNull();
  }
}
