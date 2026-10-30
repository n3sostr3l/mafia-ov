export type Role = "CIVILIAN" | "MAFIA" | "DON" | "SHERIFF" | "UNKNOWN";
export type Status = "ALIVE" | "SHOT" | "VOTED";
export type Phase = "PREPARATION" | "DAY" | "VOTING" | "NIGHT" | "FINISHED";
export interface ColorPick {
  seat: number;
  color: Role;
}
export interface Testament {
  mode: "COLORS" | "WHEEL";
  colors: ColorPick[];
  wheel: number | null;
}
export interface Player {
  seat: number;
  name: string;
  role: Role;
  status: Status;
  pu: boolean;
  revealRole: boolean;
  photo: string | null;
  importedPhoto: string | null;
  testament: Testament | null;
}
export interface Check {
  id: string;
  by: "DON" | "SHERIFF";
  seat: number;
  result: string;
  round: number;
  published: boolean;
}
export interface Theme {
  civilian: string;
  mafia: string;
  special: string;
  transparent: boolean;
  width: number;
  height: number;
  useRolePhotos: boolean;
  rolePhotos: Partial<Record<Role, string>>;
}
export interface State {
  players: Player[];
  nominees: number[];
  checks: Check[];
  theme: Theme;
  phase: Phase;
  round: number;
  speaker: number | null;
  showRoles: boolean;
  showChecks: boolean;
}
export interface Game {
  id: string;
  tournamentId: string;
  tournamentName: string;
  tableNumber: number;
  gameNumber: number;
  revision: number;
  updatedAt: string;
  state: State;
}
export interface Tournament {
  id: string;
  name: string;
  externalId: string | null;
  createdAt: string;
}
export interface GameSummary {
  id: string;
  tableNumber: number;
  gameNumber: number;
  revision: number;
  updatedAt: string;
}
export interface User {
  id: string;
  username: string;
  email: string;
}
export interface GameEvent {
  id: string;
  revision: number;
  description: string;
  createdAt: string;
}
export const roles: { value: Role; label: string; short: string }[] = [
  { value: "CIVILIAN", label: "Мирный", short: "Мирный" },
  { value: "MAFIA", label: "Мафия", short: "Мафия" },
  { value: "DON", label: "Дон", short: "Дон" },
  { value: "SHERIFF", label: "Шериф", short: "Шериф" },
];
export const phases: { value: Phase; label: string }[] = [
  { value: "PREPARATION", label: "Подготовка" },
  { value: "DAY", label: "День" },
  { value: "VOTING", label: "Голосование" },
  { value: "NIGHT", label: "Ночь" },
  { value: "FINISHED", label: "Завершена" },
];
export const roleLabel = (role: Role) =>
  roles.find((r) => r.value === role)?.label ?? "Роль скрыта";
export const phaseLabel = (phase: Phase) =>
  phases.find((p) => p.value === phase)?.label ?? phase;
export function photoId(
  state: State,
  player: Player,
  publicView = false,
): string | null {
  return (
    player.photo ||
    (state.theme.useRolePhotos &&
    (!publicView || state.showRoles || player.revealRole)
      ? state.theme.rolePhotos[player.role]
      : null) ||
    player.importedPhoto ||
    null
  );
}
export function project(game: Game): Game {
  const g = structuredClone(game),
    s = g.state;
  s.players.forEach((p) => {
    const image = photoId(s, p, true);
    p.role = s.showRoles || p.revealRole ? p.role : "UNKNOWN";
    p.photo = image;
    p.importedPhoto = null;
    p.revealRole = false;
  });
  s.checks = s.checks.filter((c) => s.showChecks && c.published);
  s.theme.rolePhotos = {};
  s.theme.useRolePhotos = false;
  return g;
}
export function demoGame(): Game {
  const names = [
    "Алекс",
    "Лис",
    "Вега",
    "Север",
    "Мира",
    "Космос",
    "Рэй",
    "Феникс",
    "Тень",
    "Ника",
  ];
  return {
    id: "demo",
    tournamentId: "demo",
    tournamentName: "Кубок осени",
    tableNumber: 1,
    gameNumber: 3,
    revision: 0,
    updatedAt: new Date().toISOString(),
    state: {
      players: names.map((name, i) => ({
        seat: i + 1,
        name,
        role: (i === 5
          ? "DON"
          : i === 3
            ? "SHERIFF"
            : [1, 7].includes(i)
              ? "MAFIA"
              : "CIVILIAN") as Role,
        status: i === 2 ? "SHOT" : "ALIVE",
        pu: false,
        revealRole: false,
        photo: null,
        importedPhoto: null,
        testament:
          i === 2
            ? {
                mode: "COLORS",
                colors: [2, 6, 9].map((seat) => ({ seat, color: "MAFIA" })),
                wheel: null,
              }
            : null,
      })),
      nominees: [2, 6, 9],
      checks: [
        {
          id: "demo-check",
          by: "SHERIFF",
          seat: 2,
          result: "BLACK",
          round: 1,
          published: false,
        },
      ],
      theme: {
        civilian: "#e94549",
        mafia: "#262a32",
        special: "#efc34d",
        transparent: false,
        width: 1920,
        height: 1080,
        useRolePhotos: false,
        rolePhotos: {},
      },
      phase: "DAY",
      round: 2,
      speaker: 4,
      showRoles: false,
      showChecks: false,
    },
  };
}
