import type { ApiError } from "./api-types";
export class ApiFailure extends Error {
  constructor(public status: number, public detail: ApiError) { super(detail.message); }
}
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try { response = await fetch(path, { ...init, cache: "no-store", credentials: "same-origin",
    headers: { "Content-Type": "application/json", ...init?.headers } }); }
  catch (e) {
    if (e instanceof Error && e.name === "AbortError") throw e;
    throw new ApiFailure(0, { code: "NETWORK_ERROR", message: "연결이 끊겼습니다. 입력은 유지됩니다. 다시 확인해 주세요.", retryable: true });
  }
  const data = await response.json().catch(() => ({ code: "INVALID_RESPONSE", message: "응답을 읽지 못했습니다.", retryable: true }));
  if (!response.ok) {
    if (response.status === 401 && path !== "/api/session") window.location.assign("/login");
    throw new ApiFailure(response.status, data);
  }
  return data as T;
}
export const statusLabel = { OPEN: "접수", IN_PROGRESS: "처리 중", RESOLVED: "해결" };
export function dateLabel(value: string) { return new Date(value).toLocaleString("ko-KR", { timeZone: "Asia/Seoul" }); }
