package ru.polemica.studio;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
class SecurityConfig {
  @Bean
  PasswordEncoder encoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecurityContextRepository contextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
        .securityContext(c -> c.securityContextRepository(contextRepository()))
        .authorizeHttpRequests(a ->
            a.requestMatchers(
                    "/api/v1/auth/csrf",
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/verify",
                    "/api/v1/integration/status",
                    "/api/v1/health",
                    "/api/v1/public/**",
                    "/ws/**")
                .permitAll()
                .anyRequest()
                .authenticated())
        .exceptionHandling(e ->
            e.authenticationEntryPoint(
                    (req, res, x) -> {
                      res.setStatus(401);
                      res.setContentType("application/json;charset=UTF-8");
                      res.getWriter().write("{\"message\":\"Войдите в аккаунт.\"}");
                    })
                .accessDeniedHandler((req, res, x) -> {
                  res.setStatus(403);
                  res.setContentType("application/json;charset=UTF-8");
                  res.getWriter()
                      .write(
                          "{\"message\":\"Нет доступа или устарела защита сессии. Обновите"
                              + " страницу.\"}");
                }))
        .headers(h ->
            h.contentTypeOptions(c -> {})
                .referrerPolicy(r ->
                    r.policy(
                        org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                            .ReferrerPolicy.NO_REFERRER)))
        .logout(l -> l.disable())
        .build();
  }
}
