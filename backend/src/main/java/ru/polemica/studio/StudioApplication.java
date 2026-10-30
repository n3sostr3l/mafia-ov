package ru.polemica.studio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    exclude =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class)
public class StudioApplication {
  public static void main(String[] args) {
    SpringApplication.run(StudioApplication.class, args);
  }
}
