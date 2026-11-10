package ru.polemica.studio;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
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
}
