import { useState } from 'react';
import { usePoll } from './usePoll';
import { workApi, type NewWorkItem, type Project, type Stage, type WorkItem, type WorkItemDetail } from './workApi';
import { WorkDetail, type DetailActions } from './WorkDetail';
import { groupByStage, SKIPPABLE, stageLabel, waitLabel } from './workModel';

type Props = {
  /** A failed read, for the problem list at the top of the page. */
  onError: (e: unknown) => void;
  onRead: () => void;
};

/**
 * Work items (FN-0201). Items imported from zm-docs are copies whose source of truth stays the
 * Git file; they show here and change only by the next import. Work made here is edited here.
 */
export function Work({ onError, onRead }: Props) {
  const [projectId, setProjectId] = useState('');
  const [showClosed, setShowClosed] = useState(false);
  const [q, setQ] = useState('');
  const [items, setItems] = useState<WorkItem[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [skipped, setSkipped] = useState<Stage[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<WorkItemDetail | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [refresh, setRefresh] = useState(0);

  usePoll(
    async (alive, signal) => {
      const [list, projectList, workflow] = await Promise.all([
        workApi.workItems({ projectId: projectId || undefined, open: !showClosed, q: q.trim() || undefined }, signal),
        workApi.projects(signal),
        workApi.workflow(signal),
      ]);
      if (!alive()) return;
      setItems(list);
      setProjects(projectList);
      setSkipped(workflow.skipped_stages);
      onRead();
    },
    5000,
    [projectId, showClosed, q, refresh],
    onError,
  );

  usePoll(
    async (alive, signal) => {
      if (selected === null) return;
      const d = await workApi.detail(selected, signal);
      if (alive()) setDetail(d);
    },
    5000,
    [selected, refresh],
    onError,
  );

  /** Runs one action on the selected item with the version shown; a refusal is shown with its reason. */
  const act = (run: (d: WorkItemDetail) => Promise<WorkItemDetail>) => async () => {
    if (detail === null) return;
    try {
      setDetail(await run(detail));
      setActionError(null);
      setRefresh((r) => r + 1);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
      setRefresh((r) => r + 1); // reload: a 409 usually means someone else changed it
      throw e;
    }
  };
  const swallow = (p: Promise<void>) => p.catch(() => undefined);
  const actions: DetailActions = {
    move: (to, note) => swallow(act((d) => workApi.move(d.item.id, d.item.version, to, note))()),
    update: (fields) => swallow(act((d) => workApi.update(d.item.id, d.item.version, fields))()),
    addWait: (reason, note) => swallow(act((d) => workApi.addWait(d.item.id, d.item.version, reason, note))()),
    removeWait: (reason) => swallow(act((d) => workApi.removeWait(d.item.id, d.item.version, reason))()),
  };

  function select(id: string) {
    setSelected(id);
    setDetail(null);
    setActionError(null);
  }

  return (
    <main className="work">
      <header>
        <h1>작업</h1>
        <p>
          zm-docs에서 가져온 작업(<code>TSK-</code> 번호)은 정본이 Git 파일이라 여기서는 읽기만 한다. 원본을 고친 뒤 <code>zm-baton import zm-docs</code>로
          다시 가져온다. 여기서 만든 작업은 여기서 고친다.
        </p>
        <div className="filters">
          <select value={projectId} onChange={(e) => setProjectId(e.target.value)} aria-label="프로젝트">
            <option value="">모든 프로젝트</option>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {`${p.external_ref ?? ''} ${p.name}`.trim()} ({p.open_work_items})
              </option>
            ))}
          </select>
          <input className="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="제목·번호·다음 행동 찾기" aria-label="찾기" />
          <label className="inline">
            <input type="checkbox" checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} /> 완료·취소도 보기
          </label>
          <button onClick={() => setCreating((c) => !c)}>{creating ? '새 작업 닫기' : '새 작업'}</button>
        </div>
        <WorkflowSetting
          skipped={skipped}
          onChange={async (next) => {
            try {
              setSkipped((await workApi.setWorkflow(next)).skipped_stages);
              setRefresh((r) => r + 1);
            } catch (e) {
              onError(e);
            }
          }}
        />
      </header>
      {creating && (
        <NewWork
          projects={projects}
          defaultProject={projectId}
          onCreate={async (body) => {
            try {
              const created = await workApi.create(body);
              setCreating(false);
              setRefresh((r) => r + 1);
              select(created.id);
            } catch (e) {
              onError(e);
            }
          }}
        />
      )}
      <div className="columns">
        <WorkList items={items} skipped={skipped} selected={selected} onSelect={select} />
        {detail ? (
          <WorkDetail detail={detail} projects={projects} skipped={skipped} actions={actions} error={actionError} />
        ) : (
          <p className="empty">{selected ? '불러오는 중…' : '왼쪽에서 작업을 고른다.'}</p>
        )}
      </div>
    </main>
  );
}

