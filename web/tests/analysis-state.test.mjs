import { test } from "node:test";
import assert from "node:assert/strict";
import { shouldPoll, retryBody, newRequest, pendingKey, readPending, restorePending, submitPreservingKey, matchingEvidence, errorGuidance } from "../lib/analysis-state.mjs";
function memoryStorage() {
  const values = new Map();
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
}
const ticketId = "ticket-a";
const intent = { requestKey: "once", ticketVersion: 2, previousInvestigationId: "previous-investigation" };
const saved = { analysisRequestId: "saved-analysis", ticketId, ticketVersion: 2, input: { ticketId, ...intent } };
const now = Date.parse("2026-09-22T01:00:00Z");
const active = { submissionStatus: "SUBMITTED", investigation: { status: "RUNNING" }, createdAt: new Date(now).toISOString(), syncError: null };
test("polling stops at terminal delivery/investigation or observation boundary without reclassifying failed reads", () => {
  assert.equal(shouldPoll(active, now), true);
  for (const status of ["COMPLETED", "NEEDS_INPUT", "FAILED"]) assert.equal(shouldPoll({ ...active, investigation: { status } }, now), false);
  assert.equal(shouldPoll({ ...active, submissionStatus: "FAILED", investigation: null }, now), false);
  assert.equal(shouldPoll({ ...active, syncError: { code: "AGENT_UNAVAILABLE" } }, now), true);
  assert.equal(shouldPoll({ ...active, syncError: { code: "AGENT_OBSERVATION_EXPIRED" } }, now), false);
  assert.equal(shouldPoll(active, now + 14 * 60 * 1000), false);
});
test("delivery retry preserves the stored key, version and previous investigation despite subsequent edits", () => {
  const previous = { input: { requestKey: "preserved", ticketVersion: 1, previousInvestigationId: "old-investigation", message: "old" } };
  assert.deepEqual(retryBody(previous), { requestKey: "preserved", ticketVersion: 1, previousInvestigationId: "old-investigation" });
  assert.deepEqual(newRequest({ version: 3 }, "current-investigation", "new-key"), { requestKey: "new-key", ticketVersion: 3, previousInvestigationId: "current-investigation" });
});
test("ambiguous pending intent survives reload and cannot leak across tickets", () => {
  const values = new Map(); const storage = { getItem: key => values.get(key) ?? null };
  const intent = { requestKey: "once", ticketVersion: 2, previousInvestigationId: null };
  values.set(pendingKey("ticket-a"), JSON.stringify(intent));
  assert.deepEqual(readPending(storage, "ticket-a"), intent); assert.equal(readPending(storage, "ticket-b"), null);
  for (const invalid of ["{", "null", JSON.stringify({ ...intent, ticketVersion: 0 }), JSON.stringify({ ...intent, requestKey: "" })]) {
    values.set(pendingKey("ticket-a"), invalid); assert.throws(() => readPending(storage, "ticket-a"), /접수/);
  }
});
test("lost POST response survives a new browser session and only the identical intent can be retried", async () => {
  const storage = memoryStorage(); let sends = 0;
  await assert.rejects(submitPreservingKey(storage, ticketId, intent, async body => {
    sends++; assert.deepEqual(readPending(storage, ticketId), body); throw Error("response lost after save");
  }));
  const restored = restorePending(storage, memoryStorage(), ticketId);
  assert.deepEqual(restored, intent);
  await assert.rejects(submitPreservingKey(storage, ticketId, { ...intent, requestKey: "another" }, async () => { sends++; }), /접수/);
  assert.equal(sends, 1);
  assert.deepEqual(await submitPreservingKey(storage, ticketId, restored, async body => { sends++; assert.deepEqual(body, intent); return saved; }), saved);
  assert.equal(sends, 2); assert.equal(readPending(storage, ticketId), null);
});
test("legacy session intent migrates durably before removal and conflicting tab intents remain intact", () => {
  const storage = memoryStorage(); const session = memoryStorage(); session.setItem(pendingKey(ticketId), JSON.stringify(intent));
  assert.deepEqual(restorePending(storage, session, ticketId), intent);
  assert.deepEqual(readPending(storage, ticketId), intent); assert.equal(readPending(session, ticketId), null);
  const other = { ...intent, requestKey: "other-tab" }; session.setItem(pendingKey(ticketId), JSON.stringify(other));
  assert.throws(() => restorePending(storage, session, ticketId), /서로 다른/);
  assert.deepEqual(readPending(storage, ticketId), intent); assert.deepEqual(readPending(session, ticketId), other);
  const failing = memoryStorage(); failing.setItem = () => { throw Error("storage unavailable"); };
  assert.throws(() => restorePending(failing, session, ticketId), /unavailable/);
  assert.deepEqual(readPending(session, ticketId), other);
});
test("unreadable or unwritable storage blocks POST without discarding the pending key", async () => {
  for (const failure of ["getItem", "setItem"]) {
    const storage = memoryStorage(); storage[failure] = () => { throw Error("storage unavailable"); }; let sends = 0;
    await assert.rejects(submitPreservingKey(storage, ticketId, intent, async () => { sends++; return saved; }), /unavailable/);
    assert.equal(sends, 0);
  }
  for (const raw of ["broken", "null", JSON.stringify({ ...intent, requestKey: " " }), JSON.stringify({ ...intent, ticketVersion: 0 })]) {
    const storage = memoryStorage(); storage.setItem(pendingKey(ticketId), raw); let sends = 0;
    await assert.rejects(submitPreservingKey(storage, ticketId, intent, async () => { sends++; return saved; }), /접수/);
    assert.equal(sends, 0); assert.equal(storage.getItem(pendingKey(ticketId)), raw);
  }
});
test("malformed or mismatched successful responses retain the exact request metadata", async () => {
  for (const result of [null, {}, { ...saved, ticketId: "other" }, { ...saved, ticketVersion: 3 },
    ...[{ ticketId: "other" }, { requestKey: "other" }, { ticketVersion: 3 }, { previousInvestigationId: null }]
      .map(change => ({ ...saved, input: { ...saved.input, ...change } }))]) {
    const storage = memoryStorage();
    await assert.rejects(submitPreservingKey(storage, ticketId, intent, async () => result), /일치/);
    assert.deepEqual(readPending(storage, ticketId), intent);
  }
});
test("only explicit contract rejections release a pending request; ambiguous HTTP outcomes retain it", async () => {
  for (const [status, code] of [[0, "NETWORK_ERROR"], [401, "UNAUTHENTICATED"], [403, "FORBIDDEN"], [429, "INVESTIGATION_QUEUE_FULL"],
    [500, "INTERNAL_ERROR"], [502, "UPSTREAM_ERROR"], [504, "TIMEOUT"], [400, "INVALID_RESPONSE"], [404, "UNKNOWN_ROUTE"], [409, "UNKNOWN_CONFLICT"]]) {
    const storage = memoryStorage();
    await assert.rejects(submitPreservingKey(storage, ticketId, intent, async () => { throw { status, detail: { code } }; }));
    assert.deepEqual(readPending(storage, ticketId), intent);
  }
  for (const [status, code] of [[400, "INVALID_REQUEST"], [404, "NOT_FOUND"], [409, "TICKET_VERSION_CONFLICT"], [409, "REQUEST_KEY_CONFLICT"]]) {
    const storage = memoryStorage();
    await assert.rejects(submitPreservingKey(storage, ticketId, intent, async () => { throw { status, detail: { code } }; }));
    assert.equal(readPending(storage, ticketId), null);
  }
});
test("responses cannot erase a different tab's intent and browser storage contains no inquiry or evidence", async () => {
  for (const rejected of [false, true]) {
    const storage = memoryStorage(); const other = { ...intent, requestKey: "other-tab" };
    const operation = submitPreservingKey(storage, ticketId, { ...intent, message: "private inquiry", evidence: ["private evidence"] }, async body => {
      assert.deepEqual(body, intent); assert.deepEqual(JSON.parse(storage.getItem(pendingKey(ticketId))), intent);
      storage.setItem(pendingKey(ticketId), JSON.stringify(other));
      if (rejected) throw { status: 409, detail: { code: "TICKET_VERSION_CONFLICT" } };
      return saved;
    });
    if (rejected) await assert.rejects(operation); else await operation;
    assert.deepEqual(readPending(storage, ticketId), other);
  }
});
test("evidence detail must preserve its owned ID, kind, observation and complete source", () => {
  const summary = { evidenceId: "e1", type: "CODE", observedAt: "2026-09-22T01:00:00Z", source: { path: "a.java", buildId: "build-a", startLine: 3, endLine: 4 } };
  assert.equal(matchingEvidence(summary, { ...summary, source: { endLine: 4, startLine: 3, buildId: "build-a", path: "a.java" }, content: "source" }), true);
  for (const mismatch of [{ evidenceId: "other" }, { type: "LOG" }, { observedAt: "other" }, { source: { ...summary.source, buildId: "build-b" } }])
    assert.equal(matchingEvidence(summary, { ...summary, ...mismatch }), false);
});
test("error guidance preserves model configuration/budget versus input, delivery and synchronization meaning", () => {
  assert.match(errorGuidance({ code: "LLM_CONFIGURATION_ERROR", retryable: false }, "investigation"), /운영자/);
  assert.match(errorGuidance({ code: "INVESTIGATION_BUDGET_EXCEEDED", retryable: false }, "investigation"), /초기화되지/);
  assert.match(errorGuidance({ code: "INVALID_REQUEST", retryable: false }, "submission"), /수정·저장/);
  assert.match(errorGuidance({ code: "AGENT_UNAVAILABLE", retryable: true }, "sync"), /마지막/);
  assert.match(errorGuidance({ code: "FUTURE_UNKNOWN", retryable: false }, "investigation"), /기존 조사/);
});
