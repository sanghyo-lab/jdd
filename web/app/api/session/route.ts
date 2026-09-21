import "server-only";
import { configuration, createSession, equalSecret, requireOrigin, requireSession,
  sessionCookie, readJson, jsonResponse, errorResponse, WebError } from "@/lib/security.mjs";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
// Bounded single-process demo limit. Never trust forwarded IP headers as an identity.
let attempts: number[] = [];
export async function POST(request: Request) {
  try {
    const config = configuration(); requireOrigin(request, config);
    attempts = attempts.filter(t => t > Date.now() - 60000);
    if (attempts.length >= 20) return jsonResponse({ code: "LOGIN_LIMIT", message: "잠시 후 다시 로그인해 주세요.", retryable: true }, 429, { "Retry-After": "60" });
    attempts.push(Date.now());
    const data = await readJson(request, 4096);
    if (!data || typeof data.password !== "string" || !equalSecret(data.password, config.password))
      throw new WebError(401, "UNAUTHORIZED", "접속 암호를 확인해 주세요.");
    return jsonResponse({ authenticated: true }, 200, { "Set-Cookie": sessionCookie(createSession(config), config) });
  } catch (error) { return errorResponse(error); }
}
export async function DELETE(request: Request) {
  try {
    const config = configuration(); requireOrigin(request, config); requireSession(request, config);
    return jsonResponse({ authenticated: false }, 200, { "Set-Cookie": sessionCookie("", config) });
  } catch (error) { return errorResponse(error); }
}
