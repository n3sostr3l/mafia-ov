package ru.polemica.studio;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
class GameValidator {
  private final UploadRepository uploads;

  GameValidator(UploadRepository uploads) {
    this.uploads = uploads;
  }

  void validate(GameState s) {
    if (s == null
        || s.players == null
        || s.players.size() != 10
        || s.theme == null
        || s.phase == null) throw ApiErrors.bad("В партии должно быть ровно 10 игроков.");
    Set<Integer> seats = new HashSet<>();
    for (var p : s.players) {
      if (p == null
          || p.seat < 1
          || p.seat > 10
          || !seats.add(p.seat)
          || p.name == null
          || p.name.isBlank()
          || p.name.length() > 50
          || p.role == null
          || p.role == GameState.Role.UNKNOWN
          || p.status == null) throw ApiErrors.bad("Проверьте номера, имена и роли игроков.");
      photo(p.photo);
      photo(p.importedPhoto);
      if (p.testament != null) {
        var t = p.testament;
        if (t.mode == null || t.colors == null || t.colors.size() > 3)
          throw ApiErrors.bad("В ЛХ доступно до трёх цветов или один руль.");
        if (t.mode == GameState.TestamentMode.WHEEL
            && (t.wheel == null || t.wheel < 1 || t.wheel > 10))
          throw ApiErrors.bad("Номер руля: от 1 до 10.");
        Set<Integer> used = new HashSet<>();
        for (var c : t.colors)
          if (c == null
              || c.seat < 1
              || c.seat > 10
              || !used.add(c.seat)
              || !(c.color == GameState.Role.CIVILIAN || c.color == GameState.Role.MAFIA))
            throw ApiErrors.bad("В ЛХ нужны разные места от 1 до 10 и красный или чёрный цвет.");
      }
    }
    if (s.round < 1 || s.round > 100 || (s.speaker != null && !seats.contains(s.speaker)))
      throw ApiErrors.bad("Проверьте раунд и выступающего.");
    if (s.speaker != null
        && s.players.stream()
            .anyMatch(p -> p.seat == s.speaker && p.status != GameState.Status.ALIVE))
      throw ApiErrors.bad(
          "Выбывший игрок не может быть текущим выступающим. Снимите статус перед выступлением.");
    if (s.nominees == null
        || s.nominees.size() > 10
        || new HashSet<>(s.nominees).size() != s.nominees.size()
        || !seats.containsAll(s.nominees)) throw ApiErrors.bad("Некорректный список голосования.");
    if (s.checks == null || s.checks.size() > 200) throw ApiErrors.bad("Слишком много проверок.");
    Set<String> ids = new HashSet<>();
    for (var c : s.checks)
      if (c == null
          || c.id == null
          || c.id.length() > 50
          || !ids.add(c.id)
          || !(c.by == GameState.Role.DON || c.by == GameState.Role.SHERIFF)
          || !seats.contains(c.seat)
          || c.round < 1
          || c.round > 100
          || (c.checker != null && !seats.contains(c.checker)))
        throw ApiErrors.bad("Некорректная проверка Дона или Шерифа.");
    if (s.firstDeparted != null && !seats.contains(s.firstDeparted))
      throw ApiErrors.bad("Проверьте первого ушедшего.");
    if (s.players.stream().filter(p -> p.pu).count() > 1)
      throw ApiErrors.bad("ПУ может быть только один игрок.");
    if (s.rounds == null || s.rounds.size() > 100)
      throw ApiErrors.bad("Некорректная история раундов.");
    for (var entry : s.rounds.entrySet()) {
      var r = entry.getValue();
      if (entry.getKey() < 1
          || entry.getKey() > 100
          || r == null
          || r.nominees == null
          || r.votes == null
          || r.spokenSeats == null
          || r.eligibleVoters == null
          || !seats.containsAll(r.nominees)
          || !seats.containsAll(r.spokenSeats)
          || !seats.containsAll(r.eligibleVoters)
          || !seats.containsAll(r.votes.keySet())
          || !r.nominees.containsAll(r.votes.values())
          || (r.eliminated != null && !seats.contains(r.eliminated)))
        throw ApiErrors.bad("Некорректные данные голосования или речей раунда.");
    }
    var t = s.theme;
    if (t.width < 640
        || t.width > 3840
        || t.height < 360
        || t.height > 2160
        || t.rolePhotos == null) throw ApiErrors.bad("Размер оверлея: 640–3840 × 360–2160.");
    for (String color :
        List.of(
            t.civilian == null ? "" : t.civilian,
            t.mafia == null ? "" : t.mafia,
            t.special == null ? "" : t.special,
            t.sheriff == null ? "#efc34d" : t.sheriff,
            t.don == null ? "" : t.don))
      if (!color.matches("#[0-9a-fA-F]{6}"))
        throw ApiErrors.bad("Цвет должен быть в формате #RRGGBB.");
    t.rolePhotos.forEach((role, image) -> photo(image));
    s.players.sort(Comparator.comparingInt(p -> p.seat));
  }

  private void photo(String id) {
    if (id == null || id.isBlank()) return;
    if (!id.matches("[a-f0-9-]{36}") || !uploads.existsById(id))
      throw ApiErrors.bad("Фотография не найдена.");
  }
}
