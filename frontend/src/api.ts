import { Injectable } from "@angular/core";
@Injectable({ providedIn: "root" })
export class Api {
  private keys: Record<string, string> = (() => {
    try {
      return JSON.parse(sessionStorage.getItem("tournament-keys") || "{}");
    } catch {
      return {};
    }
  })();
  private activeKey = "";
  key(id: string) {
    return this.keys[id] ?? "";
  }
  remember(id: string, key: string) {
    this.keys[id] = key;
    this.activeKey = key;
    sessionStorage.setItem("tournament-keys", JSON.stringify(this.keys));
  }

  async request<T = any>(
    path: string,
    method = "GET",
    body?: unknown,
    binary = false,
    retried = false,
  ): Promise<T> {
    const headers: Record<string, string> = {};
    if (this.activeKey && !path.startsWith("/public/"))
      headers["X-Tournament-Key"] = this.activeKey;
    const isFile = body instanceof FormData;
    if (body !== undefined && !isFile)
      headers["Content-Type"] = "application/json";
    let res: Response;
    try {
      res = await fetch("/api/v1" + path, {
        method,
        headers,
        credentials: "omit",
        body:
          body === undefined ? undefined : isFile ? body : JSON.stringify(body),
      });
    } catch {
      throw new Error("Нет связи с сервером. Изменение не сохранено.");
    }
    if (res.ok && binary) return (await res.blob()) as T;
    const data = await res.json().catch(() => ({}));
    if (res.status === 401 && data.tournamentId) {
      if (
        !retried &&
        this.keys[data.tournamentId] &&
        this.activeKey !== this.keys[data.tournamentId]
      ) {
        this.activeKey = this.keys[data.tournamentId];
        return this.request<T>(path, method, body, binary, true);
      }
      window.dispatchEvent(
        new CustomEvent("tournament-locked", {
          detail: { id: data.tournamentId },
        }),
      );
    }
    if (!res.ok) {
      throw Object.assign(
        new Error(
          data.message || `Ошибка сервера (${res.status}). Повторите запрос.`,
        ),
        { status: res.status },
      );
    }
    return data;
  }
}
