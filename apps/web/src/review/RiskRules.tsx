import { useEffect, useId, useRef, useState, type Ref } from 'react';
import { Icon } from '../icons/Icon';
import { reviewsApi, type ReviewSettings, type RiskProposal } from '../reviewsApi';
import { useNarrow } from '../shell/useNarrow';
import { DetailPane } from '../ui/DetailPane';
import { EmptyState } from '../ui/EmptyState';
import { GroupHead, RowSkeleton } from '../ui/Group';
import { ListPane } from '../ui/ListPane';
import { focusRow, tabStop } from '../ui/rowKeys';
import { useAddress } from '../ui/useAddress';
import { useSheet } from '../ui/useSheet';
import { useShownRow } from '../ui/useShownRow';
import { usePoll } from '../usePoll';
import { at } from '../workModel';
import { parsePatterns, rateLabel, sameList } from './reviewModel';

/*
 * The risk path lists (FN-0411; contract v1 "위험 목록을 판정한다"): the organisation's, which every
 * project uses until it sets its own, and each project's. A person reads and edits one pattern per
 * line, sets the sample rate and daily cap, and adds or dismisses the candidates people put forward
 * when they rejected a sample. Every change is the person's and goes to the audit log; AI cannot
 * change these lists. In the work screen's grammar: the list of scopes beside the detail.
 */

export const ORG = 'org';

export type Scope = {
  key: string;
  label: string;
  patterns: string[];
  /** Organisation: it set its own list. Project: it uses the organisation's. */
  flag: boolean;
  proposals: RiskProposal[];
};

export function scopes(s: ReviewSettings): Scope[] {
  return [
    { key: ORG, label: '조직 기본', patterns: s.org.patterns, flag: s.org.customized, proposals: s.proposals.filter((p) => p.project_id === null) },
    ...s.projects.map((p) => ({
      key: p.project.id,
      label: p.project.external_ref ? `${p.project.external_ref} ${p.project.name}` : p.project.name,
      patterns: p.patterns,
      flag: p.inherited,
      proposals: s.proposals.filter((x) => x.project_id === p.project.id),
    })),
  ];
}

/** Where a scope's list comes from, in words. */
export function sourceLine(scope: Scope): string {
  if (scope.key === ORG) return scope.flag ? '조직이 고친 목록' : 'zm-baton의 처음 목록';
  return scope.flag ? '조직 기본 목록을 쓴다' : '이 프로젝트가 고친 목록';
}

export const readView = (hash: string) => {
  const at = hash.indexOf('?');
  return { selected: new URLSearchParams(at < 0 ? '' : hash.slice(at + 1)).get('item') || null };
};

export const writeView = (v: { selected: string | null }) => (v.selected ? `#risk?item=${encodeURIComponent(v.selected)}` : '#risk');

type Props = { onError: (e: unknown) => void; onRead: () => void; api?: typeof reviewsApi };

