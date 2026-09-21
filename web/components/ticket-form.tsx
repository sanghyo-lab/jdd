"use client";
import { useState } from "react";
import type { Assignee, Context, Ticket } from "@/lib/api-types";
import { api } from "@/lib/client-api";

export const contextFields = [
  ["orderId", "주문 번호"], ["productId", "상품 번호"], ["customerId", "고객 번호"],
  ["requestId", "요청 번호"], ["checkoutKey", "체크아웃 키"], ["occurredAt", "발생 시각"],
] as const;
export const examples = ["결제는 성공했는데 주문이 결제 대기로 보여요.", "최소 주문금액을 채웠는데 쿠폰이 적용되지 않아요.",
  "정률 쿠폰을 썼는데 할인금액이 0원이에요.", "한 번 주문했는데 주문이 두 개 생겼어요.", "주문을 취소했는데 환불되지 않았어요.",
  "주문을 취소했는데 사용한 쿠폰이 돌아오지 않았어요.", "재고가 1개였는데 2건의 주문이 성공했어요."];
export function contextFromForm(form: FormData, original: Context = {}): Context {
  const context: Context = {};
  for (const [key] of contextFields) {
    const value = String(form.get(key) ?? "");
    if (value.trim() === "") continue;
    context[key] = key === "occurredAt" ? (original.occurredAt && value === localDate(original.occurredAt)
      ? original.occurredAt : new Date(value).toISOString()) : value;
  }
  return context;
}
export function ContextInputs({ context = {} }: { context?: Context }) {
  return <details className="context-inputs"><summary>주문·상품 등 참고 정보 <span className="muted">선택</span></summary>
    <div className="form-grid">{contextFields.map(([key, label]) => <label key={key}>{label}
      <input name={key} type={key === "occurredAt" ? "datetime-local" : "text"} step="any" maxLength={200}
        defaultValue={key === "occurredAt" && context[key] ? localDate(context[key]) : context[key] ?? ""} />
    </label>)}</div><p className="small muted">아는 정보만 입력하세요. 발생 시각은 현재 브라우저의 시간대를 기준으로 저장합니다.</p></details>;
}
function localDate(value: string) {
  const date = new Date(value); return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, -1);
}
export function TicketForm({ assignees }: { assignees: Assignee[] }) {
  const [busy, setBusy] = useState(false); const [error, setError] = useState(""); const [message, setMessage] = useState("");
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy) return; setBusy(true); setError(""); const form = new FormData(event.currentTarget);
    try {
      const ticket = await api<Ticket>("/api/tickets", { method: "POST", body: JSON.stringify({
        title: form.get("title"), message, context: contextFromForm(form), assigneeId: form.get("assigneeId") || null,
      }) });
      window.location.assign("/tickets/" + encodeURIComponent(ticket.ticketId));
    } catch (e) { setError((e as Error).message); setBusy(false); }
  }
  return <form onSubmit={submit} className="stack"><label>제목<input name="title" required maxLength={200} placeholder="문의의 핵심을 짧게 적어 주세요" /></label>
    <label>문의 내용<textarea value={message} onChange={e => setMessage(e.target.value)} required maxLength={10000} rows={5} placeholder="어떤 상황에서 어떤 문제가 생겼나요?" /></label>
    <label className="small">예시 문의<select value="" onChange={e => setMessage(e.target.value)}><option value="">예시를 선택해 작성 시작</option>{examples.map((text, i) => <option key={text} value={text}>{i + 1}. {text}</option>)}</select></label>
    <label>담당자<select name="assigneeId"><option value="">미배정</option>{assignees.map(a => <option key={a.id} value={a.id}>{a.displayName}</option>)}</select></label>
    <ContextInputs />{error && <div role="alert" className="notice error">{error}<p className="small">응답을 받지 못했다면 목록에서 접수 여부를 먼저 확인해 주세요.</p></div>}
    <button className="primary" disabled={busy}>{busy ? "저장 중…" : "문의 등록"}</button></form>;
}
