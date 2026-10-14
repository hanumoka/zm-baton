import type { ContradictionSide, ContradictionStatus, MasterCheck } from '../contradictionsApi';

/*
 * Document contradiction candidates as plain functions (contract 「문서 모순 후보」): where each side
 * opens, what a candidate's evidence holds and what the master's newest check came to. The first
 * screen, the document detail and the decision detail draw them the same way.
 */

const text = (v: unknown): string | null => (typeof v === 'string' && v.trim() !== '' ? v : null);

/** A candidate's evidence as a first-screen item carries it (its `detail`). */
export type Evidence = {
  reason: string | null;
  sides: ContradictionSide[];
  workItemId: string | null;
  path: 'api' | 'local' | null;
};

export function evidenceOf(detail: Record<string, unknown> | null | undefined): Evidence {
  const raw = Array.isArray(detail?.sides) ? (detail.sides as unknown[]) : [];
  const sides = raw.flatMap((s): ContradictionSide[] => {
    if (typeof s !== 'object' || s === null) return [];
    const r = s as Record<string, unknown>;
    const id = text(r.id);
    if (!id) return [];
    return [
      {
        kind: text(r.kind) ?? '',
        id,
        version_id: text(r.version_id),
        number: typeof r.number === 'number' ? r.number : null,
        ref: text(r.ref),
        title: text(r.title),
        label: text(r.label),
        quote: text(r.quote),
      },
    ];
  });
  const path = detail?.master_path === 'api' || detail?.master_path === 'local' ? detail.master_path : null;
  return { reason: text(detail?.reason), sides, workItemId: text(detail?.work_item_id), path };
}

/** Where a side opens: the document at the version quoted, or the decision (past ones unfolded, since it may have been replaced since). */
export function sideHref(s: Pick<ContradictionSide, 'kind' | 'id' | 'number'>): string {
  const p = new URLSearchParams();
  if (s.kind === 'decision') {
    p.set('past', '1');
    p.set('item', s.id);
    return `#decisions?${p.toString()}`;
  }
  p.set('item', s.id);
  if (s.number !== null) p.set('ver', String(s.number));
  return `#documents?${p.toString()}`;
}

/** 「문서 「제목」 판 3」, 「결정 DEC-0012」: how the server named it, else its kind and id. */
export const sideLabel = (s: ContradictionSide) => s.label ?? `${s.kind === 'decision' ? '결정' : '문서'} ${s.title ?? s.id}`;

/** What a side is called on its own detail screen. */
export const selfLabel = (s: Pick<ContradictionSide, 'kind'>) => (s.kind === 'decision' ? '이 결정' : '이 문서');

export const STATUS_LABELS: Record<ContradictionStatus, string> = {
  open: '판정 전',
  acknowledged: '확인함',
  not_a_conflict: '모순 아님',
  resolved: '해결',
};

/** Which path the master ran on, in words. */
export const pathLabel = (path: 'api' | 'local' | null) =>
  path === 'api' ? '서버가 API 키로 찾았다' : path === 'local' ? '이 PC의 연결 프로그램이 Claude Code로 찾았다' : '찾은 경로 모름';

/** What the master's newest check came to, in one sentence. */
export function checkLine(c: Pick<MasterCheck, 'state' | 'path' | 'reason' | 'outcome'>): string {
  switch (c.state) {
    case 'requested':
      return c.path === 'api' ? '서버가 API로 찾기를 기다린다' : '이 PC의 연결 프로그램(zm-baton agent)이 가져가기를 기다린다';
    case 'running':
      return '마스터가 찾는 중이다';
    case 'done': {
      const kept = c.outcome?.kept ?? 0;
      return kept > 0 ? `후보 ${kept}건을 찾았다` : '어긋나는 곳을 찾지 못했다';
    }
    case 'failed':
      return `찾지 못하고 멈췄다. ${c.reason ?? '이유가 남지 않았다'}`;
    case 'skipped':
      return `찾지 않았다. ${c.reason ?? '이유가 남지 않았다'}`;
    default:
      return c.state;
  }
}
