package ru.polemica.studio;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PolemicaMemberParsingTest {
  final ObjectMapper mapper = new ObjectMapper();

  @Test
  void readsCompetitionMemberNameFromNestedPlayer() throws Exception {
    var member =
        mapper.readTree(
            """
            {
              "registration": "2026-01-01T00:00:00",
              "status": 1,
              "player": {
                "id": 86666,
                "username": "Ставит чай",
                "avatar": "86666/photo.jpeg"
              }
            }
            """);

    assertThat(IntegrationController.memberName(member)).isEqualTo("Ставит чай");
    assertThat(IntegrationController.memberAvatar(member)).isEqualTo("86666/photo.jpeg");
  }

  @Test
  void readsFlatGameSeatNameAndAvatar() throws Exception {
    var seat =
        mapper.readTree(
            """
            {
              "position": 3,
              "username": "Деким",
              "avatar": "85795/avatar.jpeg",
              "role": 1,
              "player": {"id": 85795, "username": "Деким", "avatar": "85795/avatar.jpeg"}
            }
            """);

    assertThat(IntegrationController.memberName(seat)).isEqualTo("Деким");
    assertThat(IntegrationController.memberAvatar(seat)).isEqualTo("85795/avatar.jpeg");
  }

  @Test
  void ignoresMemberWithoutUsername() throws Exception {
    var member = mapper.readTree("{\"status\": 0, \"player\": {\"id\": 1}}");
    assertThat(IntegrationController.memberName(member)).isBlank();
    assertThat(IntegrationController.memberAvatar(member)).isBlank();
  }
}
