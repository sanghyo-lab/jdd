import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";

export const COOKIE = "jdd_session";
const MAX_AGE = 8 * 60 * 60;
export class WebError extends Error {
  constructor(status, code, message, retryable = false) {
    super(message); this.status = status; this.code = code; this.retryable = retryable;
  }
}
export function configuration(env = process.env) {
  try {
    const origin = new URL(env.WEB_ORIGIN);
    const local = ["127.0.0.1", "localhost", "[::1]"].includes(origin.hostname);
    if (origin.origin !== env.WEB_ORIGIN || origin.username || origin.password ||
        (origin.protocol !== "https:" && !(origin.protocol === "http:" && local)) ||
        (env.WEB_ACCESS_PASSWORD?.length ?? 0) < 16 || (env.WEB_SESSION_SECRET?.length ?? 0) < 32) throw Error();
    return { origin: origin.origin, secure: origin.protocol === "https:",
      password: env.WEB_ACCESS_PASSWORD,
      key: createHmac("sha256", env.WEB_SESSION_SECRET).update(env.WEB_ACCESS_PASSWORD).digest() };
  } catch {
    throw new WebError(503, "WEB_NOT_CONFIGURED", "접속 설정이 준비되지 않았습니다. 운영 담당자에게 알려 주세요.");
  }
}
export function equalSecret(a, b) {
  return timingSafeEqual(createHash("sha256").update(a).digest(), createHash("sha256").update(b).digest());
}
export function createSession(config, now = Date.now()) {
  const payload = Buffer.from(JSON.stringify({ exp: Math.floor(now / 1000) + MAX_AGE,
    nonce: randomBytes(16).toString("hex") })).toString("base64url");
  return payload + "." + createHmac("sha256", config.key).update(payload).digest("base64url");
}
export function validSession(value, config, now = Date.now()) {
  if (typeof value !== "string" || value.length > 512) return false;
  try {
    const [payload, mac, extra] = value.split(".");
    if (!payload || !mac || extra !== undefined || !equalSecret(mac,
      createHmac("sha256", config.key).update(payload).digest("base64url"))) return false;
    const { exp, nonce } = JSON.parse(Buffer.from(payload, "base64url").toString());
    const seconds = Math.floor(now / 1000);
    return Number.isSafeInteger(exp) && exp > seconds && exp <= seconds + MAX_AGE &&
      typeof nonce === "string" && /^[a-f0-9]{32}$/.test(nonce);
  } catch { return false; }
}
export function cookieValue(request) {
  return (request.headers.get("cookie") ?? "").split(";").map(p => p.trim())
    .find(p => p.startsWith(COOKIE + "="))?.slice(COOKIE.length + 1);
}
export function requireSession(request, config = configuration()) {
  if (!validSession(cookieValue(request), config))
    throw new WebError(401, "UNAUTHORIZED", "다시 로그인해 주세요.");
}
export function requireOrigin(request, config = configuration()) {
  if (request.headers.get("origin") !== config.origin ||
      request.headers.get("sec-fetch-site") === "cross-site")
    throw new WebError(403, "FORBIDDEN", "허용된 화면에서 다시 요청해 주세요.");
}
export function requireRefreshOrigin(request, config) {
  if (request.headers.has("origin")) return requireOrigin(request, config);
  try {
    if (request.headers.get("sec-fetch-site") === "same-origin" &&
        new URL(request.headers.get("referer")).origin === config.origin) return;
  } catch { /* Fail closed without a verifiable browser origin. */ }
  throw new WebError(403, "FORBIDDEN", "허용된 화면에서 다시 조회해 주세요.");
}
export function sessionCookie(value, config) {
  return `${COOKIE}=${value}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${value ? MAX_AGE : 0}${config.secure ? "; Secure" : ""}`;
}
export function jsonResponse(data, status = 200, extra = {}) {
  return Response.json(data, { status, headers: { "Cache-Control": "private, no-store", ...extra } });
}
export function errorResponse(error) {
  if (error instanceof WebError) return jsonResponse({ code: error.code, message: error.message,
    retryable: error.retryable }, error.status);
  return jsonResponse({ code: "INTERNAL_ERROR", message: "요청을 처리하지 못했습니다.", retryable: false }, 500);
}
export async function readLimited(body, maxBytes) {
  if (!body) return "";
  const reader = body.getReader(); const chunks = []; let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read(); if (done) break;
      size += value.length;
      if (size > maxBytes) { await reader.cancel(); throw new WebError(413, "PAYLOAD_TOO_LARGE", "요청 또는 응답이 너무 큽니다."); }
      chunks.push(value);
    }
    return Buffer.concat(chunks).toString("utf8");
  } finally { reader.releaseLock(); }
}
export async function readJson(request, limit = 65536) {
  if ((request.headers.get("content-type") ?? "").split(";")[0].trim() !== "application/json")
    throw new WebError(415, "INVALID_REQUEST", "JSON 형식으로 요청해 주세요.");
  try { return JSON.parse(await readLimited(request.body, limit)); }
  catch (e) { if (e instanceof WebError) throw e; throw new WebError(400, "INVALID_REQUEST", "입력 형식을 확인해 주세요."); }
}
