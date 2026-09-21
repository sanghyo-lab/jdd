"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import type { Assignee, Ticket } from "@/lib/api-types";
import { api, dateLabel, statusLabel } from "@/lib/client-api";
import { TicketForm } from "./ticket-form";
export function TicketList() {
  const [items, setItems] = useState<Ticket[]>([]); const [assignees, setAssignees] = useState<Assignee[]>([]);
  const [status, setStatus] = useState(""); const [assignee, setAssignee] = useState(""); const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(true); const [error, setError] = useState(""); const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError("");
    const query = new URLSearchParams({ limit: "20", offset: String(offset) });
    if (status) query.set("status", status); if (assignee) query.set("assigneeId", assignee);
    Promise.all([api<{ items: Ticket[] }>("/api/tickets?" + query, { signal: controller.signal }),
      api<{ items: Assignee[] }>("/api/assignees", { signal: controller.signal })])
      .then(([tickets, people]) => { setItems(tickets.items); setAssignees(people.items); })
      .catch(e => { if (e.name !== "AbortError") setError(e.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [status, assignee, offset, revision]);
  return <><div className="page-heading"><div><p className="eyebrow">고객의 질문에서 해결까지</p><h1>문의 티켓</h1><p className="muted">맥락을 남기고, 다음 할 일을 함께 확인하세요.</p></div>
    <div className="heading-actions"><a className="primary button-link" href="#new-ticket">새 문의 작성</a><button className="secondary" onClick={() => setRevision(n => n + 1)} disabled={loading}>목록 새로고침</button></div></div>
    <div className="ticket-columns"><section className="panel list-panel" aria-label="티켓 목록"><div className="panel-heading"><h2>팀의 문의</h2><span className="small muted">최근 접수 순</span></div>
      <div className="filters"><label>업무 상태<select value={status} onChange={e => { setStatus(e.target.value); setOffset(0); }}><option value="">전체 상태</option>{Object.entries(statusLabel).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
        <label>담당자<select value={assignee} onChange={e => { setAssignee(e.target.value); setOffset(0); }}><option value="">전체 담당자</option>{assignees.map(a => <option key={a.id} value={a.id}>{a.displayName}</option>)}</select></label></div>
      {error && <div className="notice error" role="alert">{error} <button onClick={() => setRevision(n => n + 1)}>다시 조회</button></div>}
      {loading ? <div className="empty" role="status">문의 목록을 불러오는 중…</div> : error ? null : items.length === 0 ?
        <div className="empty"><span className="empty-icon">＋</span><h3>{status || assignee || offset ? "조건에 맞는 문의가 없습니다" : "첫 번째 문의를 남겨 주세요"}</h3><p>새 문의를 등록하면 이곳에서 처리 흐름을 확인할 수 있어요.</p></div> :
        <ul className="ticket-list">{items.map(ticket => <li key={ticket.ticketId}><Link href={"/tickets/" + encodeURIComponent(ticket.ticketId)}>
          <div className="ticket-row"><span className={"badge " + ticket.status}>{statusLabel[ticket.status]}</span><span className="small muted">{dateLabel(ticket.createdAt)}</span></div>
          <h3>{ticket.title}</h3><p className="excerpt">{ticket.message}</p><div className="ticket-row small muted"><span>{assignees.find(a => a.id === ticket.assigneeId)?.displayName ?? "미배정"}</span><span>버전 {ticket.version} <span aria-hidden="true">↗</span></span></div>
        </Link></li>)}</ul>}
      <div className="pagination"><button className="secondary" disabled={loading || offset === 0} onClick={() => setOffset(n => Math.max(0, n - 20))}>이전</button><span>{offset / 20 + 1} 페이지</span><button className="secondary" disabled={loading || items.length < 20 || !!error} onClick={() => setOffset(n => n + 20)}>다음</button></div>
    </section><aside id="new-ticket" className="panel create-panel"><div className="panel-heading"><h2>새 문의</h2><span className="small muted">등록 후 내용 수정 가능</span></div><TicketForm assignees={assignees} /></aside></div></>;
}
