import { Icon } from '../icons/Icon';
import type { InboxItem } from '../sessionsApi';
import { evidenceOf, pathLabel, sideHref, sideLabel } from '../ui/contradictionView';

/**
 * The evidence of a contradiction candidate on the first screen: the two quoted sentences, each
 * under the record it comes from (a link that opens that document version or decision), why the
 * master thinks they contradict, and where its run record is.
 */
export function ContradictionEvidence({ item }: { item: InboxItem }) {
  const e = evidenceOf(item.detail);
  return (
    <>
      <section className="dt-sec">
        <h3>어긋나 보이는 두 문장</h3>
        <ol className="cx-sides">
          {e.sides.map((s) => (
            <li key={`${s.kind}-${s.id}`} className="cx-side">
              <p className="cx-side-h">
                <Icon name={s.kind === 'decision' ? 'decision' : 'doc'} />
                <a href={sideHref(s)}>{sideLabel(s)}</a>
              </p>
              <blockquote className="cx-quote">{s.quote ?? '인용한 문장이 남지 않았다'}</blockquote>
            </li>
          ))}
        </ol>
      </section>
      <section className="dt-sec">
        <h3>마스터가 본 이유</h3>
        <p className="dt-text">{e.reason ?? '이유가 남지 않았다'}</p>
        <p className="ib-sub cx-run">
          {pathLabel(e.path)}.{' '}
          {e.workItemId && <a href={`#work?closed=1&item=${encodeURIComponent(e.workItemId)}`}>마스터 실행 기록 열기</a>}
        </p>
      </section>
    </>
  );
}
