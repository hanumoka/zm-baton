import { useEffect, useId, useState } from 'react';
import { contradictionsApi, type Contradiction, type ContradictionsAbout } from '../contradictionsApi';
import { Icon } from '../icons/Icon';
import { inboxHref } from '../inboxModel';
import { sessionsApi } from '../sessionsApi';
import { at } from '../workModel';
import { checkLine, selfLabel, sideHref, sideLabel, STATUS_LABELS } from './contradictionView';

type Props = {
  /** The document (`doc_…`) or decision (`dcs_…`) shown. */
  about: string;
  /** For tests: what reads the candidates and what sends 「해결」. */
  load?: (id: string, signal?: AbortSignal) => Promise<ContradictionsAbout>;
  resolve?: (noticeId: string) => Promise<unknown>;
};

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * 「열린 모순 후보」 in a document's or decision's detail (contract 「문서 모순 후보」): each open or
 * acknowledged candidate about it, with this record's sentence and the other record's, and the
 * master's newest check. A candidate still on the first screen is judged there; an acknowledged one
 * can be marked resolved here once the records were fixed. Nothing shows when there is no candidate
 * and no check yet.
 */
export function OpenContradictions({ about, load = contradictionsApi.about, resolve = (id) => sessionsApi.judgeNotice(id, 'resolved') }: Props) {
  const [data, setData] = useState<{ about: string; value: ContradictionsAbout } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [round, setRound] = useState(0);

  useEffect(() => {
    const ac = new AbortController();
    setError(null);
    load(about, ac.signal)
      .then((value) => setData({ about, value }))
      .catch((e) => {
        if (!ac.signal.aborted) setError(message(e));
      });
    return () => ac.abort();
  }, [about, load, round]);

  if (error) {
    return (
      <section className="dt-sec">
        <h3>열린 모순 후보</h3>
        <p className="dt-none">모순 후보를 읽지 못했다: {error}</p>
      </section>
    );
  }
  const d = data?.about === about ? data.value : null;
  if (d === null) return null;
  return <ContradictionsView about={about} d={d} resolve={resolve} onResolved={() => setRound((r) => r + 1)} />;
}

/** What [OpenContradictions] draws once the candidates arrived; drawn on its own in tests. */
export function ContradictionsView({ about, d, resolve, onResolved }: { about: string; d: ContradictionsAbout; resolve: (id: string) => Promise<unknown>; onResolved: () => void }) {
  const headId = useId();
  const last = d.checks[0] ?? null;
  if (d.open.length === 0 && last === null) return null;
  return (
    <section className="dt-sec cx" aria-labelledby={headId}>
      <h3 id={headId}>
        열린 모순 후보 <span className="num">{d.open.length}</span>
      </h3>
      {d.open.length === 0 ? (
        <p className="dt-none">
          열린 후보가 없다.{d.closed_count > 0 && ` 판정을 마친 후보 ${d.closed_count}건은 감사 기록에 남아 있다.`}
        </p>
      ) : (
        <ul className="cx-list">
          {d.open.map((c) => (
            <li key={c.id}>
              <Candidate c={c} about={about} resolve={resolve} onResolved={onResolved} />
            </li>
          ))}
        </ul>
      )}
      {last && (
        <p className="cx-check">
          <Icon name="ai" />
          <span>
            {about.startsWith('dcs_') ? '이 결정' : '이 문서'}로 시작한 마지막 모순 찾기{' '}
            <time className="num" dateTime={last.created_at}>
              {at(last.created_at)}
            </time>
            : {checkLine(last)}
            {last.work_item_id && (
              <>
                {' '}
                <a href={`#work?closed=1&item=${encodeURIComponent(last.work_item_id)}`}>실행 기록</a>
              </>
            )}
          </span>
        </p>
      )}
    </section>
  );
}

function Candidate({ c, about, resolve, onResolved }: { c: Contradiction; about: string; resolve: (id: string) => Promise<unknown>; onResolved: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // This record's sentence first, then the other record's.
  const sides = [...c.sides].sort((a, b) => Number(b.id === about) - Number(a.id === about));
  return (
    <div className="cx-item">
      <p className="cx-reason">
        <span className={c.status === 'open' ? 'cx-state is-open' : 'cx-state'}>{STATUS_LABELS[c.status]}</span>
        <span>{c.reason ?? '두 문장이 어긋나 보인다'}</span>
      </p>
      <ol className="cx-sides">
        {sides.map((s) => (
          <li key={`${s.kind}-${s.id}`} className="cx-side">
            <p className="cx-side-h">
              <Icon name={s.kind === 'decision' ? 'decision' : 'doc'} />
              {s.id === about ? (
                <span>
                  {selfLabel(s)}
                  {s.number !== null && ` 판 ${s.number}`}
                </span>
              ) : (
                <a href={sideHref(s)}>{sideLabel(s)}</a>
              )}
            </p>
            <blockquote className="cx-quote">{s.quote ?? '인용한 문장이 남지 않았다'}</blockquote>
          </li>
        ))}
      </ol>
      <p className="cx-acts">
        {c.inbox_item_id ? (
          <a className="btn" href={inboxHref(c.inbox_item_id)}>
            나를 기다리는 것에서 판정
          </a>
        ) : (
          <button
            type="button"
            className="btn"
            disabled={busy}
            aria-busy={busy ? 'true' : undefined}
            onClick={() => {
              setBusy(true);
              resolve(c.id)
                .then(() => {
                  setError(null);
                  onResolved();
                })
                .catch((e) => setError(message(e)))
                .finally(() => setBusy(false));
            }}
          >
            {busy ? '보내는 중' : '고쳤으니 해결로 남기기'}
          </button>
        )}
        <span className="cx-when">
          처음 찾은 때 <time className="num" dateTime={c.created_at}>{at(c.created_at)}</time>
          {c.repeat_count > 0 && <span className="num">, 다시 나온 횟수 {c.repeat_count}</span>}
        </span>
      </p>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>해결로 남기지 못했다: {error}</span>
        </p>
      )}
    </div>
  );
}
