import { useState } from 'react';
import { usePoll } from './usePoll';
import { workApi, type Decision, type DecisionDetail, type Project } from './workApi';
import { decisionStatusLabel } from './workModel';

type Props = {
  onError: (e: unknown) => void;
  onRead: () => void;
};

/**
 * Decision records (FN-0604). In stage 1 they come from zm-docs and are changed in Git there
 * (FN-0605), so this screen only reads them: every section, the projects and what replaced what.
 */
export function Decisions({ onError, onRead }: Props) {
  const [projectId, setProjectId] = useState('');
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const [list, setList] = useState<Decision[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<DecisionDetail | null>(null);

  usePoll(
    async (alive, signal) => {
      const [decisions, projectList] = await Promise.all([
        workApi.decisions({ projectId: projectId || undefined, status: status || undefined, q: q.trim() || undefined }, signal),
        workApi.projects(signal),
      ]);
      if (!alive()) return;
      setList(decisions);
      setProjects(projectList);
      onRead();
    },
    15000,
    [projectId, status, q],
    onError,
  );

  usePoll(
    async (alive, signal) => {
      if (selected === null) return;
      const d = await workApi.decision(selected, signal);
      if (alive()) setDetail(d);
    },
    15000,
    [selected],
    onError,
  );

  function select(id: string) {
    setSelected(id);
    setDetail(null);
  }

  return (
    <main className="decisions">
      <header>
        <h1>결정 기록</h1>
        <p>정본은 zm-docs의 Git 파일이라 여기서는 읽기만 한다.</p>
        <div className="filters">
          <select value={projectId} onChange={(e) => setProjectId(e.target.value)} aria-label="프로젝트">
            <option value="">모든 프로젝트</option>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {`${p.external_ref ?? ''} ${p.name}`.trim()}
              </option>
            ))}
          </select>
          <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="상태">
            <option value="">모든 상태</option>
            <option value="accepted">채택</option>
            <option value="superseded">대체됨</option>
            <option value="proposed">제안</option>
            <option value="rejected">기각</option>
          </select>
          <input className="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="번호·질문·결정 찾기" aria-label="찾기" />
          <small>{list.length}건</small>
        </div>
      </header>
      <div className="columns">
        <DecisionList list={list} selected={selected} onSelect={select} />
        {detail ? (
          <DecisionView detail={detail} onSelect={select} />
        ) : (
          <p className="empty">{selected ? '불러오는 중…' : '왼쪽에서 결정을 고른다.'}</p>
        )}
      </div>
    </main>
  );
}

export function DecisionList({ list, selected, onSelect }: { list: Decision[]; selected: string | null; onSelect: (id: string) => void }) {
  if (list.length === 0) {
    return (
      <p className="empty">
        결정 기록이 없다. zm-docs 체크아웃에서 <code>zm-baton import zm-docs --apply</code>로 가져온다.
      </p>
    );
  }
  return (
    <ul className="list" aria-label="결정 목록">
      {list.map((d) => (
        <li key={d.id}>
          <button className={d.id === selected ? 'selected' : undefined} onClick={() => onSelect(d.id)}>
            <span>
              <code>{d.external_ref}</code> {d.question}
              {d.status !== 'accepted' && <em className="wait"> {decisionStatusLabel(d.status)}</em>}
            </span>
            <small>{d.decided_on}</small>
          </button>
        </li>
      ))}
    </ul>
  );
}

export function DecisionView({ detail, onSelect }: { detail: DecisionDetail; onSelect: (id: string) => void }) {
  const d = detail.decision;
  return (
    <section className="detail" aria-label="결정 상세">
      <h2>
        {d.external_ref}: {d.question}
      </h2>
      <p className="meta">
        {decisionStatusLabel(d.status)}
        {d.decided_on && ` · ${d.decided_on}`}
        {d.projects.length > 0 && ` · ${d.projects.map((p) => p.external_ref ?? p.name).join(', ')}`}
      </p>
      {detail.git_ref?.path && (
        <p className="source">
          정본: <code>{detail.git_ref.path}</code>
          {detail.git_ref.commit && <small> (읽은 커밋 {detail.git_ref.commit.slice(0, 10)})</small>}
        </p>
      )}
      {(d.supersedes || d.superseded_by) && (
        <p className="links">
          {d.supersedes && (
            <>
              대체한 결정: <button onClick={() => onSelect(d.supersedes!.id)}>{d.supersedes.external_ref}</button>{' '}
            </>
          )}
          {d.superseded_by && (
            <>
              이 결정을 대체한 결정: <button onClick={() => onSelect(d.superseded_by!.id)}>{d.superseded_by.external_ref}</button>
            </>
          )}
        </p>
      )}
      {detail.sections.map((s, i) => (
        <section key={`${i}-${s.heading}`} className="doc">
          <h3>{s.heading}</h3>
          <div className="body">{s.body}</div>
        </section>
      ))}
    </section>
  );
}
