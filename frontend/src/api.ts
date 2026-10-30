import { Injectable } from "@angular/core";
@Injectable({ providedIn: "root" })
export class Api {
  private token = "";
  private header = "X-CSRF-TOKEN";
  async csrf() {
    const res = await fetch("/api/v1/auth/csrf");
    if (!res.ok)
      throw new Error("Сервер недоступен. Проверьте запуск backend.");
    const data = await res.json();
    this.token = data.token;
    this.header = data.header;
  }
  async request<T = any>(
    path: string,
    method = "GET",
    body?: unknown,
  ): Promise<T> {
    const headers: Record<string, string> = {};
    if (method !== "GET") {
      if (!this.token) await this.csrf();
      headers[this.header] = this.token;
    }
    const isFile = body instanceof FormData;
    if (body !== undefined && !isFile)
      headers["Content-Type"] = "application/json";
    let res: Response;
    try {
      res = await fetch("/api/v1" + path, {
        method,
        headers,
        body:
          body === undefined ? undefined : isFile ? body : JSON.stringify(body),
      });
    } catch {
      throw new Error("Нет связи с сервером. Изменение не сохранено.");
    }
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      if (res.status === 403) this.token = "";
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
