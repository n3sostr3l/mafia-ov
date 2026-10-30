package ru.polemica.studio;

import java.util.*;
import org.springframework.web.bind.annotation.*;

/**
 * No undocumented external routes are assumed. Replace with a verified, contract-tested adapter.
 */
interface PolemicaAdapter {
  record Capabilities(
      boolean connected, String status, List<String> confirmedFields, String message) {}

  Capabilities capabilities();
}

@RestController
class IntegrationController implements PolemicaAdapter {
  @Override
  @GetMapping("/api/v1/integration/status")
  public Capabilities capabilities() {
    return new Capabilities(
        false,
        "UNVERIFIED",
        List.of(),
        "Доступ к API Polemica не подтверждён. Все игровые данные вводятся вручную; результаты и"
            + " очки не рассчитываются.");
  }
}
