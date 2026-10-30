package ru.polemica.studio;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "app.dev=true",
      "app.uploads=./target/test-uploads",
      "server.servlet.session.cookie.secure=false"
    })
@AutoConfigureMockMvc
class StudioIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;

  private MockHttpSession login() throws Exception {
    var u = new StudioUser();
    u.id = UUID.randomUUID().toString();
    u.username = "u" + u.id.replace("-", "");
    u.email = u.username + "@example.test";
    u.passwordHash = passwords.encode("test-password-2026");
    u.verified = true;
    users.save(u);
    var r =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of("login", u.username, "password", "test-password-2026"))))
            .andExpect(status().isOk())
            .andReturn();
    return (MockHttpSession) r.getRequest().getSession(false);
  }

  private JsonNode createGame(MockHttpSession session) throws Exception {
    var t =
        mvc.perform(
                post("/api/v1/tournaments")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Тестовый турнир\"}"))
            .andExpect(status().isOk())
            .andReturn();
    String tid = mapper.readTree(t.getResponse().getContentAsString()).get("id").asText();
    var g =
        mvc.perform(
                post("/api/v1/tournaments/" + tid + "/games")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tableNumber\":1,\"gameNumber\":1,\"demo\":true}"))
            .andExpect(status().isOk())
            .andReturn();
    return mapper.readTree(g.getResponse().getContentAsString());
  }

  private JsonNode update(MockHttpSession session, JsonNode game, GameState state, int expected)
      throws Exception {
    var result =
        mvc.perform(
                put("/api/v1/games/" + game.get("id").asText())
                    .session(session)
                    .with(csrf())
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
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  private String token(MockHttpSession session, String id) throws Exception {
    return mapper
        .readTree(
            mvc.perform(get("/api/v1/games/" + id + "/overlay-link").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("token")
        .asText();
  }

  @Test
  void registrationRequiresVerificationAndDoesNotDisclosePasswords() throws Exception {
    String name = "registration" + UUID.randomUUID().toString().substring(0, 8);
    String body =
        mapper.writeValueAsString(
            Map.of(
                "username",
                name,
                "email",
                name + "@example.test",
                "password",
                "test-password-2026"));
    var result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("passwordHash", "test-password-2026");
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("login", name, "password", "test-password-2026"))))
        .andExpect(status().isForbidden());
    String url =
        mapper.readTree(result.getResponse().getContentAsString()).get("verificationUrl").asText();
    String t = url.substring(url.indexOf("verify=") + 7);
    mvc.perform(
            post("/api/v1/auth/verify")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("token", t))))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("login", name, "password", "test-password-2026"))))
        .andExpect(status().isOk());
  }

  @Test
  void protectsOwnershipAndRequiresCsrf() throws Exception {
    var a = login();
    var b = login();
    var g = createGame(a);
    String id = g.get("id").asText();
    mvc.perform(get("/api/v1/games/" + id)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/games/" + id).session(b)).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/games/" + id + "/overlay-link").session(b))
        .andExpect(status().isNotFound());
    mvc.perform(
            put("/api/v1/games/" + id)
                .session(a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/public/games/" + id).param("token", "wrong"))
        .andExpect(status().isNotFound());
  }

  @Test
  void redactsRolesChecksAndRoleImageConfiguration() throws Exception {
    var session = login();
    var g = createGame(session);
    String id = g.get("id").asText();
    String t = token(session, id);
    var state = mapper.treeToValue(g.get("state"), GameState.class);
    var c = new GameState.Check();
    c.id = UUID.randomUUID().toString();
    c.by = GameState.Role.SHERIFF;
    c.seat = 2;
    c.result = "BLACK";
    c.round = 1;
    c.published = true;
    state.checks.add(c);
    g = update(session, g, state, 200);
    var raw =
        mvc.perform(get("/api/v1/public/games/" + id).param("token", t))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var pub = mapper.readTree(raw).get("state");
    for (var p : pub.get("players")) assertThat(p.get("role").asText()).isEqualTo("UNKNOWN");
    assertThat(pub.get("checks").size()).isZero();
    assertThat(pub.get("theme").get("rolePhotos").size()).isZero();
    assertThat(raw).doesNotContain("overlayToken", "ownerId", "password", "email");
    state.players.get(3).revealRole = true;
    state.showChecks = true;
    g = update(session, g, state, 200);
    pub =
        mapper
            .readTree(
                mvc.perform(get("/api/v1/public/games/" + id).param("token", t))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("state");
    assertThat(pub.get("players").get(3).get("role").asText()).isEqualTo("SHERIFF");
    assertThat(pub.get("players").get(5).get("role").asText()).isEqualTo("UNKNOWN");
    assertThat(pub.get("checks").size()).isEqualTo(1);
  }

  @Test
  void staleUpdatesAreRejectedAndHistoryCanRestore() throws Exception {
    var session = login();
    var original = createGame(session);
    var state = mapper.treeToValue(original.get("state"), GameState.class);
    state.players.getFirst().name = "Новое имя";
    var changed = update(session, original, state, 200);
    assertThat(changed.get("revision").asLong()).isEqualTo(original.get("revision").asLong() + 1);
    update(session, original, state, 409);
    String id = original.get("id").asText();
    var history =
        mapper.readTree(
            mvc.perform(get("/api/v1/games/" + id + "/events").session(session))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(history.size()).isEqualTo(2);
    var restored =
        mapper.readTree(
            mvc.perform(
                    post("/api/v1/games/" + id + "/restore/" + history.get(1).get("id").asText())
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":" + changed.get("revision").asLong() + "}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(restored.get("state").get("players").get(0).get("name").asText()).isEqualTo("Алекс");
  }

  @Test
  void rejectsInvalidSeatsAndRetainsTestamentForEliminatedPlayers() throws Exception {
    var session = login();
    var g = createGame(session);
    var state = mapper.treeToValue(g.get("state"), GameState.class);
    state.players.getFirst().seat = 2;
    update(session, g, state, 400);
    state.players.getFirst().seat = 1;
    state.players.getFirst().status = GameState.Status.SHOT;
    var t = new GameState.Testament();
    t.mode = GameState.TestamentMode.WHEEL;
    t.wheel = 9;
    state.players.getFirst().testament = t;
    var saved = update(session, g, state, 200);
    assertThat(saved.get("state").get("players").get(0).get("testament").get("wheel").asInt())
        .isEqualTo(9);
  }

  @Test
  void rotationRevokesOldPublicLink() throws Exception {
    var session = login();
    var g = createGame(session);
    String id = g.get("id").asText();
    String old = token(session, id);
    mvc.perform(post("/api/v1/games/" + id + "/overlay-link").session(session).with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/public/games/" + id).param("token", old))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/public/games/" + id).param("token", token(session, id)))
        .andExpect(status().isOk());
  }
}
