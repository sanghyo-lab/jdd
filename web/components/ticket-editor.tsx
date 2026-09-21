"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import type { Assignee, Ticket, TicketDetail } from "@/lib/api-types";
import { api, ApiFailure, dateLabel, statusLabel } from "@/lib/client-api";
import { ContextInputs, contextFromForm } from "./ticket-form";
import { AnalysisWorkspace } from "./analysis-workspace";

export function TicketEditor({ id }: { id: string }) {
  const [detail, setDetail] = useState<TicketDetail | null>(null); const [assignees, setAssignees] = useState<Assignee[]>([]);
  const [error, setError] = useState(""); const [conflict, setConflict] = useState<Ticket | null>(null);
  const [conflictPending, setConflictPending] = useState(false);
  const [busy, setBusy] = useState(false); const [notice, setNotice] = useState(""); const [revision, setRevision] = useState(0);
  const path = "/api/tickets/" + encodeURIComponent(id);
  useEffect(() => {
    const controller = new AbortController(); setError("");
    Promise.all([api<TicketDetail>(path, { signal: controller.signal }), api<{ items: Assignee[] }>("/api/assignees", { signal: controller.signal })])
      .then(([ticket, people]) => { setDetail(ticket); setAssignees(people.items); setConflict(null); setConflictPending(false); })
      .catch(e => { if (e.name !== "AbortError") setError(e.message); });
    return () => controller.abort();
  }, [path, revision]);
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy || !detail) return; const data = new FormData(event.currentTarget);
    setBusy(true); setError(""); setNotice("");
    try {
      const ticket = await api<Ticket>(path, { method: "PATCH", body: JSON.stringify({ expectedVersion: detail.ticket.version,
        title: data.get("title"), message: data.get("message"), status: data.get("status"),
        assigneeId: data.get("assigneeId") || null, context: contextFromForm(data, detail.ticket.context) }) });
      setDetail({ ...detail, ticket }); setConflict(null); setConflictPending(false); setNotice("변경 내용을 저장했습니다.");
    } catch (e) {
      setError((e as Error).message);
      if (e instanceof ApiFailure && e.detail.code === "TICKET_VERSION_CONFLICT") {
        setConflictPending(true);
        try { setConflict((await api<TicketDetail>(path)).ticket); } catch { /* Preserve the draft and original conflict. */ }
      }
    } finally { setBusy(false); }
  }
  return <><Link className="back-link" href="/tickets">← 문의 목록</Link>
    {error && <div className="notice error" role="alert">{error}{!detail && <button onClick={() => setRevision(n => n + 1)}>다시 조회</button>}</div>}
    {!detail ? !error && <div className="empty" role="status">문의를 불러오는 중…</div> : <>
      <div className="page-heading"><div><p className="eyebrow">문의 상세 · 버전 {detail.ticket.version}</p><h1>{detail.ticket.title}</h1><p className="muted">접수 {dateLabel(detail.ticket.createdAt)} · 최근 수정 {dateLabel(detail.ticket.updatedAt)}</p></div><span className={"badge " + detail.ticket.status}>{statusLabel[detail.ticket.status]}</span></div>
      <div className="detail-columns"><section className="panel" id="ticket-input"><div className="panel-heading"><h2>문의와 처리 상태</h2><span className="small muted">담당자가 확인 후 해결 처리</span></div>
        {notice && <div className="notice success" role="status">{notice}</div>}
        {conflictPending && !conflict && <div className="notice warning" role="alert">작성 중인 입력은 유지했습니다. 최신 저장 내용을 불러오지 못했습니다.
          <button disabled={busy} onClick={async () => { setBusy(true); try { setConflict((await api<TicketDetail>(path)).ticket); } catch (e) { setError((e as Error).message); } finally { setBusy(false); } }}>최신 버전 다시 확인</button></div>}
        {conflict && <div className="notice warning" role="alert"><h3>다른 변경이 먼저 저장되었습니다</h3><p>아래 작성 중인 입력은 유지했습니다. 현재 저장된 버전 {conflict.version}과 비교해 주세요.</p>
          <details><summary>최신 저장 내용 확인</summary><h4>{conflict.title}</h4><p className="pre-wrap">{conflict.message}</p><p>상태: {statusLabel[conflict.status]} · 담당자: {assignees.find(a => a.id === conflict.assigneeId)?.displayName ?? "미배정"}</p><pre>{JSON.stringify(conflict.context, null, 2)}</pre></details>
          <button className="secondary" onClick={() => { setDetail({ ...detail, ticket: conflict }); setConflict(null); setConflictPending(false); setError(""); setNotice("최신 저장 내용을 불러왔습니다. 필요한 부분을 다시 수정해 주세요."); }}>작성 중인 입력을 최신 내용으로 교체</button></div>}
        <form className="stack" key={detail.ticket.version} onSubmit={save}>
          <label>제목<input name="title" required maxLength={200} defaultValue={detail.ticket.title} /></label>
          <label>문의 내용<textarea aria-label="문의 내용" name="message" required rows={8} maxLength={10000} defaultValue={detail.ticket.message} /></label>
          <div className="form-grid"><label>업무 상태<select name="status" defaultValue={detail.ticket.status}>{Object.entries(statusLabel).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
            <label>담당자<select name="assigneeId" defaultValue={detail.ticket.assigneeId ?? ""}><option value="">미배정</option>{assignees.map(a => <option key={a.id} value={a.id}>{a.displayName}</option>)}</select></label></div>
          <ContextInputs context={detail.ticket.context} /><button className="primary align-start" disabled={busy || conflictPending}>{busy ? "저장 중…" : "변경 저장"}</button>
        </form></section></div><AnalysisWorkspace ticket={detail.ticket} initialAnalyses={detail.analyses} /></>}
  </>;
}
