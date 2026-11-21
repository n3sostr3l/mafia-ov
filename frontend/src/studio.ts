import { PlayerPickerComponent, PlayerOption } from "./player-picker";
import { ballotCandidates } from "./voting";
import {
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { ActivatedRoute, Router } from "@angular/router";
import { Api } from "./api";
import { IconComponent } from "./icon";
import { PlayerCardComponent } from "./player-card";
import { OverlayComponent } from "./overlay";
import { SpeechClockComponent } from "./speech-clock";
import {
  checkResult,
  refreshChecks,
  finishGame,
  roundData,
  demoAction,
  nextSpeech,
  expireSpeech,
} from "./gameplay";
import {
  Check,
  Game,
  GameEvent,
  GameSummary,
  Player,
  Role,
  State,
  Tournament,
  TournamentPlayer,
  demoGame,
  phaseLabel,
  photoId,
  project,
  roleLabel,
  roles,
  phases,
  normalizeGame,
  roleColor,
} from "./model";

@Component({
  selector: "app-studio",
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    PlayerCardComponent,
    OverlayComponent,
    SpeechClockComponent,
    PlayerPickerComponent,
  ],
  templateUrl: "./studio.html",
})
export class StudioComponent implements OnInit, OnDestroy {
  private api = inject(Api);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  game = signal<Game>(this.readDemo());
  selected = signal(4);
  page = signal<
    "studio" | "library" | "tournament" | "settings" | "history" | "help"
  >("studio");
  tab = signal<"player" | "testament" | "checks">("player");
  modal = signal<
    | "obs"
    | "newTournament"
    | "newGame"
    | "rotate"
    | "unlock"
    | "access"
    | "transfer"
    | "location"
    | "delete"
    | null
  >(null);
  isOverlay = location.pathname.startsWith("/overlay/");
  overlayReady = signal(false);
  overlayError = signal("");
  overlayToken = "";
  overlayUrl = signal("");
  connected = signal(false);
  saving = signal(false);
  loading = signal(false);
  error = signal("");
  toast = signal("");
  isDemo = computed(() => this.game().id === "demo");
  publicGame = computed(() => project(this.game()));
  player = computed(
    () => this.game().state.players.find((p) => p.seat === this.selected())!,
  );
  alive = computed(
    () => this.game().state.players.filter((p) => p.status === "ALIVE").length,
  );
  roles = roles;
  phases = phases;
  roleLabel = roleLabel;
  phaseLabel = phaseLabel;
  tournaments = signal<Tournament[]>([]);
  tournamentPlayers = signal<TournamentPlayer[]>([]);
  rosterSeats = Array.from({ length: 10 }, (_, i) => i + 1);
  summaries = signal<GameSummary[]>([]);
  chosenTournament = signal<Tournament | null>(null);
  events = signal<GameEvent[]>([]);
  search = "";
  searchSource = "LOCAL";
  newName = "";
  externalId = "";
  newTable = 1;
  newGameNumber = 1;
  newDemo = false;
  newRosterPlayerName = "";
  newGamePlayerIds: (string | null)[] = Array(10).fill(null);
  draftName = "";
  testamentMode: "COLORS" | "WHEEL" = "COLORS";
  testamentSeats: (number | null)[] = [null, null, null];
  testamentColors: Role[] = ["MAFIA", "MAFIA", "MAFIA"];
  wheel = 1;
  checkChecker = 4;
  checkSeat = 1;
  password = "";
  polemicaLogin = "";
  polemicaPassword = "";
  lockedTournament = "";
  draftProfile: string | null = null;
  transferMode: "import" | "export" = "export";
  transferOptions = {
    seating: true,
    photos: true,
    roles: false,
    progress: false,
    theme: false,
  };
  transferFile: File | null = null;
  editingGame: GameSummary | null = null;
  swapLocation = false;
  pendingDelete: {
    kind: "games" | "tournaments";
    id: string;
    name: string;
  } | null = null;
  private lockListener = (event: Event) => {
    this.lockedTournament = (event as CustomEvent).detail.id;
    this.password = "";
    this.polemicaLogin = "";
    this.polemicaPassword = "";
    this.stopLive();
    this.modal.set("unlock");
  };
  checkers = computed(() =>
    this.game().state.players.filter(
      (p) => p.role === "DON" || p.role === "SHERIFF",
    ),
  );
  currentRound = computed(() => roundData(this.game().state));
  roleColor = roleColor;
  private demoTimer?: ReturnType<typeof setInterval>;
  settingsDraft!: State["theme"];
  mobileMenu = signal(false);
  integrationOpen = signal(false);
  private socket?: WebSocket;
  private poll?: ReturnType<typeof setInterval>;
  private toastTimer?: ReturnType<typeof setTimeout>;
  private reconnect?: ReturnType<typeof setTimeout>;
  private destroyed = false;
  private pendingLive?: Game;
  private polemicaPoll?: ReturnType<typeof setInterval>;
  private storageListener = (e: StorageEvent) => {
    if (e.key === "polemica-demo-v1" && this.isDemo()) {
      const g = this.readDemo();
      this.game.set(this.isOverlay ? project(g) : g);
    }
  };
  async ngOnInit() {
    window.addEventListener("storage", this.storageListener);
    window.addEventListener("tournament-locked", this.lockListener);
    this.select(4);
    this.settingsDraft = structuredClone(this.game().state.theme);
    if (this.isOverlay) {
      await this.loadOverlay();
      return;
    }
    this.demoTimer = setInterval(() => {
      const s = this.game().state;
      if (
        this.isDemo() &&
        !this.saving() &&
        s.speechEndsAt !== null &&
        s.speechEndsAt <= Date.now()
      )
        void this.change("Минута завершена", (next) =>
          expireSpeech(next, Date.now()),
        );
    }, 250);
    try {
      const path = location.pathname;
      if (path.startsWith("/games/")) await this.loadGame(path.split("/")[2]);
      else if (path.startsWith("/tournaments/"))
        await this.openTournament(path.split("/")[2], false);
      else if (path !== "/demo") await this.openLibrary(false);
    } catch (e: any) {
      this.error.set(e.message);
    }
  }
  ngOnDestroy() {
    this.destroyed = true;
    this.stopLive();
    clearTimeout(this.toastTimer);
    clearInterval(this.demoTimer);
    window.removeEventListener("storage", this.storageListener);
    window.removeEventListener("tournament-locked", this.lockListener);
  }
  readDemo(): Game {
    try {
      const g = JSON.parse(localStorage.getItem("polemica-demo-v1") || "null");
      if (g?.id === "demo" && g.state?.players?.length === 10)
        return normalizeGame(g);
    } catch {}
    return normalizeGame(demoGame());
  }
  notify(message: string) {
    this.toast.set(message);
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.toast.set(""), 4200);
  }
  async run(task: () => Promise<void>) {
    if (this.saving()) return;
    this.saving.set(true);
    this.error.set("");
    try {
      await task();
    } catch (e: any) {
      this.error.set(e.message);
    } finally {
      this.saving.set(false);
      const pending = this.pendingLive;
      this.pendingLive = undefined;
      if (
        pending?.id === this.game().id &&
        pending.revision > this.game().revision
      )
        this.game.set(pending);
    }
  }
  async change(description: string, mutate: (s: State) => void) {
    await this.run(async () => {
      const current = this.game(),
        next = structuredClone(current);
      mutate(next.state);
      if (this.isDemo()) {
        refreshChecks(next.state);
        roundData(next.state).nominees = [...next.state.nominees];
        roundData(next.state).votes = Object.fromEntries(
          Object.entries(roundData(next.state).votes).filter(([, candidate]) =>
            next.state.nominees.includes(candidate),
          ),
        );
        if (
          roundData(next.state).eliminated !== null &&
          next.state.players[roundData(next.state).eliminated! - 1].status ===
            "ALIVE"
        )
          roundData(next.state).eliminated = null;
        finishGame(next.state);
        next.revision++;
        next.updatedAt = new Date().toISOString();
        this.game.set(next);
        localStorage.setItem("polemica-demo-v1", JSON.stringify(next));
        this.notify(description);
      } else {
        let result: Game;
        try {
          result = await this.api.request<Game>(
            "/games/" + current.id,
            "PUT",
            { revision: current.revision, state: next.state, description },
          );
        } catch (e: any) {
          if (e.status !== 409) throw e;
          const latest = await this.api.request<Game>("/games/" + current.id);
          const rebased = structuredClone(latest);
          mutate(rebased.state);
          result = await this.api.request<Game>(
            "/games/" + latest.id,
            "PUT",
            { revision: latest.revision, state: rebased.state, description },
          );
          this.notify("Изменение применено к актуальной партии.");
        }
        this.game.set(result);
        this.notify("Сохранено: " + description.toLowerCase());
      }
    });
  }
  select(seat: number) {
    this.selected.set(seat);
    const p = this.game().state.players.find((p) => p.seat === seat);
    if (!p) return;
    if (p.role === "DON" || p.role === "SHERIFF") this.checkChecker = p.seat;
    this.draftName = p.name;
    this.draftProfile = p.tournamentPlayerId ?? null;
    const t = p.testament;
    this.testamentMode = t?.mode ?? "COLORS";
    this.wheel = t?.wheel ?? 1;
    this.testamentSeats = [0, 1, 2].map((i) => t?.colors[i]?.seat ?? null);
    this.testamentColors = [0, 1, 2].map((i) => t?.colors[i]?.color ?? "MAFIA");
  }
  async assignRole(role: Role) {
    await this.change("Роль игрока " + this.selected() + " изменена", (s) => {
      s.players[this.selected() - 1].role = role;
    });
  }
  async status(status: Player["status"]) {
    await this.change("Статус игрока " + this.selected() + " изменён", (s) => {
      const p = s.players[this.selected() - 1],
        old = p.status;
      p.status = status;
      if (status !== "ALIVE" && old === "ALIVE" && s.firstDeparted === null)
        s.firstDeparted = p.seat;
      if (status === "ALIVE" && s.firstDeparted === p.seat)
        s.firstDeparted = null;
      if (status !== "ALIVE" && s.speaker === p.seat) {
        if (this.isDemo()) nextSpeech(s, Date.now());
        else s.speaker = null;
      }
    });
  }
  async saveName() {
    if (!this.draftName.trim() || this.draftName.length > 50) {
      this.notify("Имя: от 1 до 50 символов.");
      return;
    }
    if (this.isDemo()) {
      await this.change(
        "Имя игрока изменено",
        (s) => (s.players[this.selected() - 1].name = this.draftName.trim()),
      );
      return;
    }

    await this.run(async () => {
      const existing = this.tournamentPlayers().find(
        (p) =>
          p.name.trim().toLocaleLowerCase() ===
          this.draftName.trim().toLocaleLowerCase(),
      );
      const profile =
        existing ??
        (await this.api.request<TournamentPlayer>(
          "/tournaments/" + this.game().tournamentId + "/players",
          "POST",
          {
            name: this.draftName.trim(),
            photo: this.player().tournamentPlayerId
              ? null
              : this.player().photo,
          },
        ));
      this.setTournamentPlayer(profile);
      const next = await this.api.request<Game>("/games/" + this.game().id);
      this.placeProfile(next.state, profile);
      this.game.set(
        await this.api.request<Game>("/games/" + next.id, "PUT", {
          revision: next.revision,
          state: next.state,
          description: "Состав партии изменён",
        }),
      );
      this.select(this.selected());
    });
  }
  profileOptions(seat?: number): PlayerOption[] {
    return this.tournamentPlayers().map((p) => ({
      ...p,
      disabled:
        !!seat &&
        this.newGamePlayerIds.includes(p.id) &&
        this.newGamePlayerIds[seat - 1] !== p.id,
    }));
  }
  seatOptions(checkersOnly = false): PlayerOption[] {
    return this.game()
      .state.players.filter(
        (p) => !checkersOnly || ["DON", "SHERIFF"].includes(p.role),
      )
      .map((p) => ({
        id: String(p.seat),
        name: p.name + " · № " + p.seat,
        photo: p.photo,
      }));
  }
  number(value: string | null) {
    return value === null ? null : Number(value);
  }
  private placeProfile(s: State, profile: TournamentPlayer) {
    const target = s.players[this.selected() - 1];
    const other = s.players.find(
      (p) => p.tournamentPlayerId === profile.id && p.seat !== target.seat,
    );
    if (other)
      Object.assign(other, {
        name: target.name,
        photo: target.photo,
        importedPhoto: target.importedPhoto,
        tournamentPlayerId: target.tournamentPlayerId,
      });
    Object.assign(target, {
      tournamentPlayerId: profile.id,
      name: profile.name,
      photo: profile.photo,
      importedPhoto: null,
    });
  }
  async assignProfile(id: string | null) {
    const p = this.tournamentPlayers().find((p) => p.id === id);
    if (!p) return;
    await this.change("Рассадка изменена", (s) => this.placeProfile(s, p));
    this.select(this.selected());
  }
  async speak(seat: number | null) {
    if (
      seat !== null &&
      this.game().state.players[seat - 1].status !== "ALIVE"
    ) {
      this.notify("Сначала верните игрока в игру.");
      return;
    }
    await this.action(seat === null ? "SPEECH_NEXT" : "SPEECH_START", seat);
  }
  async nominate(seat: number) {
    if (
      this.game().state.players[seat - 1].status !== "ALIVE" ||
      this.game().state.winner
    )
      return;
    await this.change("Список голосования обновлён", (s) => {
      s.nominees = s.nominees.includes(seat)
        ? s.nominees.filter((n) => n !== seat)
        : [...s.nominees, seat];
    });
  }
  clearNominees = (s: State) => {
    s.nominees = [];
  };
  deleteRolePhoto(role: Role) {
    delete this.settingsDraft.rolePhotos[role];
  }
  async action(type: string, seat?: number | null, candidate?: number | null) {
    if (this.isDemo()) {
      await this.change("Игровое действие сохранено", (s) =>
        demoAction({ ...this.game(), state: s }, type, seat, candidate),
      );
      return;
    }
    await this.run(async () => {
      let game = this.game();
      try {
        game = await this.api.request<Game>(
          "/games/" + game.id + "/actions",
          "POST",
          { revision: game.revision, type, seat, candidate },
        );
      } catch (e: any) {
        if (e.status !== 409) throw e;
        const latest = await this.api.request<Game>("/games/" + game.id);
        game = await this.api.request<Game>(
          "/games/" + latest.id + "/actions",
          "POST",
          { revision: latest.revision, type, seat, candidate },
        );
        this.notify("Действие применено к актуальной партии.");
      }
      this.game.set(game);
    });
  }
  async stepPhase(direction: number) {
    await this.action(direction > 0 ? "PHASE_NEXT" : "PHASE_PREVIOUS");
  }
  async firstDeparted() {
    await this.change(
      "Первый ушедший изменён",
      (s) =>
        (s.firstDeparted =
          s.firstDeparted === this.selected() ? null : this.selected()),
    );
  }
  votesFor(seat: number) {
    return Object.entries(this.currentRound().votes)
      .filter(([, target]) => target === seat)
      .map(([voter]) => Number(voter));
  }
  ballotCandidates() {
    return ballotCandidates(this.game().state);
  }
  ballotStage() {
    return (
      {
        INITIAL: "Голосование",
        SHOOTOUT: "Перестрелка · 30 секунд",
        REVOTE: "Переголосование",
        LIFT: "Кто за подъём всех?",
        COMPLETED: "Голосование завершено",
      } as Record<string, string>
    )[this.currentRound().ballotStage ?? "INITIAL"];
  }
  async vote(voter: number, candidate: number) {
    await this.action(
      "VOTE",
      voter,
      this.currentRound().votes[voter] === candidate ? null : candidate,
    );
  }
  voteDisabled(voter: number, candidate: number) {
    const r = this.currentRound(),
      assigned = r.votes[voter];
    return (
      this.saving() ||
      !r.eligibleVoters.includes(voter) ||
      this.game().state.players[voter - 1].status !== "ALIVE" ||
      (assigned != null && assigned !== candidate)
    );
  }
  liftThreshold() {
    return Math.floor(this.alive() / 2);
  }
  candidateColor(seat: number) {
    const role = this.game().state.players[seat - 1].role;
    return ["DON", "MAFIA"].includes(role)
      ? this.game().state.theme.mafia
      : this.game().state.theme.civilian;
  }
  async toggleVisibility(field: "showRoles" | "showChecks") {
    await this.change(
      "Публичное отображение обновлено",
      (s) => (s[field] = !s[field]),
    );
  }
  async saveTestament() {
    const seats = this.testamentSeats.filter((n): n is number => n !== null);
    if (new Set(seats).size !== seats.length) {
      this.notify("Номера в ЛХ не должны повторяться.");
      return;
    }
    await this.change("ЛХ игрока " + this.selected() + " сохранён", (s) => {
      s.players[this.selected() - 1].testament = {
        mode: this.testamentMode,
        wheel: this.testamentMode === "WHEEL" ? this.wheel : null,
        colors:
          this.testamentMode === "COLORS"
            ? this.testamentSeats.flatMap((seat, i) =>
                seat === null ? [] : [{ seat, color: this.testamentColors[i] }],
              )
            : [],
      };
    });
  }
  async clearTestament() {
    await this.change(
      "ЛХ удалён",
      (s) => (s.players[this.selected() - 1].testament = null),
    );
    this.select(this.selected());
  }
  checker(): Player | undefined {
    return (
      this.checkers().find((p) => p.seat === this.checkChecker) ??
      this.checkers()[0]
    );
  }
  automaticCheck() {
    const p = this.checker();
    return p ? checkResult(this.game().state, p.role, this.checkSeat) : "";
  }
  async addCheck() {
    const checker = this.checker();
    if (!checker) {
      this.notify("Сначала назначьте Дона или Шерифа.");
      return;
    }
    await this.change("Проверка добавлена", (s) => {
      s.checks.push({
        id: crypto.randomUUID(),
        by: checker.role as Check["by"],
        checker: checker.seat,
        seat: this.checkSeat,
        result: checkResult(s, checker.role, this.checkSeat),
        round: s.round,
        published: true,
      });
    });
  }
  async removeCheck(id: string) {
    await this.change(
      "Проверка удалена",
      (s) => (s.checks = s.checks.filter((c) => c.id !== id)),
    );
  }
  checkLabel(check: Check | string) {
    return (
      {
        RED: "Мирный",
        BLACK: "Мафия",
        SHERIFF: "Шериф",
        NOT_SHERIFF: "Не шериф",
      } as Record<string, string>
    )[typeof check === "string" ? check : check.result];
  }
  checkColor(result: string) {
    const t = this.game().state.theme;
    return result === "RED"
      ? t.civilian
      : result === "BLACK"
        ? t.mafia
        : result === "SHERIFF"
          ? t.sheriff
          : "#7d8797";
  }
  photoSource() {
    const p = this.player(),
      s = this.game().state;
    return p.photo
      ? "Загружена судьёй"
      : s.theme.useRolePhotos && s.theme.rolePhotos[p.role]
        ? "Фотография роли"
        : p.importedPhoto
          ? "Polemica"
          : "Изображение по умолчанию";
  }
  async upload(event: Event, role?: Role) {
    const input = event.target as HTMLInputElement,
      file = input.files?.[0],
      seat = this.selected();
    if (!file) return;
    if (this.isDemo()) {
      this.notify("Создайте сохранённую партию для загрузки фотографий.");
      input.value = "";
      return;
    }
    if (
      !["image/png", "image/jpeg"].includes(file.type) ||
      file.size > 5 * 1024 * 1024
    ) {
      this.notify("Выберите JPG или PNG до 5 МБ.");
      input.value = "";
      return;
    }
    try {
      const form = new FormData();
      form.append("file", file);
      const res = await this.api.request<{ id: string }>(
        "/uploads",
        "POST",
        form,
      );
      if (role) {
        this.settingsDraft.rolePhotos[role] = res.id;
        this.notify("Фото роли загружено. Сохраните оформление.");
      } else if (
        this.player().tournamentPlayerId ||
        !this.isDefaultPlayerName(this.player().name, this.player().seat)
      ) {
        const player = this.player();
        const profile = player.tournamentPlayerId
          ? await this.saveTournamentPlayerRequest(
              player.tournamentPlayerId,
              player.name,
              res.id,
            )
          : await this.api.request<TournamentPlayer>(
              "/tournaments/" + this.game().tournamentId + "/players",
              "POST",
              { name: player.name, photo: res.id },
            );
        this.setTournamentPlayer(profile);
        await this.reloadCurrentGame();
        this.notify("Фото игрока сохранено для всего турнира.");
      } else
        await this.change(
          "Фотография игрока " + seat + " обновлена",
          (s) => (s.players[seat - 1].photo = res.id),
        );
    } catch (e: any) {
      this.error.set(e.message);
    } finally {
      input.value = "";
    }
  }
  async removePhoto() {
    const player = this.player();
    if (this.isDemo() || !player.tournamentPlayerId) {
      await this.change(
        "Индивидуальная фотография удалена",
        (s) => (s.players[this.selected() - 1].photo = null),
      );
      return;
    }
    await this.run(async () => {
      const profile = await this.saveTournamentPlayerRequest(
        player.tournamentPlayerId!,
        player.name,
        null,
      );
      this.setTournamentPlayer(profile);
      await this.reloadCurrentGame();
      this.notify("Фото удалено во всех партиях турнира.");
    });
  }
  private async saveTournamentPlayerRequest(
    playerId: string,
    name: string,
    photo: string | null,
  ) {
    return this.api.request<TournamentPlayer>(
      "/tournaments/" +
        (this.chosenTournament()?.id ?? this.game().tournamentId) +
        "/players/" +
        playerId,
      "PUT",
      { name, photo },
    );
  }
  private setTournamentPlayer(player: TournamentPlayer) {
    this.tournamentPlayers.update((players) =>
      players.some((entry) => entry.id === player.id)
        ? players.map((entry) => (entry.id === player.id ? player : entry))
        : [...players, player],
    );
  }
  private async reloadCurrentGame() {
    const game = await this.api.request<Game>("/games/" + this.game().id);
    this.game.set(game);
    this.select(this.selected());
  }
  private isDefaultPlayerName(name: string, seat: number) {
    return name.trim() === "Игрок " + seat;
  }
  async saveSettings() {
    await this.change(
      "Оформление сохранено",
      (s) => (s.theme = structuredClone(this.settingsDraft)),
    );
  }
  async go(page: "studio" | "library" | "settings" | "history" | "help") {
    this.mobileMenu.set(false);
    this.error.set("");
    if (page === "library") {
      await this.openLibrary();
      return;
    }
    this.page.set(page);
    if (page === "settings")
      this.settingsDraft = structuredClone(this.game().state.theme);
    if (page === "history") await this.loadHistory();
  }
  async openLibrary(navigate = true) {
    this.loading.set(true);
    this.page.set("library");
    try {
      await this.searchTournaments();
      if (navigate) await this.router.navigateByUrl("/");
    } catch (e: any) {
      this.error.set(e.message);
    } finally {
      this.loading.set(false);
    }
  }
  async searchTournaments() {
    this.error.set("");
    try {
      const result = await this.api.request<{ items: Tournament[] }>(
        "/tournaments?query=" +
          encodeURIComponent(this.search) +
          "&source=" +
          this.searchSource,
      );
      this.tournaments.set(result.items);
    } catch (e: any) {
      this.tournaments.set([]);
      this.error.set(e.message);
    }
  }
  async createTournament() {
    await this.run(async () => {
      const t = await this.api.request<Tournament>("/tournaments", "POST", {
        name: this.newName,
        externalId: this.externalId || null,
        password: this.password || null,
        polemicaLogin: this.polemicaLogin || null,
        polemicaPassword: this.polemicaPassword || null,
      });
      if (this.password) {
        const grant = await this.api.request<{ key: string }>(
          "/tournaments/" + t.id + "/unlock",
          "POST",
          { password: this.password },
        );
        this.api.remember(t.id, grant.key);
      }
      this.password = "";
      this.polemicaLogin = "";
      this.polemicaPassword = "";
      this.modal.set(null);
      this.newName = "";
      this.externalId = "";
      await this.openTournament(t.id);
    });
  }
  async openTournament(id: string, navigate = true) {
    this.loading.set(true);
    this.error.set("");
    try {
      const data = await this.api.request<{
        tournament: Tournament;
        games: GameSummary[];
        players: TournamentPlayer[];
      }>("/tournaments/" + id);
      this.chosenTournament.set(data.tournament);
      this.summaries.set(data.games);
      this.tournamentPlayers.set(data.players);
      this.page.set("tournament");
      if (navigate) await this.router.navigateByUrl("/tournaments/" + id);
    } catch (e: any) {
      this.error.set(e.message);
    } finally {
      this.loading.set(false);
    }
  }
  async importPolemicaTournament() {
      const tournament = this.chosenTournament();
      if (!tournament?.externalId) {
        this.notify("Сначала задайте идентификатор Polemica.");
        return;
      }
      await this.run(async () => {
        if (this.polemicaLogin && this.polemicaPassword) {
          await this.api.request(
            "/polemica/login",
            "POST",
            {
              competitionId: tournament.externalId,
              login: this.polemicaLogin,
              password: this.polemicaPassword,
            },
          );
          this.polemicaPassword = "";
        }
        const result = await this.api.request<{
          imported: number;
          members: number;
          gameRows: number;
          memberRows: number;
          skippedGames: number;
        }>(
          "/tournaments/" + tournament.id + "/polemica/import",
          "POST",
        );
        await this.openTournament(tournament.id, false);
        this.notify(
          "Polemica: строк игр " +
            result.gameRows +
            ", импортировано партий " +
            result.imported +
            ", строк игроков " +
            result.memberRows +
            ", профилей " +
            result.members +
            ", пропущено игр " +
            result.skippedGames +
            ".",
        );
      });
  }
  async addTournamentPlayer() {
    const name = this.newRosterPlayerName.trim();
    if (!name || name.length > 50) {
      this.notify("Имя: от 1 до 50 символов.");
      return;
    }
    await this.run(async () => {
      const player = await this.api.request<TournamentPlayer>(
        "/tournaments/" + this.chosenTournament()!.id + "/players",
        "POST",
        { name },
      );
      if (!this.tournamentPlayers().some((p) => p.id === player.id))
        this.tournamentPlayers.update((players) => [...players, player]);
      this.newRosterPlayerName = "";
    });
  }
  async saveTournamentPlayer(player: TournamentPlayer) {
    await this.run(async () => {
      const saved = await this.saveTournamentPlayerRequest(
        player.id,
        player.name,
        player.photo,
      );
      this.setTournamentPlayer(saved);
      player.name = saved.name;
      this.notify("Профиль обновлён во всех партиях турнира.");
    });
  }
  async uploadTournamentPlayerPhoto(event: Event, player: TournamentPlayer) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    if (
      !["image/png", "image/jpeg"].includes(file.type) ||
      file.size > 5 * 1024 * 1024
    ) {
      this.notify("Выберите JPG или PNG до 5 МБ.");
      input.value = "";
      return;
    }
    await this.run(async () => {
      const form = new FormData();
      form.append("file", file);
      const uploaded = await this.api.request<{ id: string }>(
        "/uploads",
        "POST",
        form,
      );
      const saved = await this.saveTournamentPlayerRequest(
        player.id,
        player.name,
        uploaded.id,
      );
      this.setTournamentPlayer(saved);
      player.photo = saved.photo;
      this.notify("Фото игрока обновлено во всех партиях турнира.");
    });
    input.value = "";
  }
  async removeTournamentPlayerPhoto(player: TournamentPlayer) {
    await this.run(async () => {
      const saved = await this.saveTournamentPlayerRequest(
        player.id,
        player.name,
        null,
      );
      this.setTournamentPlayer(saved);
      player.photo = null;
      this.notify("Фото удалено во всех партиях турнира.");
    });
  }
  newGame() {
    this.newTable = 1;
    this.newGameNumber =
      Math.max(0, ...this.summaries().map((g) => g.gameNumber)) + 1;
    this.newGamePlayerIds =
      this.tournamentPlayers().length === 10
        ? this.tournamentPlayers().map((p) => p.id)
        : Array(10).fill(null);
    this.modal.set("newGame");
  }
  async createGame() {
    await this.run(async () => {
      const g = await this.api.request<Game>(
        "/tournaments/" + this.chosenTournament()!.id + "/games",
        "POST",
        {
          tableNumber: this.newTable,
          gameNumber: this.newGameNumber,
          demo: this.newDemo,
          playerIds: this.newGamePlayerIds,
        },
      );
      this.modal.set(null);
      await this.loadGame(g.id);
      await this.router.navigateByUrl("/games/" + g.id);
    });
  }
  async openGame(id: string) {
    await this.loadGame(id);
    await this.router.navigateByUrl("/games/" + id);
  }
  async loadGame(id: string) {
    this.loading.set(true);
    this.error.set("");
    try {
      const g = await this.api.request<Game>("/games/" + id);
      this.game.set(normalizeGame(g));
      const data = await this.api.request<{
        tournament: Tournament;
        players: TournamentPlayer[];
      }>("/tournaments/" + g.tournamentId);
      this.chosenTournament.set(data.tournament);
      this.tournamentPlayers.set(data.players);
      this.select(1);
      this.page.set("studio");
      this.settingsDraft = structuredClone(g.state.theme);
      this.startLive(id);
      this.startPolemicaLive(id);
    } catch (e: any) {
      this.error.set(e.message);
    } finally {
      this.loading.set(false);
    }
  }
  async demo() {
    this.stopLive();
    this.game.set(this.readDemo());
    this.page.set("studio");
    this.select(4);
    this.error.set("");
    await this.router.navigateByUrl("/demo");
  }
  async loadHistory() {
    if (this.isDemo()) {
      this.events.set([]);
      return;
    }
    try {
      this.events.set(
        await this.api.request<GameEvent[]>(
          "/games/" + this.game().id + "/events",
        ),
      );
    } catch (e: any) {
      this.error.set(e.message);
    }
  }
  async restore(event: GameEvent) {
    await this.run(async () => {
      const g = await this.api.request<Game>(
        "/games/" + this.game().id + "/restore/" + event.id,
        "POST",
        { revision: this.game().revision },
      );
      this.game.set(g);
      this.select(this.selected());
      await this.loadHistory();
      this.notify("Версия восстановлена.");
    });
  }
  async undo() {
    if (this.isDemo()) {
      this.notify("История версий доступна в сохранённых партиях.");
      return;
    }
    await this.loadHistory();
    const previous = this.events()[1];
    if (previous) await this.restore(previous);
    else this.notify("Предыдущей версии ещё нет.");
  }
  async obs() {
    if (this.isDemo() && ["library", "tournament"].includes(this.page())) {
      this.notify("Сначала выберите партию за столом.");
      return;
    }
    this.error.set("");
    try {
      const token = this.isDemo()
        ? ""
        : (
            await this.api.request<{ token: string }>(
              "/games/" + this.game().id + "/overlay-link",
            )
          ).token;
      this.overlayToken = token;
      this.overlayUrl.set(
        location.origin +
          "/overlay/" +
          this.game().id +
          (token ? "?token=" + encodeURIComponent(token) : ""),
      );
      this.modal.set("obs");
    } catch (e: any) {
      this.error.set(e.message);
    }
  }
  async copyLink() {
    try {
      await navigator.clipboard.writeText(this.overlayUrl());
      this.notify("Ссылка для OBS скопирована.");
    } catch {
      this.notify(
        "Не удалось скопировать. Выделите ссылку и скопируйте вручную.",
      );
    }
  }
  async rotateLink() {
    await this.run(async () => {
      const res = await this.api.request<{ token: string }>(
        "/games/" + this.game().id + "/overlay-link",
        "POST",
      );
      this.overlayToken = res.token;
      this.overlayUrl.set(
        location.origin + "/overlay/" + this.game().id + "?token=" + res.token,
      );
      const latest = await this.api.request<Game>("/games/" + this.game().id);
      this.game.set(latest);
      this.modal.set("obs");
      this.notify("Создана новая ссылка. Замените её в OBS.");
    });
  }
  async unlockTournament() {
    await this.run(async () => {
      const grant = await this.api.request<{ key: string }>(
        "/tournaments/" + this.lockedTournament + "/unlock",
        "POST",
        { password: this.password },
      );
      this.api.remember(this.lockedTournament, grant.key);
      this.password = "";
      this.modal.set(null);
      const path = location.pathname;
      if (path.startsWith("/games/")) await this.loadGame(path.split("/")[2]);
      else await this.openTournament(this.lockedTournament);
    });
  }
  async savePassword() {
    await this.run(async () => {
      const id = this.chosenTournament()!.id;
      const grant = await this.api.request<{ key: string }>(
        "/tournaments/" + id + "/password",
        "PUT",
        { password: this.password },
      );
      this.api.remember(id, grant.key);
      this.password = "";
      this.modal.set(null);
      await this.openTournament(id);
      this.notify("Доступ к турниру обновлён.");
    });
  }
  editLocation(g: GameSummary) {
    this.editingGame = g;
    this.newTable = g.tableNumber;
    this.newGameNumber = g.gameNumber;
    this.swapLocation = false;
    this.modal.set("location");
  }
  async saveLocation() {
    await this.run(async () => {
      const g = this.editingGame!;
      await this.api.request("/games/" + g.id + "/location", "PATCH", {
        revision: g.revision,
        tableNumber: this.newTable,
        gameNumber: this.newGameNumber,
        swap: this.swapLocation,
      });
      this.modal.set(null);
      await this.openTournament(this.chosenTournament()!.id);
    });
  }
  confirmDelete(kind: "games" | "tournaments", id: string, name: string) {
    this.pendingDelete = { kind, id, name };
    this.modal.set("delete");
  }
  async deleteResource() {
    await this.run(async () => {
      const d = this.pendingDelete!;
      await this.api.request("/" + d.kind + "/" + d.id, "DELETE");
      this.modal.set(null);
      if (d.kind === "tournaments") {
        this.stopLive();
        this.game.set(this.readDemo());
        this.chosenTournament.set(null);
        await this.openLibrary();
      } else {
        if (this.game().id === d.id) {
          this.stopLive();
          this.game.set(this.readDemo());
        }
        await this.openTournament(this.chosenTournament()!.id);
      }
    });
  }
  transfer(mode: "import" | "export") {
    this.transferMode = mode;
    this.transferFile = null;
    this.modal.set("transfer");
  }
  pickArchive(event: Event) {
    this.transferFile = (event.target as HTMLInputElement).files?.[0] ?? null;
  }
  async transferTournament() {
    await this.run(async () => {
      const query = new URLSearchParams(
        Object.entries(this.transferOptions).map(([k, v]) => [k, String(v)]),
      ).toString();
      if (this.transferMode === "export") {
        const blob = await this.api.request<Blob>(
          "/tournaments/" + this.chosenTournament()!.id + "/export?" + query,
          "GET",
          undefined,
          true,
        );
        const url = URL.createObjectURL(blob),
          link = document.createElement("a");
        link.href = url;
        link.download = "tournament-" + this.chosenTournament()!.id + ".zip";
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        this.modal.set(null);
      } else {
        if (!this.transferFile) throw new Error("Выберите архив турнира.");
        const form = new FormData();
        form.append("file", this.transferFile);
        const result = await this.api.request<{ id: string }>(
          "/tournaments/import?" + query,
          "POST",
          form,
        );
        this.modal.set(null);
        await this.openTournament(result.id);
      }
    });
  }
  private async loadOverlay() {
    const id = location.pathname.split("/")[2],
      token = this.route.snapshot.queryParamMap.get("token") || "";
    this.overlayToken = token;
    if (id === "demo") {
      this.game.set(project(this.readDemo()));
      this.overlayReady.set(true);
      return;
    }
    try {
      this.game.set(
        await this.api.request<Game>(
          "/public/games/" +
            encodeURIComponent(id) +
            "?token=" +
            encodeURIComponent(token),
        ),
      );
      this.overlayReady.set(true);
      this.startLive(id, token);
    } catch (e: any) {
      this.overlayError.set(e.message);
    }
  }
  private startPolemicaLive(id: string) {
    clearInterval(this.polemicaPoll);
    if (!this.chosenTournament()?.externalId || this.isDemo()) return;
    const refresh = async () => {
      if (this.saving() || this.destroyed || this.game().id !== id) return;
      try {
        const g = await this.api.request<Game>(
          "/games/" + id + "/polemica/refresh",
          "POST",
        );
        if (g.revision >= this.game().revision) this.game.set(normalizeGame(g));
      } catch (e: any) {
        if (e.status !== 404) this.connected.set(false);
      }
    };
    void refresh();
    this.polemicaPoll = setInterval(() => void refresh(), 10000);
  }
  private stopLive() {
    clearInterval(this.poll);
    clearInterval(this.polemicaPoll);
    clearTimeout(this.reconnect);
    this.socket?.close();
    this.socket = undefined;
    this.connected.set(false);
  }
  private startLive(id: string, token?: string) {
    this.stopLive();
    const refresh = async () => {
      if (this.saving() || this.destroyed) return;
      try {
        const g = await this.api.request<Game>(
          token !== undefined
            ? "/public/games/" + id + "?token=" + encodeURIComponent(token)
            : "/games/" + id,
        );
        if (g.revision > this.game().revision) this.game.set(g);
      } catch (e: any) {
        this.connected.set(false);
        if (this.isOverlay && e.status === 404) {
          this.overlayReady.set(false);
          this.overlayError.set(
            "Ссылка отключена. Получите новую ссылку у судьи.",
          );
          this.stopLive();
        }
      }
    };
    const connect = () => {
      if (this.destroyed || this.game().id !== id) return;
      const socket = new WebSocket(
        (location.protocol === "https:" ? "wss://" : "ws://") +
          location.host +
          "/ws/games/" +
          id +
          (token !== undefined
            ? "?token=" + encodeURIComponent(token)
            : "?editKey=" +
              encodeURIComponent(this.api.key(this.game().tournamentId))),
      );
      this.socket = socket;
      socket.onopen = () => this.connected.set(true);
      socket.onmessage = (e) => {
        try {
          const g = JSON.parse(e.data) as Game;
          if (g.revision > this.game().revision) {
            if (this.saving()) this.pendingLive = g;
            else this.game.set(g);
          }
        } catch {}
      };
      socket.onerror = () => this.connected.set(false);
      socket.onclose = () => {
        this.connected.set(false);
        if (!this.destroyed && this.socket === socket)
          this.reconnect = setTimeout(connect, 5000);
      };
    };
    connect();
    this.poll = setInterval(() => {
      void refresh();
      if (this.socket?.readyState === WebSocket.OPEN) this.socket.send("ping");
    }, 10000);
  }
}
