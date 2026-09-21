"use client";
import type { AnalysisReport, Evidence } from "@/lib/api-types";
import { supportLabel } from "@/lib/analysis-state.mjs";

export function EvidenceLinks({ ids, evidence, onSelect }: { ids: string[]; evidence: Evidence[]; onSelect: (id: string) => void }) {
  return <div className="evidence-links">{ids.map(id => {
    const entry = evidence.find(item => item.evidenceId === id);
    return entry ? <button className="evidence-link" key={id} onClick={() => onSelect(id)} title={entry.summary}>
      <span>{entry.type}</span> {entry.summary}</button> : <span className="small error-text" key={id}>연결되지 않은 근거: {id}</span>;
  })}</div>;
}
export function InvestigationReport({ report, evidence, onSelect }: { report: AnalysisReport; evidence: Evidence[]; onSelect: (id: string) => void }) {
  const links = (ids: string[]) => <EvidenceLinks ids={ids} evidence={evidence} onSelect={onSelect} />;
  return <div className="report"><section className="report-summary"><p className="eyebrow">조사 보고서</p><h3 className="pre-wrap">{report.summary}</h3></section>
    <section><h3>확인된 사실</h3>{report.facts.length ? <ul className="report-list">{report.facts.map(item => <li key={item.id}><p className="pre-wrap">{item.description}</p>{links(item.evidenceIds)}</li>)}</ul> : <p className="muted small">확인된 사실이 없습니다.</p>}</section>
    <section><h3>원인 후보</h3>{report.hypotheses.length ? <ul className="report-list">{report.hypotheses.map(item => <li key={item.id}>
      <span className={"badge support-" + item.supportLevel}>{supportLabel[item.supportLevel]}</span><p className="pre-wrap">{item.description}</p>{links(item.evidenceIds)}
      {!!item.limitations.length && <div className="limitations"><strong>판단의 한계</strong><ul>{item.limitations.map((text, i) => <li key={i}>{text}</li>)}</ul></div>}
    </li>)}</ul> : <p className="muted small">보고서에 원인 후보가 없습니다. 정상으로 확인된 조사에서도 비어 있을 수 있습니다.</p>}</section>
    <section><h3>해당 건의 조치</h3><p className="small muted">아래 내용은 사람이 검토하고 수행할 제안입니다. 업무 데이터에 자동 적용되지 않습니다.</p>
      {report.actions.length ? <ul className="report-list">{report.actions.map(item => <li key={item.id}><p className="pre-wrap">{item.description}</p>{links(item.evidenceIds)}</li>)}</ul> : <p className="muted small">제안된 조치가 없습니다.</p>}</section>
    <section><h3>재발 방지</h3>{report.prevention.length ? <ul className="report-list">{report.prevention.map(item => <li key={item.id}><p className="pre-wrap">{item.description}</p>
      {!!item.targetPaths.length && <div className="small"><strong>검토 대상</strong><ul>{item.targetPaths.map(path => <li key={path}><code>{path}</code></li>)}</ul></div>}{links(item.evidenceIds)}
      {!!item.validationSteps.length && <div className="small"><strong>검증 방법</strong><ol>{item.validationSteps.map((text, i) => <li key={i}>{text}</li>)}</ol></div>}
    </li>)}</ul> : <p className="muted small">제안된 재발 방지 항목이 없습니다.</p>}</section>
    {!!report.missingInformation.length && <section className="notice warning"><h3>추가로 필요한 정보</h3><ul>{report.missingInformation.map((item, i) => <li key={i}><strong>{item.field}</strong>: {item.reason}</li>)}</ul>
      <a className="inline-link" href="#ticket-input">문의와 참고 정보를 수정·저장한 뒤 새 분석 요청</a></section>}
  </div>;
}
