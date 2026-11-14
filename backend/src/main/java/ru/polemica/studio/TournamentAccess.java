package ru.polemica.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Service
class TournamentAccess {
  private final TournamentRepository tournaments;
  private final GameRepository games;
  private final org.springframework.context.ApplicationEventPublisher publisher;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
  private final Map<String, Grant> grants = new ConcurrentHashMap<>();
  private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

  record Grant(String tournament, String hash, Instant expires) {}

  record Attempts(long minute, int count) {}

  TournamentAccess(
      TournamentRepository tournaments,
      GameRepository games,
      org.springframework.context.ApplicationEventPublisher publisher) {
    this.tournaments = tournaments;
    this.games = games;
    this.publisher = publisher;
  }

  String gameTournament(String id) {
    return games.findById(id).orElseThrow(ApiErrors::missing).tournamentId;
  }

  boolean allowed(String id, String key) {
    var t = tournaments.findById(id).orElseThrow(ApiErrors::missing);
    if (t.passwordHash == null) return true;
    if (key == null) return false;
    var g = grants.get(key);
    return g != null
        && g.tournament.equals(id)
        && g.hash.equals(t.passwordHash)
        && g.expires.isAfter(Instant.now());
  }

  String unlock(String id, String password, String address) {
    long minute = System.currentTimeMillis() / 60000;
    attempts.entrySet().removeIf(e -> e.getValue().minute < minute);
    var count =
        attempts.compute(
            id + ":" + address,
            (k, v) -> new Attempts(minute, v == null || v.minute != minute ? 1 : v.count + 1));
    if (count.count > 20)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
          "Слишком много попыток. Подождите минуту.");
    var t = tournaments.findById(id).orElseThrow(ApiErrors::missing);
    if (t.passwordHash != null
        && (password == null
            || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
            || !passwords.matches(password, t.passwordHash)))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "Неверный пароль турнира.");
    return grant(t);
  }

  @Transactional
  String change(String id, String password) {
    if (password == null
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
        || (!password.isEmpty() && password.length() < 4))
      throw ApiErrors.bad(
          "Пароль слишком короткий или длинный. Используйте от 4 до 72 латинских символов либо"
              + " более короткий пароль на кириллице.");
    var t = tournaments.findById(id).orElseThrow(ApiErrors::missing);
    t.passwordHash = password.isEmpty() ? null : passwords.encode(password);
    tournaments.saveAndFlush(t);
    grants.entrySet().removeIf(e -> e.getValue().tournament.equals(id));
    for (var g : games.findByTournamentIdOrderByTableNumberAscGameNumberAsc(id))
      publisher.publishEvent(new GameService.Changed(g.id, false));
    return grant(t);
  }

  private String grant(Tournament t) {
    grants.entrySet().removeIf(e -> e.getValue().expires.isBefore(Instant.now()));
    String key = OverlayTokens.randomToken();
    grants.put(
        key,
        new Grant(t.id, Objects.toString(t.passwordHash, ""), Instant.now().plusSeconds(43200)));
    return key;
  }
}

@Configuration
class TournamentGuard implements WebMvcConfigurer {
  private final TournamentAccess access;
  private final ObjectMapper mapper;

  TournamentGuard(TournamentAccess access, ObjectMapper mapper) {
    this.access = access;
    this.mapper = mapper;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler)
                  throws Exception {
                if (request.getMethod().equals("OPTIONS")) return true;
                String pattern =
                    Objects.toString(
                        request.getAttribute(
                            org.springframework.web.servlet.HandlerMapping
                                .BEST_MATCHING_PATTERN_ATTRIBUTE),
                        "");
                @SuppressWarnings("unchecked")
                Map<String, String> variables =
                    (Map<String, String>)
                        request.getAttribute(
                            org.springframework.web.servlet.HandlerMapping
                                .URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                String id = null;
                if (pattern.startsWith("/api/v1/tournaments/{id}")) {
                  if (pattern.equals("/api/v1/tournaments/{id}/unlock")
                      && request.getMethod().equals("POST")) return true;
                  id = variables.get("id");
                } else if (pattern.startsWith("/api/v1/games/{id}"))
                  id = access.gameTournament(variables.get("id"));
                if (id == null || access.allowed(id, request.getHeader("X-Tournament-Key")))
                  return true;
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(
                    response.getWriter(),
                    Map.of("message", "Введите пароль турнира.", "tournamentId", id));
                return false;
              }
            })
        .addPathPatterns("/api/v1/**");
  }
}

@RestController
@RequestMapping("/api/v1/tournaments/{id}")
class AccessController {
  private final TournamentAccess access;

  AccessController(TournamentAccess access) {
    this.access = access;
  }

  record Password(String password) {}

  @PostMapping("/unlock")
  Map<String, String> unlock(
      @PathVariable String id, @RequestBody Password body, HttpServletRequest request) {
    return Map.of("key", access.unlock(id, body.password, request.getRemoteAddr()));
  }

  @PutMapping("/password")
  Map<String, String> password(@PathVariable String id, @RequestBody Password body) {
    return Map.of("key", access.change(id, body.password));
  }
}
