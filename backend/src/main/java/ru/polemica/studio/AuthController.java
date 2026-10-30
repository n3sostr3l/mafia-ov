package ru.polemica.studio;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final SecurityContextRepository contexts;
  private final JavaMailSender mail;
  private final boolean dev;
  private final String publicUrl;
  private final String from;
  private final Map<String, List<Long>> attempts = new ConcurrentHashMap<>();

  AuthController(
      UserRepository users,
      PasswordEncoder encoder,
      SecurityContextRepository contexts,
      JavaMailSender mail,
      @Value("${app.dev}") boolean dev,
      @Value("${app.public-url}") String publicUrl,
      @Value("${app.mail-from}") String from) {
    this.users = users;
    this.encoder = encoder;
    this.contexts = contexts;
    this.mail = mail;
    this.dev = dev;
    this.publicUrl = publicUrl;
    this.from = from;
  }

  record Register(
      @Pattern(regexp = "[a-zA-Z0-9_]{3,40}") @NotBlank String username,
      @Email @NotBlank @Size(max = 254) String email,
      @Size(min = 10, max = 72) @NotBlank String password) {}

  record Login(
      @NotBlank @Size(max = 254) String login, @NotBlank @Size(max = 72) String password) {}

  @GetMapping("/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "header", token.getHeaderName());
  }

  @GetMapping("/me")
  Map<String, String> me(Authentication auth) {
    var u = users.findById(auth.getName()).orElseThrow(ApiErrors::missing);
    return Map.of("id", u.id, "username", u.username, "email", u.email);
  }

  @PostMapping("/register")
  @Transactional
  Map<String, Object> register(@Valid @RequestBody Register body, HttpServletRequest req) {
    limit(req);
    String email = body.email().strip().toLowerCase(Locale.ROOT);
    String username = body.username().toLowerCase(Locale.ROOT);
    if (users.findByEmailOrUsername(email, username).isPresent())
      throw ApiErrors.bad("Такой логин или почта уже зарегистрированы.");
    if (body.password().getBytes(StandardCharsets.UTF_8).length > 72)
      throw ApiErrors.bad("Пароль слишком длинный: максимум 72 байта UTF-8.");
    var u = new StudioUser();
    u.id = UUID.randomUUID().toString();
    u.username = username;
    u.email = email;
    u.passwordHash = encoder.encode(body.password());
    String token = randomToken();
    u.verifyHash = hash(token);
    u.verifyExpires = Instant.now().plusSeconds(86400);
    users.save(u);
    String link = publicUrl + "/?verify=" + token;
    if (dev)
      return Map.of(
          "message", "Локальный режим: подтвердите почту по ссылке ниже.", "verificationUrl", link);
    var msg = new SimpleMailMessage();
    msg.setFrom(from);
    msg.setTo(email);
    msg.setSubject("Polemica Studio: подтверждение почты");
    msg.setText("Подтвердите почту в течение 24 часов: " + link);
    try {
      mail.send(msg);
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Не удалось отправить письмо. Проверьте SMTP и повторите регистрацию.");
    }
    return Map.of("message", "Письмо отправлено. Подтвердите почту, чтобы войти.");
  }

  @PostMapping("/verify")
  @Transactional
  Map<String, String> verify(@RequestBody Map<String, String> body) {
    var token = body.getOrDefault("token", "");
    var u =
        users
            .findByVerifyHash(hash(token))
            .orElseThrow(() -> ApiErrors.bad("Ссылка подтверждения недействительна."));
    if (u.verifyExpires == null || u.verifyExpires.isBefore(Instant.now()))
      throw ApiErrors.bad("Ссылка подтверждения истекла.");
    u.verified = true;
    u.verifyHash = null;
    u.verifyExpires = null;
    users.save(u);
    return Map.of("message", "Почта подтверждена. Теперь можно войти.");
  }

  @PostMapping("/login")
  Map<String, String> login(
      @Valid @RequestBody Login body, HttpServletRequest req, HttpServletResponse res) {
    limit(req);
    if (body.password().getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль.");
    String login = body.login().strip().toLowerCase(Locale.ROOT);
    var u = users.findByEmailOrUsername(login, login).orElse(null);
    if (u == null || !encoder.matches(body.password(), u.passwordHash))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль.");
    if (!u.verified)
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Сначала подтвердите электронную почту.");
    if (req.getSession(false) != null) req.changeSessionId();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            u.id, null, List.of(new SimpleGrantedAuthority("ROLE_JUDGE"))));
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, req, res);
    return Map.of("id", u.id, "username", u.username, "email", u.email);
  }

  @PostMapping("/logout")
  Map<String, String> logout(HttpServletRequest req) {
    var s = req.getSession(false);
    if (s != null) s.invalidate();
    SecurityContextHolder.clearContext();
    return Map.of("message", "Вы вышли из аккаунта.");
  }

  private synchronized void limit(HttpServletRequest req) {
    long now = System.currentTimeMillis();
    attempts
        .entrySet()
        .removeIf(e -> e.getValue().isEmpty() || e.getValue().getLast() < now - 60000);
    var list = attempts.computeIfAbsent(req.getRemoteAddr(), x -> new ArrayList<>());
    list.removeIf(t -> t < now - 60000);
    if (list.size() >= 20)
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Слишком много попыток. Повторите через минуту.");
    list.add(now);
  }

  static String randomToken() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
