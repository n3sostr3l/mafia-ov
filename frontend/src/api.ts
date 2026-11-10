import { Injectable } from "@angular/core";
@Injectable({ providedIn: "root" })
export class Api {
  async request<T = any>(
    path: string,
    method = "GET",
    body?: unknown,
  ): Promise<T> {
    const headers: Record<string, string> = {};
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
    const data = await res.json().catch(() => ({}));
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
