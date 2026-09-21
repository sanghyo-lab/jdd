import { test } from "node:test";
import assert from "node:assert/strict";
import { configuration, createSession, validSession, sessionCookie, readJson, requireOrigin, WebError } from "../lib/security.mjs";
import { destination, relay } from "../lib/backend.mjs";

const env = { WEB_ORIGIN: "http://127.0.0.1:3000", WEB_ACCESS_PASSWORD: "synthetic-contract-password",
  WEB_SESSION_SECRET: "synthetic-contract-session-signing-secret", VOC_API_BASE_URL: "http://127.0.0.1:8082",
  COMMERCE_API_BASE_URL: "http://127.0.0.1:8080", BACKEND_SERVICE_TOKEN: "synthetic-backend-only-token" };
const config = configuration(env);
const cookie = "jdd_session=" + createSession(config);
function request(path, method = "GET", headers = {}, body) {
  return new Request(env.WEB_ORIGIN + "/api/" + path, { method, headers: { cookie, ...headers },
    ...(body === undefined ? {} : { body }) });
}
async function denied(req, parts, expected, settings = env) {
  let calls = 0;
  const result = await relay(req, parts, settings, async () => { calls++; throw Error("must not call backend"); });
  assert.equal(result.status, expected); assert.equal(calls, 0); assert.equal(result.headers.get("cache-control"), "private, no-store");
}
test("configuration fails closed and requires HTTPS except for explicit loopback", () => {
  for (const patch of [{ WEB_ORIGIN: "http://public.example" }, { WEB_ORIGIN: "https://example.com/path" },
    { WEB_ORIGIN: "https://name:password@example.com" }, { WEB_ORIGIN: "http://localhost:3000/" },
    { WEB_SESSION_SECRET: "" }, { WEB_ACCESS_PASSWORD: "" }]) assert.throws(() => configuration({ ...env, ...patch }), WebError);
  assert.equal(configuration({ ...env, WEB_ORIGIN: "https://demo.example" }).secure, true);
});
test("sessions reject tampering, expiration, extra segments and secret rotation", () => {
  const token = createSession(config, 1000000); assert.ok(validSession(token, config, 1000001));
  for (const invalid of [token + ".extra", token + "a", token.replace(/.$/, "!"), "garbage", undefined])
    assert.equal(validSession(invalid, config, 1000001), false);
  assert.equal(validSession(token, config, 1000000 + 8 * 3600000), false);
  assert.equal(validSession(token, configuration({ ...env, WEB_ACCESS_PASSWORD: "rotated-synthetic-password" }), 1000001), false);
});
test("cookies are HttpOnly, strict, bounded and Secure on HTTPS; logout expires them", () => {
  const text = sessionCookie("test", { ...config, secure: true });
  for (const attr of ["HttpOnly", "SameSite=Strict", "Max-Age=28800", "Secure", "Path=/"]) assert.ok(text.includes(attr));
  assert.ok(sessionCookie("", config).includes("Max-Age=0")); assert.ok(!text.includes("Domain="));
});
test("untrusted forwarded host cannot satisfy the canonical origin guard", () => {
  assert.throws(() => requireOrigin(request("tickets", "POST", { origin: "https://attacker.example", "x-forwarded-host": "127.0.0.1:3000" }), config));
  assert.throws(() => requireOrigin(request("tickets", "POST"), config));
  requireOrigin(request("tickets", "POST", { origin: env.WEB_ORIGIN }), config);
});
test("request parser rejects wrong media, malformed and oversized bodies", async () => {
  for (const [body, type, limit, status] of [["{}", "text/plain", 100, 415], ["{", "application/json", 100, 400],
    [JSON.stringify({ value: "x".repeat(100) }), "application/json", 10, 413]]) {
    await assert.rejects(() => readJson(request("tickets", "POST", { "content-type": type }, body), limit), e => e.status === status);
  }
});
test("every documented VOC and commerce path maps only to its owning backend", () => {
  for (const [path, method, service, backend] of [
    ["assignees", "GET", "voc", "/api/assignees"], ["tickets", "GET", "voc", "/api/tickets"],
    ["tickets", "POST", "voc", "/api/tickets"], ["tickets/t-1", "PATCH", "voc", "/api/tickets/t-1"],
    ["tickets/t-1/analyses", "POST", "voc", "/api/tickets/t-1/analyses"],
    ["tickets/t-1/analyses/a-1", "GET", "voc", "/api/tickets/t-1/analyses/a-1"],
    ["tickets/t-1/analyses/a-1/evidence/e-1", "GET", "voc", "/api/tickets/t-1/analyses/a-1/evidence/e-1"],
    ["commerce/products", "GET", "commerce", "/api/products"], ["commerce/products/p-1", "GET", "commerce", "/api/products/p-1"],
    ["commerce/orders", "GET", "commerce", "/api/orders"], ["commerce/orders", "POST", "commerce", "/api/orders"],
    ["commerce/orders/o-1", "GET", "commerce", "/api/orders/o-1"],
    ["commerce/orders/o-1/payments", "POST", "commerce", "/api/orders/o-1/payments"],
    ["commerce/orders/o-1/cancel", "POST", "commerce", "/api/orders/o-1/cancel"],
    ["commerce/customers/c-1/coupons", "GET", "commerce", "/api/customers/c-1/coupons"],
  ]) assert.deepEqual(destination(path.split("/"), method, new URLSearchParams()), { service, path: backend });
});
test("admin, Agent, arbitrary URL and path traversal cannot become a proxy target", () => {
  for (const path of ["internal/runtime", "actuator/health", "investigations/x", "commerce/internal/runtime", "http://example.com", "tickets/..", "tickets/%2fadmin", "tickets/a/b", "commerce/orders/x/refund"])
    assert.equal(destination(path.split("/"), "GET", new URLSearchParams()), null);
  assert.throws(() => destination(["tickets"], "DELETE", new URLSearchParams()), e => e.status === 405);
  for (const query of ["url=https://example.com", "status=OPEN&status=RESOLVED", "refresh=true"])
    assert.throws(() => destination(["tickets"], "GET", new URLSearchParams(query)), e => e.status === 400);
});
test("unauthenticated or unconfigured API requests never reach the backend", async () => {
  await denied(new Request(env.WEB_ORIGIN + "/api/tickets"), ["tickets"], 401);
  await denied(request("tickets"), ["tickets"], 503, { ...env, WEB_SESSION_SECRET: "" });
});
test("cross-site writes and malformed requests never reach the backend", async () => {
  await denied(request("tickets", "POST", { origin: "https://attacker.example" }, "{}"), ["tickets"], 403);
  await denied(request("tickets", "POST", { origin: env.WEB_ORIGIN, "content-type": "application/json" }, "bad"), ["tickets"], 400);
  await denied(request("tickets", "DELETE", { origin: env.WEB_ORIGIN }), ["tickets"], 405);
});
test("manual refresh requires browser origin proof, ordinary cached GET stays read-only", async () => {
  const parts = ["tickets", "t-1", "analyses", "a-1"];
  await denied(request(parts.join("/") + "?refresh=true"), parts, 403);
  const result = await relay(request(parts.join("/") + "?refresh=true", "GET", {
    referer: env.WEB_ORIGIN + "/tickets/t-1", "sec-fetch-site": "same-origin" }), parts, env,
  async url => { assert.equal(url, env.VOC_API_BASE_URL + "/api/" + parts.join("/") + "?refresh=true"); return Response.json({ investigation: null }); });
  assert.equal(result.status, 200);
});
test("relay strips client credentials and only sends configured service token, preserves contract and retry hints", async () => {
  let calls = 0;
  const result = await relay(request("tickets", "POST", { origin: env.WEB_ORIGIN, "content-type": "application/json",
    authorization: "Bearer client-secret", "x-forwarded-host": "attacker.example" }, JSON.stringify({ title: "합성 문의" })), ["tickets"], env,
  async (url, init) => { calls++; assert.equal(url, env.VOC_API_BASE_URL + "/api/tickets");
    assert.equal(init.redirect, "manual"); assert.equal(init.cache, "no-store");
    assert.deepEqual(init.headers, { Accept: "application/json", Authorization: "Bearer " + env.BACKEND_SERVICE_TOKEN, "Content-Type": "application/json" });
    assert.equal(JSON.parse(init.body).title, "합성 문의");
    return Response.json({ code: "INVESTIGATION_QUEUE_FULL", message: "대기 중", retryable: true }, { status: 429,
      headers: { "Retry-After": "5", "Set-Cookie": "backend-secret=hidden", "X-Internal": "hidden" } }); });
  assert.equal(calls, 1); assert.equal(result.status, 429); assert.equal(result.headers.get("retry-after"), "5");
  assert.equal(result.headers.get("set-cookie"), null); assert.equal(result.headers.get("x-internal"), null);
  assert.equal((await result.json()).code, "INVESTIGATION_QUEUE_FULL");
});
test("redirects, HTML, lost responses and oversized responses are sanitized without retrying POST", async () => {
  for (const response of [() => new Response("secret", { status: 302, headers: { location: "http://other.internal" } }),
    () => new Response("<html>secret</html>"), () => Response.json({ value: "x".repeat(4 * 1024 * 1024) }),
    () => { throw Error("private-address-and-token"); }]) {
    let calls = 0; const result = await relay(request("tickets", "POST", { origin: env.WEB_ORIGIN, "content-type": "application/json" }, "{}"), ["tickets"], env, async () => { calls++; return response(); });
    assert.equal(calls, 1); assert.equal(result.status, 502); const value = await result.text();
    assert.ok(!value.includes("private-address")); assert.ok(!value.includes("secret")); assert.ok(value.includes("BACKEND_UNAVAILABLE"));
  }
});
test("backend configuration rejects URL credentials, query and path before fetch", async () => {
  for (const value of ["http://name:secret@localhost:8082", "http://localhost:8082/path", "http://localhost:8082?query=1", "file:///tmp/api"])
    await denied(request("tickets"), ["tickets"], 503, { ...env, VOC_API_BASE_URL: value });
});
