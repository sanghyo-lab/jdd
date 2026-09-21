"use client";
import { useEffect, useRef, useState } from "react";
import type { AnalysisRequest, AnalysisSummary, AnalysisView, ApiError, Ticket } from "@/lib/api-types";
import { api, ApiFailure, dateLabel } from "@/lib/client-api";
import { errorGuidance, investigationLabel, newRequest, pendingKey, readPending, retryBody, shouldPoll, submissionLabel } from "@/lib/analysis-state.mjs";
import { EvidenceLinks, InvestigationReport } from "./investigation-report";
import { EvidencePanel } from "./evidence-panel";

function ErrorNotice({ error, scope }: { error: ApiError; scope: "submission" | "sync" | "investigation" }) {
  return <div className={"notice " + (scope === "sync" ? "warning" : "error")} role="alert">
    <strong>{scope === "submission" ? "전달 오류" : scope === "sync" ? "조사 상태 조회 오류" : "조사 실패"}: {error.message}</strong>
    <p className="small">{error.code} · {error.retryable ? "재시도 가능" : "확인 필요"}</p><p>{errorGuidance(error, scope)}</p>
    {scope === "submission" && !error.retryable && <a className="inline-link" href="#ticket-input">문의 입력 수정</a>}
  </div>;
}
export function AnalysisWorkspace({ ticket, initialAnalyses }: { ticket: Ticket; initialAnalyses: AnalysisSummary[] }) {
  const [analyses, setAnalyses] = useState(initialAnalyses); const [selected, setSelected] = useState("");
  const [view, setView] = useState<AnalysisView | null>(null); const [evidenceId, setEvidenceId] = useState<string | null>(null);
  const [pending, setPending] = useState<AnalysisRequest | null>(null); const [busy, setBusy] = useState(false);
  const [error, setError] = useState(""); const [lookupError, setLookupError] = useState(""); const [revision, setRevision] = useState(0);
  const [notice, setNotice] = useState(""); const sending = useRef(false); const selectedRef = useRef(""); const base = "/api/tickets/" + encodeURIComponent(ticket.ticketId);
  const analysisPath = selected ? base + "/analyses/" + encodeURIComponent(selected) : "";
  useEffect(() => {
    const requested = new URL(window.location.href).searchParams.get("analysis");
    const initial = initialAnalyses.some(a => a.analysisRequestId === requested) ? requested! : initialAnalyses[0]?.analysisRequestId ?? "";
    setSelected(initial); selectedRef.current = initial;
    setPending(readPending(window.sessionStorage, ticket.ticketId));
  }, [ticket.ticketId]); // Initial selection is restored once; editing a ticket does not replace the selected history.
  function select(id: string) {
    setSelected(id); selectedRef.current = id; setEvidenceId(null); setNotice("");
    const url = new URL(window.location.href); url.searchParams.set("analysis", id); window.history.replaceState(null, "", url);
  }
  function accept(result: AnalysisView) {
    setView(result);
    setAnalyses(previous => [{ analysisRequestId: result.analysisRequestId, ticketVersion: result.ticketVersion,
      submissionStatus: result.submissionStatus, investigationId: result.investigationId,
      investigationStatus: result.investigation?.status ?? null, createdAt: result.createdAt, updatedAt: result.updatedAt },
      ...previous.filter(a => a.analysisRequestId !== result.analysisRequestId)].sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.analysisRequestId.localeCompare(a.analysisRequestId)));
  }
  useEffect(() => {
    setView(null); setLookupError(""); setEvidenceId(null); if (!analysisPath) return;
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout>; let failed = 0;
    let deadline = Date.now() + 14 * 60 * 1000;
    async function poll() {
      try {
        const result = await api<AnalysisView>(analysisPath, { signal: controller.signal });
        if (controller.signal.aborted) return;
        accept(result); setLookupError(""); failed = 0;
        deadline = Math.min(deadline, Date.parse(result.createdAt) + 14 * 60 * 1000);
        if (shouldPoll(result)) timer = setTimeout(poll, 2000);
      } catch (e) {
        if (controller.signal.aborted) return;
        setLookupError((e as Error).message);
        if (Date.now() < deadline) timer = setTimeout(poll, Math.min(30000, 2000 * 2 ** Math.min(++failed, 4)));
      }
    }
    void poll(); return () => { controller.abort(); clearTimeout(timer); };
  }, [analysisPath, revision]);
  async function send(body: AnalysisRequest, remembered: boolean) {
    if (sending.current) return; sending.current = true; setBusy(true); setError(""); setNotice("");
    if (remembered) {
      // Save intent before POST. An ambiguous response must not silently turn into a new model request.
      try { window.sessionStorage.setItem(pendingKey(ticket.ticketId), JSON.stringify(body)); }
      catch { setError("요청 키를 이 브라우저에 보존할 수 없습니다. 브라우저 저장 설정을 확인해 주세요."); setBusy(false); sending.current = false; return; }
      setPending(body);
    }
    try {
      const result = await api<AnalysisView>(base + "/analyses", { method: "POST", body: JSON.stringify(body) });
      if (remembered) { window.sessionStorage.removeItem(pendingKey(ticket.ticketId)); setPending(null); }
      accept(result); select(result.analysisRequestId); setRevision(n => n + 1);
      setNotice("분석 요청을 저장했습니다. 전달과 조사는 브라우저를 닫아도 서버에서 계속됩니다.");
    } catch (e) {
      setError((e as Error).message);
      // A definitive input/version rejection created no request. Network/5xx uncertainty retains the exact intent.
      if (remembered && e instanceof ApiFailure && [400, 404, 409].includes(e.status)) {
        window.sessionStorage.removeItem(pendingKey(ticket.ticketId)); setPending(null);
        if (e.detail.code === "TICKET_VERSION_CONFLICT") setError("문의가 다른 곳에서 수정되었습니다. 작성 중인 내용을 보존한 뒤 최신 티켓을 다시 확인하세요.");
      }
    } finally { setBusy(false); sending.current = false; }
  }
  async function refresh() {
    if (!analysisPath || busy) return; setBusy(true); setLookupError("");
    try {
      const result = await api<AnalysisView>(analysisPath + "?refresh=true"); if (result.analysisRequestId === selectedRef.current) accept(result);
      setNotice("저장 상태를 확인했습니다. 진행 중인 조사의 최신 결과 조회를 예약했습니다.");
      // refresh returns the old cache immediately; perform one later read even after the observation window.
      await new Promise(resolve => setTimeout(resolve, 1500));
      const updated = await api<AnalysisView>(analysisPath); if (updated.analysisRequestId === selectedRef.current) accept(updated);
    } catch (e) { setLookupError((e as Error).message); } finally { setBusy(false); }
  }
  const investigation = view?.investigation; const evidence = investigation?.evidence ?? [];
  const previous = view?.investigationId ?? null;
  return <section className="analysis-workspace" aria-label="AI 조사"><div className="panel analysis-toolbar">
    <div><p className="eyebrow">AI 조사 · 업무 상태와 별도로 관리</p><h2>문의의 근거를 확인하세요</h2><p className="small muted">현재 저장된 버전 {ticket.version}의 문의로 분석합니다. 수정 중인 내용은 먼저 저장하세요.</p></div>
    <div><button className="primary" disabled={busy || !!pending} onClick={() => void send(newRequest(ticket, previous), true)}>{busy ? "요청 중…" : analyses.length ? "현재 버전으로 새 분석 요청" : "분석 요청"}</button>
      {previous && <p className="small muted">선택한 이전 조사와 연결 · 새 요청으로 실행</p>}</div>
  </div>
    {error && <div className="notice error" role="alert">{error}</div>}{notice && <div className="notice success" role="status">{notice}</div>}
    {pending && <div className="notice warning" role="alert"><strong>접수 응답을 확인하지 못한 요청이 있습니다.</strong><p>버전 {pending.ticketVersion}의 같은 요청으로 접수 여부를 확인하세요. 현재 문의 내용으로 바뀌지 않습니다.</p>
      <button disabled={busy} onClick={() => void send(pending, true)}>같은 요청으로 접수 확인</button></div>}
    <div className="analysis-columns"><aside className="panel analysis-history"><div className="panel-heading"><h2>분석 이력</h2><span className="count">{analyses.length}</span></div>
      {!analyses.length ? <p className="empty">아직 분석 요청이 없습니다.</p> : <ul className="history">{analyses.map(item => <li key={item.analysisRequestId}><button disabled={busy} aria-pressed={selected === item.analysisRequestId} className="history-choice" onClick={() => select(item.analysisRequestId)}>
        <strong>문의 버전 {item.ticketVersion}</strong><span className="small">{submissionLabel[item.submissionStatus]} · {item.investigationStatus ? investigationLabel[item.investigationStatus] : "미접수"}</span><time className="small muted">{dateLabel(item.createdAt)}</time>
      </button></li>)}</ul>}
    </aside><section className="panel analysis-result" aria-label="분석 결과">
      {!selected ? <p className="empty">분석을 요청하면 실제 조사 상태와 저장된 근거가 표시됩니다.</p> : <>
        <div className="panel-heading"><h2>선택한 분석</h2><button disabled={busy} onClick={() => void refresh()}>상태 다시 조회</button></div>
        {lookupError && <div className="notice warning" role="alert"><strong>화면 조회 오류: {lookupError}</strong><p>마지막 조회 내용은 유지합니다. 조회 오류는 조사 실패를 뜻하지 않습니다.</p></div>}
        {!view && !lookupError && <p role="status">저장된 분석을 불러오는 중…</p>}
        {view && <><div className="analysis-state"><span className="badge">전달: {submissionLabel[view.submissionStatus]}</span><span className={"badge state-" + investigation?.status}>조사: {investigation ? investigationLabel[investigation.status] : "미접수"}</span></div>
          <p className="small muted">분석 당시 버전 {view.ticketVersion} · 현재 문의 버전 {ticket.version}<br />마지막 Agent 확인: {view.lastSyncedAt ? dateLabel(view.lastSyncedAt) : "아직 없음"}</p>
          {view.submissionStatus === "PENDING" && <p role="status" className="notice warning">서버가 Agent에 요청을 전달하고 있습니다.</p>}
          {view.submissionError && <ErrorNotice error={view.submissionError} scope="submission" />}
          {view.submissionStatus === "FAILED" && view.submissionError?.retryable && <button className="secondary" disabled={busy} onClick={() => void send(retryBody(view), false)}>같은 요청으로 전달 재시도</button>}
          {view.syncError && <ErrorNotice error={view.syncError} scope="sync" />}
          {investigation?.error && <ErrorNotice error={investigation.error} scope="investigation" />}
          {!shouldPoll(view) && (!investigation || ["RUNNING", "QUEUED"].includes(investigation.status)) && view.submissionStatus !== "FAILED" && <p className="small muted">자동 화면 갱신이 끝났습니다. 상태 다시 조회로 최신 결과를 확인하세요.</p>}
          <details className="analysis-input"><summary>분석 당시 문의와 식별자</summary><p className="pre-wrap">{view.input.message}</p><dl className="source-details">{Object.entries(view.input.context).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value ?? "미입력"}</dd></div>)}<dt>분석 요청 ID</dt><dd>{view.analysisRequestId}</dd><dt>조사 ID</dt><dd>{view.investigationId ?? "미접수"}</dd><dt>요청 키</dt><dd>{view.input.requestKey}</dd><dt>이전 조사</dt><dd>{view.input.previousInvestigationId ?? "없음"}</dd></dl></details>
          {!!investigation?.progress.length && <details className="tool-progress" open={!investigation.report}><summary>실제 도구 실행 내역 ({investigation.progress.length})</summary><ol>{investigation.progress.map(item => <li key={item.toolExecutionId}>
            <strong>{item.toolName}</strong> <span className="small">{({ RUNNING: "실행 중", SUCCEEDED: "완료", FAILED: "실패" } as Record<string, string>)[item.status] ?? item.status}</span><p className="small muted">{dateLabel(item.startedAt)}{item.finishedAt && " → " + dateLabel(item.finishedAt)}</p><p>{item.summary}</p>{item.error && <p className="error-text">{item.error.code}: {item.error.message}</p>}<EvidenceLinks ids={item.evidenceIds} evidence={evidence} onSelect={setEvidenceId} />
          </li>)}</ol></details>}
          {investigation?.report && <InvestigationReport report={investigation.report} evidence={evidence} onSelect={setEvidenceId} />}
          {!!evidence.length && <details className="all-evidence"><summary>저장된 모든 근거 ({evidence.length})</summary><EvidenceLinks ids={evidence.map(item => item.evidenceId)} evidence={evidence} onSelect={setEvidenceId} /></details>}
          {!investigation?.report && investigation?.status !== "FAILED" && <p className="empty">조사 보고서는 아직 없습니다.</p>}
        </>}
      </>}
    </section><EvidencePanel path={analysisPath} summary={evidence.find(item => item.evidenceId === evidenceId) ?? null} onClose={() => setEvidenceId(null)} /></div>
  </section>;
}
