import { useState } from 'react';
import { BUCKETS, groupByBucket, shortId, since } from './inboxModel';
import { sessionsApi, type InboxItem, type NoticeStatus } from './sessionsApi';

type Props = {
  items: InboxItem[];
  now: number;
  /** Called after a judgement so the list refreshes at once. */
  onChanged: () => void;
  onError: (e: unknown) => void;
};

/** The first screen: what waits for me, in the contract's five buckets (FN-0907). */
export function Inbox({ items, now, onChanged, onError }: Props) {
  const groups = groupByBucket(items);
  return (
    <main>
      <header>
        <h1>나를 기다리는 것</h1>
        <p>내가 풀어 줘야 일이 다시 도는 것만 급한 순서로 모은다.</p>
      </header>
      {BUCKETS.map(({ bucket, label, empty }) => {
        const list = groups.get(bucket) ?? [];
        return (
          <section key={bucket} className="bucket" aria-label={label}>
            <h2>
              <span>
                {bucket} {label}
              </span>
              <small>{list.length > 0 ? `${list.length}건` : empty}</small>
            </h2>
            {list.map((item) => (
              <InboxRow key={item.id} item={item} now={now} onChanged={onChanged} onError={onError} />
            ))}
          </section>
        );
      })}
    </main>
  );
}

function InboxRow({ item, now, onChanged, onError }: { item: InboxItem } & Omit<Props, 'items'>) {
  const [reason, setReason] = useState('');
  const [asking, setAsking] = useState(false);
  const [busy, setBusy] = useState(false);

  async function judge(status: NoticeStatus) {
    if (status === 'not_a_conflict' && !reason.trim()) {
      setAsking(true);
      return;
    }
    setBusy(true);
    try {
      await sessionsApi.judgeNotice(item.source_id, status, status === 'not_a_conflict' ? reason.trim() : undefined);
      onChanged();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  const sessions = (item.detail?.sessions as { session_id: string; executor: string; branch?: string | null }[] | undefined) ?? [];
  return (
    <article className="item">
      <div className="row">
        <div className="what">
          <div>{item.summary}</div>
          <small>
            {since(item.waiting_since, now)}
            {item.repeat_count > 0 && ` · 반복 ${item.repeat_count}회`}
            {item.notify === 'immediate' ? ' · 바로 알림' : ' · 묶어 알림'}
          </small>
        </div>
        {item.kind === 'notice' && (
          <div className="acts">
            <button disabled={busy} onClick={() => judge('acknowledged')} title="읽었다는 뜻이다. 알림은 「확인함」으로 남는다">
              확인
            </button>
            <button disabled={busy} onClick={() => judge('not_a_conflict')} title="이유를 남겨야 한다">
              충돌 아님
            </button>
            <button disabled={busy} onClick={() => judge('resolved')} title="변경과 검토를 마쳤다는 뜻이다. 같은 일이 다시 생기면 알림이 다시 열린다">
              해결
            </button>
          </div>
        )}
      </div>
      {item.kind === 'notice' && sessions.length > 0 && (
        <ul className="evidence">
          {sessions.map((s) => (
            <li key={s.session_id}>
              {shortId(s.session_id)} · {s.executor}
              {s.branch ? ` · ${s.branch}` : ''}
            </li>
          ))}
          {typeof item.detail?.path === 'string' && (
            <li>
              파일 <code>{item.detail.path}</code>
            </li>
          )}
        </ul>
      )}
      {item.kind === 'session_waiting' && (
        <p className="hint">
          직접 연 세션이라 여기서 답을 넣지 않는다. 그 세션의 터미널에서 이어서 입력하면 이 항목은 저절로 닫힌다.
          {typeof item.detail?.branch === 'string' && ` (브랜치 ${item.detail.branch})`}
        </p>
      )}
      {asking && (
        <div className="reason">
          <label>
            충돌이 아닌 이유
            <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="예: 같은 파일의 다른 함수를 고친다" />
          </label>
          <button disabled={busy || !reason.trim()} onClick={() => judge('not_a_conflict')}>
            충돌 아님으로 남기기
          </button>
        </div>
      )}
    </article>
  );
}
