export const submissionLabel = { PENDING: "전달 대기", SUBMITTED: "전달 완료", FAILED: "전달 실패" };
export const investigationLabel = { QUEUED: "조사 대기", RUNNING: "조사 중", COMPLETED: "조사 완료", NEEDS_INPUT: "추가 정보 필요", FAILED: "조사 실패" };
export const supportLabel = { SUPPORTED: "근거 있음", PARTIAL: "일부 근거", UNVERIFIED: "미확인" };
export function shouldPoll(view, now = Date.now()) {
  if (!view || view.syncError?.code === "AGENT_OBSERVATION_EXPIRED") return false;
  if (view.submissionStatus === "FAILED") return false;
  if (view.investigation && !["QUEUED", "RUNNING"].includes(view.investigation.status)) return false;
  return now - Date.parse(view.createdAt) < 14 * 60 * 1000;
}
export function retryBody(view) {
  return { requestKey: view.input.requestKey, ticketVersion: view.input.ticketVersion,
    previousInvestigationId: view.input.previousInvestigationId ?? null };
}
/** @param {{version: number}} ticket @param {string|null} previousInvestigationId @param {string} key */
export function newRequest(ticket, previousInvestigationId = null, key = crypto.randomUUID()) {
  return { requestKey: key, ticketVersion: ticket.version, previousInvestigationId };
}
export function pendingKey(ticketId) { return "jdd-analysis-request:" + ticketId; }
export function readPending(storage, ticketId) {
  try {
    const value = JSON.parse(storage.getItem(pendingKey(ticketId)) ?? "null");
    if (!value || typeof value.requestKey !== "string" || !value.requestKey || !Number.isSafeInteger(value.ticketVersion) || value.ticketVersion < 1
      || !(value.previousInvestigationId === null || typeof value.previousInvestigationId === "string")) return null;
    return value;
  } catch { return null; }
}
function ordered(value) {
  if (Array.isArray(value)) return value.map(ordered);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map(k => [k, ordered(value[k])]));
  return value;
}
export function matchingEvidence(summary, detail) {
  return !!summary && detail?.evidenceId === summary.evidenceId && detail.type === summary.type
    && detail.observedAt === summary.observedAt && JSON.stringify(ordered(detail.source)) === JSON.stringify(ordered(summary.source));
}
export function errorGuidance(error, scope) {
  if (scope === "submission" && !error.retryable)
    return "저장된 분석 입력을 확인하세요. 잘못된 정보는 문의에서 수정·저장한 뒤 새 분석을 요청하세요. 기존 요청의 입력은 바뀌지 않습니다.";
  if (scope === "submission" && error.code === "INVESTIGATION_QUEUE_FULL")
    return "조사 대기열이 가득 찼습니다. 서버는 정해진 횟수만큼 재전송합니다. 전달 실패가 확정되면 잠시 후 같은 요청으로 다시 시도하세요.";
  if (scope === "submission") return "접수 여부가 불확실할 수 있습니다. 같은 요청으로 다시 전송하면 기존 조사에 연결됩니다.";
  if (scope === "sync") return "마지막으로 확인한 조사 상태와 근거를 유지합니다. 다시 조회해도 새 조사를 실행하지 않습니다.";
  if (error.code === "LLM_CONFIGURATION_ERROR") return "운영자의 모델·인증 설정 확인이 필요합니다. 문의 정보가 부족하다는 뜻은 아닙니다.";
  if (error.code === "INVESTIGATION_BUDGET_EXCEEDED") return "허용된 사용량과 실행 범위를 확인해야 합니다. 새 조사나 재시작으로 한도가 초기화되지 않습니다.";
  if (error.code === "LLM_UNAVAILABLE") return "모델 연결에 일시적인 문제가 있습니다. 실행 범위를 확인한 뒤 새 분석을 요청할 수 있습니다.";
  return "기존 조사와 근거를 보존합니다. 원인을 확인한 뒤 필요한 경우 새 분석을 요청하세요.";
}
