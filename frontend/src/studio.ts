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
import {
  Check,
  Game,
  GameEvent,
  GameSummary,
  Player,
  Role,
  State,
  Tournament,
  User,
  demoGame,
  phaseLabel,
  photoId,
  project,
  roleLabel,
  roles,
  phases,
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
  ],
  templateUrl: "./studio.html",
})
export class StudioComponent implements OnInit, OnDestroy {
  private api = inject(Api);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  game = signal<Game>(this.readDemo());
  user = signal<User | null>(null);
  selected = signal(4);
  page = signal<
    "studio" | "library" | "tournament" | "settings" | "history" | "help"
  >("studio");
  tab = signal<"player" | "testament" | "checks">("player");
  modal = signal<
    "auth" | "obs" | "newTournament" | "newGame" | "rotate" | null
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
  authMode: "login" | "register" = "login";
  login = "";
  username = "";
  email = "";
  password = "";
  authMessage = signal("");
  verificationUrl = signal("");
  draftName = "";
  testamentMode: "COLORS" | "WHEEL" = "COLORS";
  testamentSeats: (number | null)[] = [null, null, null];
  testamentColors: Role[] = ["MAFIA", "MAFIA", "MAFIA"];
  wheel = 1;
  checkBy: "DON" | "SHERIFF" = "SHERIFF";
  checkSeat = 1;
  checkResult = "RED";
  checkPublished = false;
  settingsDraft!: State["theme"];
  mobileMenu = signal(false);
  integrationOpen = signal(false);
  private socket?: WebSocket;
  private poll?: ReturnType<typeof setInterval>;
  private toastTimer?: ReturnType<typeof setTimeout>;
  private reconnect?: ReturnType<typeof setTimeout>;
  private destroyed = false;
  private pendingLive?: Game;
  private storageListener = (e: StorageEvent) => {
    if (e.key === "polemica-demo-v1" && this.isDemo()) {
      const g = this.readDemo();
      this.game.set(this.isOverlay ? project(g) : g);
    }
  };
  async ngOnInit() {
    window.addEventListener("storage", this.storageListener);
    this.select(4);
    this.settingsDraft = structuredClone(this.game().state.theme);
    if (this.isOverlay) {
      await this.loadOverlay();
      return;
    }
    try {
      await this.api.csrf();
      const verify = this.route.snapshot.queryParamMap.get("verify");
      if (verify) {
        const result = await this.api.request("/auth/verify", "POST", {
          token: verify,
        });
        this.notify(result.message);
        await this.router.navigate([], { queryParams: {}, replaceUrl: true });
        this.modal.set("auth");
      }
      try {
        this.user.set(await this.api.request<User>("/auth/me"));
      } catch (e: any) {
        if (e.status !== 401) throw e;
      }
      const path = location.pathname;
      if (path.startsWith("/games/")) {
        if (this.user()) await this.loadGame(path.split("/")[2]);
        else {
          this.error.set("Войдите в аккаунт, чтобы открыть партию.");
          this.modal.set("auth");
        }
      } else if (path.startsWith("/tournaments/")) {
        if (this.user()) await this.openTournament(path.split("/")[2], false);
        else this.modal.set("auth");
      } else if (this.user() && path !== "/demo") await this.openLibrary(false);
    } catch (e: any) {
      this.error.set(e.message);
    }
  }
  ngOnDestroy() {
    this.destroyed = true;
    this.stopLive();
    clearTimeout(this.toastTimer);
    window.removeEventListener("storage", this.storageListener);
  }
  readDemo(): Game {
    try {
      const g = JSON.parse(localStorage.getItem("polemica-demo-v1") || "null");
      if (g?.id === "demo" && g.state?.players?.length === 10) return g;
    } catch {}
    return demoGame();
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
        next.revision++;
        next.updatedAt = new Date().toISOString();
        this.game.set(next);
        localStorage.setItem("polemica-demo-v1", JSON.stringify(next));
        this.notify(description);
      } else {
        const result = await this.api.request<Game>(
          "/games/" + current.id,
          "PUT",
          { revision: current.revision, state: next.state, description },
        );
        this.game.set(result);
        this.notify("Сохранено: " + description.toLowerCase());
      }
    });
  }
  select(seat: number) {
    this.selected.set(seat);
    const p = this.game().state.players.find((p) => p.seat === seat);
    if (!p) return;
    this.draftName = p.name;
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
      s.players[this.selected() - 1].status = status;
      if (status !== "ALIVE" && s.speaker === this.selected()) s.speaker = null;
    });
  }
  async togglePu() {
    await this.change("Статус ПУ изменён", (s) => {
      const p = s.players[this.selected() - 1];
      p.pu = !p.pu;
    });
  }
  async saveName() {
    if (!this.draftName.trim() || this.draftName.length > 50) {
      this.notify("Имя: от 1 до 50 символов.");
      return;
    }
    await this.change(
      "Имя игрока изменено",
      (s) => (s.players[this.selected() - 1].name = this.draftName.trim()),
    );
  }
  async speak(seat: number | null) {
    if (
      seat !== null &&
      this.game().state.players[seat - 1].status !== "ALIVE"
    ) {
      this.notify("Сначала верните игрока в игру.");
      return;
    }
    await this.change(
      seat ? "Выступает игрок " + seat : "Выступление завершено",
      (s) => (s.speaker = seat),
    );
  }
  async nominate(seat: number) {
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
  async setPhase(value: string) {
    await this.change("Фаза партии изменена", (s) => {
      s.phase = value as State["phase"];
      s.speaker = null;
    });
  }
  async round(delta: number) {
    await this.change(
      "Раунд изменён",
      (s) => (s.round = Math.max(1, Math.min(100, s.round + delta))),
    );
  }
  async nextRound() {
    await this.change("Начат следующий день", (s) => {
      s.round = Math.min(100, s.round + 1);
      s.phase = "DAY";
      s.speaker = null;
      s.nominees = [];
    });
  }
  async toggleVisibility(field: "showRoles" | "showChecks") {
    await this.change(
      "Публичное отображение обновлено",
      (s) => (s[field] = !s[field]),
    );
  }
  async reveal() {
    await this.change("Публичная роль игрока изменена", (s) => {
      const p = s.players[this.selected() - 1];
      p.revealRole = !p.revealRole;
    });
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
  changeCheckBy() {
    this.checkResult = this.checkBy === "DON" ? "NOT_SHERIFF" : "RED";
  }
  async addCheck() {
    await this.change("Проверка добавлена", (s) => {
      s.checks.push({
        id: crypto.randomUUID(),
        by: this.checkBy,
        seat: this.checkSeat,
        result: this.checkResult,
        round: s.round,
        published: this.checkPublished,
      });
    });
  }
  async removeCheck(id: string) {
    await this.change(
      "Проверка удалена",
      (s) => (s.checks = s.checks.filter((c) => c.id !== id)),
    );
  }
  async publishCheck(id: string) {
    await this.change("Видимость проверки изменена", (s) => {
      const c = s.checks.find((c) => c.id === id)!;
      c.published = !c.published;
    });
  }
  checkLabel(check: Check) {
    return (
      {
        RED: "Мирный",
        BLACK: "Мафия",
        SHERIFF: "Шериф",
        NOT_SHERIFF: "Не шериф",
      } as Record<string, string>
    )[check.result];
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
      this.notify("Войдите и создайте партию для загрузки фотографий.");
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
    await this.change(
      "Индивидуальная фотография удалена",
      (s) => (s.players[this.selected() - 1].photo = null),
    );
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
      if (!this.user()) {
        this.modal.set("auth");
        return;
      }
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
      });
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
      }>("/tournaments/" + id);
      this.chosenTournament.set(data.tournament);
      this.summaries.set(data.games);
      this.page.set("tournament");
      if (navigate) await this.router.navigateByUrl("/tournaments/" + id);
    } catch (e: any) {
      this.error.set(e.message);
    } finally {
      this.loading.set(false);
    }
  }
  newGame() {
    this.newTable = 1;
    this.newGameNumber =
      Math.max(0, ...this.summaries().map((g) => g.gameNumber)) + 1;
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
      this.game.set(g);
      this.select(1);
      this.page.set("studio");
      this.settingsDraft = structuredClone(g.state.theme);
      this.startLive(id);
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
  async auth() {
    this.authMessage.set("");
    this.verificationUrl.set("");
    await this.run(async () => {
      if (this.authMode === "register") {
        const res = await this.api.request("/auth/register", "POST", {
          username: this.username,
          email: this.email,
          password: this.password,
        });
        this.authMessage.set(res.message);
        this.verificationUrl.set(res.verificationUrl || "");
        this.password = "";
      } else {
        this.user.set(
          await this.api.request<User>("/auth/login", "POST", {
            login: this.login,
            password: this.password,
          }),
        );
        this.password = "";
        this.modal.set(null);
        await this.api.csrf();
        const path = location.pathname;
        if (path.startsWith("/games/")) await this.loadGame(path.split("/")[2]);
        else if (path.startsWith("/tournaments/"))
          await this.openTournament(path.split("/")[2], false);
        else await this.openLibrary();
      }
    });
  }
  async logout() {
    await this.run(async () => {
      await this.api.request("/auth/logout", "POST");
      this.user.set(null);
      await this.api.csrf();
      await this.demo();
    });
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
  private stopLive() {
    clearInterval(this.poll);
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
          (token !== undefined ? "?token=" + encodeURIComponent(token) : ""),
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