export function RiskRules({ onError, onRead, api = reviewsApi }: Props) {
  const narrow = useNarrow();
  const [view, setView, , navigation] = useAddress('risk', readView, writeView);
  const [settings, setSettings] = useState<ReviewSettings | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const sheet = useSheet(view.selected, navigation);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const root = useRef<HTMLElement>(null);

  usePoll(
    async (alive, signal) => {
      const s = await api.settings(signal);
      if (!alive()) return;
      setSettings(s);
      setProblem(null);
      onRead();
    },
    15_000,
    [refresh],
    (e) => {
      setProblem(e instanceof Error ? e.message : String(e));
      onError(e);
    },
  );

  const list = settings ? scopes(settings) : [];
  const order = list.map((s) => s.key);
  const current = list.find((s) => s.key === view.selected) ?? null;
  const choose = (id: string | null) => setView((v) => (v.selected === id ? v : { selected: id }));
  useShownRow({ fill: !narrow, follow: false, selected: view.selected, order, filterKey: '', settled: settings !== null, choose });

  function open(id: string) {
    choose(id);
    if (narrow) sheet.show();
    else requestAnimationFrame(() => titleRef.current?.focus());
  }

  const tab = tabStop(order, view.selected);
  const row = (s: Scope) => (
    <li key={s.key}>
      <button
        type="button"
        className="lrow"
        data-row={s.key}
        aria-current={s.key === view.selected ? 'true' : undefined}
        tabIndex={s.key === tab ? 0 : -1}
        onClick={() => (narrow ? open(s.key) : choose(s.key))}
      >
        <span className="lrow-l1">
          <Icon name={s.key === ORG ? 'team' : 'project'} />
          <span className="lrow-title">{s.label}</span>
        </span>
        <span className="lrow-sub">
          <span>{sourceLine(s)}</span>
          <span className="num">패턴 {s.patterns.length}개</span>
          {s.proposals.length > 0 && <span className="ib-ntf is-now num">후보 {s.proposals.length}개</span>}
        </span>
      </button>
    </li>
  );

  return (
    <main className="panes rk" ref={root}>
      <h1 className="sr">위험 목록</h1>
      <ListPane title="위험 목록의 적용 범위" head={{ base: '적용 범위', count: settings ? list.length : null, narrowed: false }} by="조직과 프로젝트" order={order} onSelect={choose} onOpen={open}>
        {settings === null ? (
          problem ? (
            <EmptyState
              kind="error"
              title="위험 목록을 읽지 못했다"
              action={
                <button type="button" className="btn" onClick={() => setRefresh((r) => r + 1)}>
                  다시 읽기
                </button>
              }
            >
              {problem}. 서버가 켜져 있는지 <code>zm-baton status</code>로 본 뒤 다시 읽는다.
            </EmptyState>
          ) : (
            <RowSkeleton rows={3} />
          )
        ) : (
          <>
            <div className="pane-grp">
              <GroupHead mark={<Icon name="team" />} label="조직" count={1} />
              <ul className="pane-rows">{row(list[0])}</ul>
            </div>
            <div className="pane-grp">
              <GroupHead mark={<Icon name="project" />} label="프로젝트" count={list.length - 1} aside={list.length === 1 ? '가져온 프로젝트가 없다' : undefined} />
              {list.length > 1 && <ul className="pane-rows">{list.slice(1).map(row)}</ul>}
            </div>
          </>
        )}
      </ListPane>
      <DetailPane narrow={narrow} open={sheet.open} label="위험 목록 상세" sheetTitle={current?.label ?? '위험 목록'} onClose={sheet.hide} onBack={() => focusRow(root.current, view.selected)}>
        {current && settings ? (
          <ScopeDetail key={current.key} scope={current} settings={settings} api={api} titleRef={titleRef} inSheet={narrow} onSaved={setSettings} onError={onError} />
        ) : settings ? (
          <EmptyState kind="empty" title="고른 범위가 없다">
            목록에서 조직이나 프로젝트를 고르면 그 위험 목록이 여기에 보인다.
          </EmptyState>
        ) : null}
      </DetailPane>
    </main>
  );
}

