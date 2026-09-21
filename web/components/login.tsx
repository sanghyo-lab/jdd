"use client";
import { useState } from "react";
import { api } from "@/lib/client-api";

export function Login() {
  const [password, setPassword] = useState(""); const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  async function submit(event: React.FormEvent) {
    event.preventDefault(); if (busy) return; setBusy(true); setError("");
    try { await api("/api/session", { method: "POST", body: JSON.stringify({ password }) }); window.location.assign("/tickets"); }
    catch (e) { setError((e as Error).message); setBusy(false); }
  }
  return <main className="login-page"><div className="login-story"><span className="brand-mark large">jdd</span><p className="eyebrow">팀의 문의 작업실</p>
    <h1>흩어진 문의를 모아,<br />해결까지 함께.</h1><p>고객의 질문과 업무 맥락을 기록하고<br />담당자와 처리 상태를 한곳에서 확인하세요.</p><span className="login-line" /></div>
    <form className="login-card" onSubmit={submit}><p className="eyebrow">팀 전용 공간</p><h2>작업실에 입장하기</h2>
      <p className="muted">운영 담당자가 전달한 접속 암호를 입력해 주세요.</p><label>접속 암호<input autoFocus type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required maxLength={1024} /></label>
      {error && <div className="notice error" role="alert">{error}</div>}<button className="primary" disabled={busy}>{busy ? "확인 중…" : "로그인"}</button>
      <p className="small muted">팀원에게 공유받은 접속 권한으로 이용할 수 있습니다.</p></form></main>;
}
export function Logout() {
  const [busy, setBusy] = useState(false); const [error, setError] = useState("");
  return <div className="logout"><button disabled={busy} className="text-button" onClick={async () => {
    setBusy(true); setError(""); try { await api("/api/session", { method: "DELETE" }); window.location.assign("/login"); }
    catch (e) { setError((e as Error).message); setBusy(false); }
  }}>로그아웃</button>{error && <span role="alert">{error}</span>}</div>;
}
