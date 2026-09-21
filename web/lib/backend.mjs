import { WebError, configuration, requireSession, requireOrigin, requireRefreshOrigin, readJson, readLimited,
  jsonResponse, errorResponse } from "./security.mjs";

// Match decoded path segments once, then encode IDs. No URL, admin or wildcard forwarding.
const id = /^[A-Za-z0-9_:-][A-Za-z0-9_.:-]{0,199}$/;
export function destination(parts, method, query) {
  if (!Array.isArray(parts) || parts.some(p => !id.test(p))) return null;
  const path = parts.join("/"); let service = "voc"; let allowed; let methods;
  if (path === "assignees") { methods = ["GET"]; allowed = []; }
  else if (path === "tickets") { methods = ["GET", "POST"]; allowed = ["status", "assigneeId", "limit", "offset"]; }
  else if (/^tickets\/[^/]+$/.test(path)) { methods = ["GET", "PATCH"]; allowed = []; }
  else if (/^tickets\/[^/]+\/analyses$/.test(path)) { methods = ["POST"]; allowed = []; }
  else if (/^tickets\/[^/]+\/analyses\/[^/]+$/.test(path)) { methods = ["GET"]; allowed = ["refresh"]; }
  else if (/^tickets\/[^/]+\/analyses\/[^/]+\/evidence\/[^/]+$/.test(path)) { methods = ["GET"]; allowed = []; }
  else if (parts[0] === "commerce") {
    service = "commerce"; const sub = parts.slice(1).join("/");
    if (sub === "products") { methods = ["GET"]; allowed = ["limit", "offset"]; }
    else if (/^products\/[^/]+$/.test(sub)) { methods = ["GET"]; allowed = []; }
    else if (sub === "orders") { methods = ["GET", "POST"]; allowed = ["customerId", "checkoutKey", "limit", "offset"]; }
    else if (/^orders\/[^/]+$/.test(sub)) { methods = ["GET"]; allowed = []; }
    else if (/^orders\/[^/]+\/(payments|cancel)$/.test(sub)) { methods = ["POST"]; allowed = []; }
    else if (/^customers\/[^/]+\/coupons$/.test(sub)) { methods = ["GET"]; allowed = ["limit", "offset"]; }
  }
  if (!methods) return null;
  if (!methods.includes(method)) throw new WebError(405, "METHOD_NOT_ALLOWED", "지원하지 않는 요청입니다.");
  const seen = new Set();
  for (const [key, value] of query) {
    if (method !== "GET" || !allowed.includes(key) || seen.has(key) || value.length > 200)
      throw new WebError(400, "INVALID_REQUEST", "조회 조건을 확인해 주세요.");
    seen.add(key);
  }
  const selected = service === "commerce" ? parts.slice(1) : parts;
  return { service, path: "/api/" + selected.map(encodeURIComponent).join("/") };
}
function backendOrigin(service, env) {
  try {
    const value = env[service === "voc" ? "VOC_API_BASE_URL" : "COMMERCE_API_BASE_URL"];
    const url = new URL(value);
    if (!["http:", "https:"].includes(url.protocol) || value !== url.origin || url.username || url.password) throw Error();
    return url.origin;
  } catch { throw new WebError(503, "BACKEND_NOT_CONFIGURED", "서비스 연결 설정이 준비되지 않았습니다."); }
}
export async function relay(request, parts, env = process.env, fetcher = fetch) {
  try {
    const config = configuration(env); requireSession(request, config);
    const url = new URL(request.url);
    const target = destination(parts, request.method, url.searchParams);
    if (!target) throw new WebError(404, "NOT_FOUND", "제공하지 않는 경로입니다.");
    // refresh=true is a scheduling operation even though the backend uses GET.
    if (request.method !== "GET") requireOrigin(request, config);
    else if (url.searchParams.get("refresh") === "true") requireRefreshOrigin(request, config);
    const headers = { Accept: "application/json" };
    if (env.BACKEND_SERVICE_TOKEN) headers.Authorization = "Bearer " + env.BACKEND_SERVICE_TOKEN;
    let body;
    if (request.method !== "GET") {
      body = JSON.stringify(await readJson(request)); headers["Content-Type"] = "application/json";
    }
    const origin = backendOrigin(target.service, env);
    let response;
    try {
      response = await fetcher(origin + target.path + url.search, { method: request.method,
        headers, body, cache: "no-store", redirect: "manual", signal: AbortSignal.timeout(12000) });
      if (response.status >= 300 && response.status < 400) throw Error();
      if (!(response.headers.get("content-type") ?? "").toLowerCase().startsWith("application/json")) throw Error();
      const data = JSON.parse(await readLimited(response.body, 4 * 1024 * 1024));
      const extras = {};
      const retry = response.headers.get("retry-after");
      if (retry && /^\d{1,5}$/.test(retry)) extras["Retry-After"] = retry;
      return jsonResponse(data, response.status, extras);
    } catch {
      throw new WebError(502, "BACKEND_UNAVAILABLE", "서비스에 연결하지 못했습니다. 저장된 상태를 다시 확인해 주세요.", true);
    }
  } catch (error) { return errorResponse(error); }
}
