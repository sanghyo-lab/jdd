import { test } from "node:test";
import assert from "node:assert/strict";
import { shouldPoll, retryBody, newRequest, pendingKey, readPending, matchingEvidence, errorGuidance } from "../lib/analysis-state.mjs";
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
  const values = new Map(); const storage = { getItem: key => values.get(key) };
  const intent = { requestKey: "once", ticketVersion: 2, previousInvestigationId: null };
  values.set(pendingKey("ticket-a"), JSON.stringify(intent));
  assert.deepEqual(readPending(storage, "ticket-a"), intent); assert.equal(readPending(storage, "ticket-b"), null);
  for (const invalid of ["{", "null", JSON.stringify({ ...intent, ticketVersion: 0 }), JSON.stringify({ ...intent, requestKey: "" })]) {
    values.set(pendingKey("ticket-a"), invalid); assert.equal(readPending(storage, "ticket-a"), null);
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
