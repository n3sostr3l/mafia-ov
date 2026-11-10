package ru.polemica.studio;

import java.security.SecureRandom;
import java.util.Base64;

final class OverlayTokens {
  private OverlayTokens() {}

  static String randomToken() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
