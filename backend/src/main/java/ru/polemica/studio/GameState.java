package ru.polemica.studio;

import java.util.*;

public class GameState {
  public enum Role {
    CIVILIAN,
    MAFIA,
    DON,
    SHERIFF,
    UNKNOWN
  }

  public enum Status {
    ALIVE,
    SHOT,
    VOTED
  }

  public enum Phase {
    PREPARATION,
    DAY,
    VOTING,
    NIGHT,
    FINISHED
  }

  public enum TestamentMode {
    COLORS,
    WHEEL
  }

  public static class ColorPick {
    public int seat;
    public Role color = Role.CIVILIAN;
  }

  public static class Testament {
    public TestamentMode mode = TestamentMode.COLORS;
    public List<ColorPick> colors = new ArrayList<>();
    public Integer wheel;
  }

  public static class Player {
    public int seat;
    public String name = "Игрок";
    public String tournamentPlayerId;
    public Role role = Role.CIVILIAN;
    public Status status = Status.ALIVE;
    public boolean pu;
    public boolean revealRole;
    public String photo;
    public String importedPhoto;
    public Testament testament;
  }

  public static class Check {
    public String id;
    public Integer checker;
    public Role by;
    public int seat;
    public String result;
    public int round;
    public boolean published;
  }

  public static class Theme {
    public String civilian = "#e94549";
    public String mafia = "#262a32";
    public String special = "#efc34d";
    public String sheriff;
    public String don = "#8b5cc7";
    public boolean transparent;
    public int width = 1920;
    public int height = 1080;
    public boolean useRolePhotos;
    public Map<Role, String> rolePhotos = new HashMap<>();
  }

  public List<Player> players = new ArrayList<>();
  public int schemaVersion;
  public List<Integer> nominees = new ArrayList<>();
  public List<Check> checks = new ArrayList<>();
  public Theme theme = new Theme();
  public Phase phase = Phase.PREPARATION;
  public int round = 1;
  public Integer speaker;
  public boolean showRoles;
  public boolean showChecks;
  public Map<Integer, RoundData> rounds = new TreeMap<>();
  public Integer firstDeparted;
  public String winner;
  public Phase phaseBeforeFinish;
  public Long speechEndsAt;
  public long speechRemainingMs = 60000;

  public static class RoundData {
    public List<Integer> nominees = new ArrayList<>();
    public Map<Integer, Integer> votes = new TreeMap<>();
    public List<Integer> eligibleVoters = new ArrayList<>();
    public List<Integer> spokenSeats = new ArrayList<>();
    public Integer eliminated;
  }

  public static GameState initial(boolean demo) {
    var state = new GameState();
    state.schemaVersion = 2;
    String[] names = {
      "Алекс", "Лис", "Вега", "Север", "Мира", "Космос", "Рэй", "Феникс", "Тень", "Ника"
    };
    for (int i = 1; i <= 10; i++) {
      var p = new Player();
      p.seat = i;
      p.name = demo ? names[i - 1] : "Игрок " + i;
      state.players.add(p);
    }
    if (demo) {
      state.phase = Phase.DAY;
      state.round = 2;
      state.speaker = 4;
      state.players.get(1).role = Role.MAFIA;
      state.players.get(5).role = Role.DON;
      state.players.get(7).role = Role.MAFIA;
      state.players.get(3).role = Role.SHERIFF;
      state.players.get(2).status = Status.SHOT;
      state.firstDeparted = 3;
      state.nominees.addAll(List.of(2, 6, 9));
      var t = new Testament();
      for (int seat : List.of(2, 6, 9)) {
        var c = new ColorPick();
        c.seat = seat;
        c.color = Role.MAFIA;
        t.colors.add(c);
      }
      state.players.get(2).testament = t;
    }
    return state;
  }
}