/** Work items per stage, open stages always shown so an empty one is visible as empty. */
export function WorkList({
  items,
  skipped = [],
  selected,
  onSelect,
}: {
  items: WorkItem[];
  skipped?: Stage[];
  selected: string | null;
  onSelect: (id: string) => void;
}) {
  if (items.length === 0) {
    return (
      <p className="empty">
        작업이 없다. 오른쪽 위 「새 작업」으로 만들거나, zm-docs 체크아웃에서 <code>zm-baton import zm-docs --apply</code>로 가져온다.
      </p>
    );
  }
  return (
    <nav className="stages" aria-label="작업 목록">
      {groupByStage(items, skipped).map((g) => (
        <section key={g.stage}>
          <h2>
            {g.label} <small>{g.items.length}</small>
          </h2>
          <ul className="list">
            {g.items.map((i) => (
              <li key={i.id}>
                <button className={i.id === selected ? 'selected' : undefined} onClick={() => onSelect(i.id)}>
                  <span>
                    {i.external_ref && <code>{i.external_ref}</code>} {i.title}
                    {i.wait_reasons.length > 0 && <em className="wait"> {i.wait_reasons.map(waitLabel).join(', ')}</em>}
                  </span>
                  <small>{i.project?.external_ref ?? ''}</small>
                </button>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </nav>
  );
}

/** The organisation uses or skips each review stage; a skipped stage is passed through by the server. */
function WorkflowSetting({ skipped, onChange }: { skipped: Stage[]; onChange: (next: Stage[]) => void }) {
  return (
    <p className="setting">
      검토 단계:
      {SKIPPABLE.map((s) => (
        <label key={s} className="inline">
          <input
            type="checkbox"
            checked={!skipped.includes(s)}
            onChange={(e) => onChange(e.target.checked ? skipped.filter((x) => x !== s) : [...skipped, s])}
          />{' '}
          {stageLabel(s)} 쓰기
        </label>
      ))}
      <small> 끄면 그 단계는 서버가 지나간다.</small>
    </p>
  );
}

function NewWork({ projects, defaultProject, onCreate }: { projects: Project[]; defaultProject: string; onCreate: (b: NewWorkItem) => void }) {
  const [title, setTitle] = useState('');
  const [nextAction, setNextAction] = useState('');
  const [project, setProject] = useState(defaultProject);
  const [stage, setStage] = useState<'draft' | 'ready'>('ready');
  return (
    <form
      className="new"
      onSubmit={(e) => {
        e.preventDefault();
        if (title.trim() === '') return;
        onCreate({
          title: title.trim(),
          next_action: nextAction.trim() || undefined,
          project_id: project || undefined,
          initial_stage: stage,
        });
      }}
    >
      <label>
        제목
        <input value={title} onChange={(e) => setTitle(e.target.value)} required autoFocus />
      </label>
      <label>
        다음 행동
        <input value={nextAction} onChange={(e) => setNextAction(e.target.value)} />
      </label>
      <label>
        프로젝트
        <select value={project} onChange={(e) => setProject(e.target.value)}>
          <option value="">없음</option>
          {projects.map((p) => (
            <option key={p.id} value={p.id}>
              {`${p.external_ref ?? ''} ${p.name}`.trim()}
            </option>
          ))}
        </select>
      </label>
      <label>
        처음 단계
        <select value={stage} onChange={(e) => setStage(e.target.value as 'draft' | 'ready')}>
          <option value="ready">수행 준비</option>
          <option value="draft">초안</option>
        </select>
      </label>
      <button type="submit" disabled={title.trim() === ''}>
        만들기
      </button>
    </form>
  );
}
