package ru.polemica.studio;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
class SpeechScheduler {
  private static final Logger log = LoggerFactory.getLogger(SpeechScheduler.class);
  private final GameRepository games;
  private final GameService service;

  SpeechScheduler(GameRepository games, GameService service) {
    this.games = games;
    this.service = service;
  }

  @Scheduled(fixedDelay = 250)
  public void tick() {
    for (var game : games.findTop100BySpeechDeadlineLessThanEqual(Instant.now())) {
      try {
        service.advanceDue(game.id, game.speechDeadline);
      } catch (Exception e) {
        log.error("Could not advance speech for game {}", game.id, e);
      }
    }
  }
}
