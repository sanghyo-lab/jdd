"use client";
import { useEffect, useRef, useState } from "react";
import type { Evidence, EvidenceDetail } from "@/lib/api-types";
import { api, dateLabel } from "@/lib/client-api";
import { matchingEvidence } from "@/lib/analysis-state.mjs";

function value(value: unknown) { return typeof value === "string" ? value : JSON.stringify(value); }
export function EvidencePanel({ path, summary, onClose }: { path: string; summary: Evidence | null; onClose: () => void }) {
  const [detail, setDetail] = useState<EvidenceDetail | null>(null); const [error, setError] = useState("");
  const [revision, setRevision] = useState(0); const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    setDetail(null); setError(""); if (!summary) return;
    const controller = new AbortController();
    api<EvidenceDetail>(path + "/evidence/" + encodeURIComponent(summary.evidenceId), { signal: controller.signal }).then(result => {
      if (!matchingEvidence(summary, result)) throw Error("선택한 조사의 근거와 응답의 출처가 일치하지 않습니다. 분석을 다시 조회해 주세요.");
      setDetail(result);
    }).catch(e => { if (e.name !== "AbortError") setError(e.message); });
    heading.current?.focus({ preventScroll: true }); heading.current?.scrollIntoView({ block: "nearest", behavior: "smooth" });
    return () => controller.abort();
  }, [path, summary, revision]);
  const table = detail?.type === "DATA" && detail.content && typeof detail.content === "object" ? detail.content as { columns: string[]; rows: Record<string, unknown>[] } : null;
  return <aside className="panel evidence-panel" aria-label="근거 원문"><div className="panel-heading"><h2 ref={heading} tabIndex={-1}>근거 원문</h2>{summary && <button className="text-button" onClick={onClose}>닫기</button>}</div>
    {!summary ? <p className="empty">보고서나 도구 실행 내역의 근거를 선택하면 저장된 원문을 확인할 수 있습니다.</p> : <>
      <span className="badge evidence-type">{summary.type}</span><h3 className="evidence-title">{summary.summary}</h3><p className="small muted">관측 {dateLabel(summary.observedAt)}</p>
      <dl className="source-details"><dt>근거 ID</dt><dd>{summary.evidenceId}</dd>{Object.entries(summary.source).map(([key, data]) => <div key={key}><dt>{({ buildId: "실행 빌드", path: "파일", startLine: "시작 줄", endLine: "끝 줄", schema: "스키마", table: "테이블", recordIds: "레코드 ID", queryDescription: "조회 조건", version: "정책 버전", section: "절" } as Record<string, string>)[key] ?? key}</dt><dd>{value(data)}</dd></div>)}</dl>
      {error && <div className="notice error" role="alert">{error}<button onClick={() => setRevision(n => n + 1)}>근거 다시 조회</button></div>}
      {!detail && !error && <p role="status">원문을 불러오는 중…</p>}
      {detail && <>{detail.truncated && <div className="notice warning">일부만 저장된 근거입니다. 표시 범위를 넘어선 판단에는 추가 조회가 필요합니다.</div>}
        {table && Array.isArray(table.columns) && Array.isArray(table.rows) ? <div className="table-scroll" role="region" aria-label="조회한 DB 레코드" tabIndex={0}><table><thead><tr>{table.columns.map(column => <th key={column}>{column}</th>)}</tr></thead><tbody>{table.rows.map((row, i) => <tr key={i}>{table.columns.map(column => <td key={column}>{value(row[column]) ?? "null"}</td>)}</tr>)}</tbody></table>{!table.rows.length && <p className="small">조회된 레코드가 없습니다.</p>}</div>
          : detail.type === "CODE" && typeof detail.content === "string" ? <div className="code-scroll" role="region" aria-label="실행 소스 원문" tabIndex={0}><ol start={Number(detail.source.startLine) || 1}>{detail.content.split("\n").map((line, i) => <li key={i}><code>{line || " "}</code></li>)}</ol></div>
          : <pre className="raw-evidence">{typeof detail.content === "string" ? detail.content : JSON.stringify(detail.content, null, 2)}</pre>}
      </>}
    </>}
  </aside>;
}