export function ScopeDetail({
  scope,
  settings,
  api,
  titleRef,
  inSheet,
  onSaved,
  onError,
}: {
  scope: Scope;
  settings: ReviewSettings;
  api: typeof reviewsApi;
  titleRef: Ref<HTMLHeadingElement>;
  inSheet: boolean;
  onSaved: (s: ReviewSettings) => void;
  onError: (e: unknown) => void;
}) {
  const titleId = useId();
  const org = scope.key === ORG;
  const [text, setText] = useState(scope.patterns.join('\n'));
  const [rate, setRate] = useState(String(Math.round(settings.sample_rate * 1000) / 10));
  const [cap, setCap] = useState(String(settings.sample_daily_cap));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  // A list saved elsewhere (or a candidate added) replaces the text only while nothing is being typed.
  const shown = useRef(scope.patterns);
  useEffect(() => {
    if (!sameList(shown.current, scope.patterns) && sameList(parsePatterns(text), shown.current)) setText(scope.patterns.join('\n'));
    shown.current = scope.patterns;
  }, [scope.patterns, text]);

  const patterns = parsePatterns(text);
  const listChanged = !sameList(patterns, scope.patterns);
  const rateNum = Number(rate);
  const capNum = Number(cap);
  const numbersChanged = org && (rateNum / 100 !== settings.sample_rate || capNum !== settings.sample_daily_cap);
  const numbersBad = org && (!Number.isFinite(rateNum) || rateNum < 0 || rateNum > 100 || !Number.isInteger(capNum) || capNum < 0);
  const why = numbersBad ? '표본 비율은 0~100%, 하루 상한은 0 이상의 정수로 쓴다' : !listChanged && !numbersChanged ? '바꾼 것이 없다' : null;

  async function run(what: () => Promise<ReviewSettings>, done: string) {
    setBusy(true);
    try {
      const s = await what();
      onSaved(s);
      setError(null);
      setSaved(done);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSaved(null);
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  function save() {
    if (why) return;
    if (org) {
      const body: { risk_paths?: string[]; sample_rate?: number; sample_daily_cap?: number } = {};
      if (listChanged) body.risk_paths = patterns;
      if (numbersChanged) {
        body.sample_rate = rateNum / 100;
        body.sample_daily_cap = capNum;
      }
      void run(() => api.setOrg(body), '저장했다. 다음에 끝나는 실행 결과부터 쓴다');
    } else {
      void run(() => api.setProject(scope.key, patterns), '저장했다. 이 프로젝트는 이제 자기 목록을 쓴다');
    }
  }

  const reset = org
    ? scope.flag && { label: '처음 목록으로 되돌리기', go: () => api.setOrg({ risk_paths: settings.default_patterns }) }
    : !scope.flag && { label: '조직 기본 목록으로 되돌리기', go: () => api.setProject(scope.key, null) };

  return (
    <article className="dt" aria-labelledby={titleId}>
      {!inSheet && (
        <nav className="dt-crumb" aria-label="위치">
          <span>위험 목록</span>
          <span aria-hidden="true">/</span>
          <span>{org ? '조직' : '프로젝트'}</span>
        </nav>
      )}
      <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
        {scope.label}
      </h2>
      <dl className="dt-facts">
        <div>
          <dt>목록</dt>
          <dd>{sourceLine(scope)}</dd>
        </div>
        <div>
          <dt>패턴</dt>
          <dd className="num">{scope.patterns.length}개</dd>
        </div>
        <div>
          <dt>표본</dt>
          <dd>
            {rateLabel(settings.sample_rate)}, 하루 {settings.sample_daily_cap}건까지
          </dd>
        </div>
      </dl>
      <div className="dt-grid is-solo">
        <div className="dt-main">
          {scope.proposals.length > 0 && (
            <section className="dt-sec">
              <h3>표본 반려에서 올라온 후보</h3>
              <p className="ib-note">사람이 표본을 반려하며 남긴 경로다. 저절로 더하지 않는다. 더하면 이 목록에 들어가고 감사 기록에 남는다.</p>
              <ul className="rk-props">
                {scope.proposals.map((p) => (
                  <li key={p.review_id}>
                    <code>{p.pattern}</code>
                    <span className="ib-sub">
                      {p.work_title ?? '작업 없음'}, {at(p.proposed_at)}
                      {p.note ? `. 반려 이유: ${p.note}` : ''}
                    </span>
                    <span className="wd-acts">
                      <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void run(() => api.proposal(p.review_id, 'add'), `${p.pattern}을(를) 목록에 더했다`)}>
                        목록에 더하기
                      </button>
                      <button type="button" className="btn" disabled={busy} onClick={() => void run(() => api.proposal(p.review_id, 'dismiss'), '후보에서 뺐다')}>
                        후보에서 빼기
                      </button>
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          )}
          <section className="dt-sec">
            <h3>위험 경로 패턴</h3>
            <p className="ib-note">
              {org
                ? '자기 목록을 정하지 않은 프로젝트와 프로젝트 없는 작업에 쓴다. '
                : '이 프로젝트의 작업 결과에 쓴다. 고치면 조직 기본 목록 대신 이 목록을 쓴다. '}
              맡긴 AI 실행의 바뀐 파일이 하나라도 걸리면 그 결과를 사람에게 보낸다. 저장소 뿌리 기준 경로이고, <code>**/</code>는 폴더 여럿, <code>/**</code>는 그
              아래 전부, <code>*</code>는 이름 일부다. 사람만 고칠 수 있다.
            </p>
            <form
              className="rk-edit"
              onSubmit={(e) => {
                e.preventDefault();
                save();
              }}
            >
              <label>
                한 줄에 패턴 하나
                <textarea className="field" value={text} onChange={(e) => setText(e.target.value)} spellCheck={false} aria-describedby={why ? `${titleId}-why` : undefined} />
              </label>
              {org && (
                <div className="rk-nums">
                  <label>
                    표본 비율(%)
                    <input className="field num" inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} />
                  </label>
                  <label>
                    하루 표본 상한(건)
                    <input className="field num" inputMode="numeric" value={cap} onChange={(e) => setCap(e.target.value)} />
                  </label>
                </div>
              )}
              <div className="wd-acts">
                <button type="submit" className="btn btn-primary" disabled={busy || why !== null} aria-busy={busy || undefined}>
                  {org ? '조직 설정 저장' : '이 프로젝트의 목록 저장'}
                </button>
                {reset && (
                  <button type="button" className="btn" disabled={busy} onClick={() => void run(reset.go, '되돌렸다')}>
                    {reset.label}
                  </button>
                )}
              </div>
              {why && (
                <span className="ctl-why" id={`${titleId}-why`}>
                  {why}
                </span>
              )}
              {saved && (
                <span className="ctl-why" role="status">
                  {saved}
                </span>
              )}
              {error && <span className="ctl-err">{error}</span>}
            </form>
          </section>
        </div>
      </div>
    </article>
  );
}
