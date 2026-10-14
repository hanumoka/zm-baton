import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ContradictionsAbout } from './contradictionsApi';
import { InboxDetail, ReasonForm } from './inbox/InboxDetail';
import { detailTitle, isContradiction, judgementsFor, kindOf, rowTitle, whyMe } from './inbox/inboxView';
import type { InboxItem } from './sessionsApi';
import { checkLine, evidenceOf, sideHref } from './ui/contradictionView';
import { ContradictionsView } from './ui/OpenContradictions';

const T0 = Date.parse('2026-10-14T00:00:00Z');
const iso = (minutes: number) => new Date(T0 + minutes * 60_000).toISOString();

// A contradiction candidate as the server raises it (ContradictionNotices.raise): bucket 5, digest.
const evidence = {
  notice_kind: 'doc_contradiction',
  check_id: 'mck_1',
  run_id: 'run_1',
  work_item_id: 'wrk_m',
  master_path: 'local',
  reason: '마스터의 인증 방식이 다르다',
  sides: [
    { kind: 'document', id: 'doc_b', version_id: 'dvr_b1', number: 1, ref: null, title: '마스터 운영', label: '문서 「마스터 운영」 판 1', quote: '마스터는 개인 구독으로 서버에서 돈다.' },
    { kind: 'decision', id: 'dcs_a', version_id: null, number: null, ref: 'DEC-0095', title: '마스터 계정', label: '결정 DEC-0095', quote: '마스터는 서버에서 API 키로 돈다.' },
  ],
};

const candidate = (over: Partial<InboxItem> = {}): InboxItem => ({
  id: 'inb_x',
  bucket: 5,
  kind: 'notice',
  source_kind: 'ntc',
  source_id: 'ntc_x',
  summary: '모순 후보: 문서 「마스터 운영」 판 1, 결정 DEC-0095',
  detail: evidence,
  wait_kind: null,
  repeat_count: 0,
  waiting_since: iso(0),
  notify: 'digest',
  ...over,
});

describe('a contradiction candidate on the first screen', () => {
  it('names its kind and its two records, and is judged in its own words', () => {
    const item = candidate();
    expect(isContradiction(item)).toBe(true);
    expect(kindOf(item)).toEqual({ mark: 'doc', label: '모순 후보' });
    expect(rowTitle(item, null)).toBe('문서 「마스터 운영」 판 1, 결정 DEC-0095');
    expect(detailTitle(item)).toBe('두 기록이 서로 어긋나 보인다');
    expect(whyMe(item)).toContain('후보일 뿐이고 일을 막지 않는다');
    expect(judgementsFor(item).map((j) => j.label)).toEqual(['확인', '모순 아님', '해결']);
    // A file conflict keeps its words.
    const conflict = candidate({ bucket: 3, detail: { path: 'a.md', sessions: [] } });
    expect(isContradiction(conflict)).toBe(false);
    expect(judgementsFor(conflict).map((j) => j.label)).toEqual(['확인', '충돌 아님', '해결']);
  });

  it('shows both sentences under links to their records, the reason, the run record and the three judgements', () => {
    const html = renderToStaticMarkup(<InboxDetail item={candidate()} sessions={[]} now={T0 + 5 * 60_000} onJudged={() => {}} onError={() => {}} />);
    expect(html).toContain('어긋나 보이는 두 문장');
    expect(html).toContain('마스터는 개인 구독으로 서버에서 돈다.');
    expect(html).toContain('마스터는 서버에서 API 키로 돈다.');
    expect(html).toContain('href="#documents?item=doc_b&amp;ver=1"');
    expect(html).toContain('href="#decisions?past=1&amp;item=dcs_a"');
    expect(html).toContain('마스터의 인증 방식이 다르다');
    expect(html).toContain('이 PC의 연결 프로그램이 Claude Code로 찾았다');
    expect(html).toContain('href="#work?closed=1&amp;item=wrk_m"');
    expect(html).toContain('aria-label="모순 후보 판정"');
    expect(html).toContain('모순 아님');
    expect(html).toContain('묶어 알림');
    expect(html).not.toContain('겹친 파일');
  });

  it('asks why it is not a contradiction before anything is sent', () => {
    const html = renderToStaticMarkup(<ReasonForm what="모순" reason="" onReason={() => {}} busy={false} onSubmit={() => {}} onCancel={() => {}} />);
    expect(html).toContain('모순이 아닌 이유');
    expect(html).toContain('모순 아님으로 남기기');
    expect(html).toContain('「모순 아님」을 받지 않는다');
    expect(html).toMatch(/<button type="submit"[^>]*disabled/);
  });
});

describe('contradiction evidence and checks', () => {
  it('reads the evidence a first-screen item carries and links each side', () => {
    const e = evidenceOf(evidence);
    expect(e.sides.map((s) => s.id)).toEqual(['doc_b', 'dcs_a']);
    expect(e.workItemId).toBe('wrk_m');
    expect(e.path).toBe('local');
    expect(evidenceOf(null)).toEqual({ reason: null, sides: [], workItemId: null, path: null });
    expect(sideHref({ kind: 'document', id: 'doc_b', number: null })).toBe('#documents?item=doc_b');
  });

  it('says what the newest check came to', () => {
    expect(checkLine({ state: 'requested', path: 'local', reason: null, outcome: null })).toContain('zm-baton agent');
    expect(checkLine({ state: 'requested', path: 'api', reason: null, outcome: null })).toContain('API');
    expect(checkLine({ state: 'done', path: 'local', reason: null, outcome: { kept: 2 } })).toBe('후보 2건을 찾았다');
    expect(checkLine({ state: 'done', path: 'local', reason: null, outcome: { kept: 0 } })).toBe('어긋나는 곳을 찾지 못했다');
    expect(checkLine({ state: 'skipped', path: null, reason: '오늘 자동 모순 찾기 5회를 다 썼다.', outcome: null })).toContain('5회를 다 썼다');
    expect(checkLine({ state: 'failed', path: 'local', reason: '연결 프로그램이 8시간 안에 가져가지 않았다.', outcome: null })).toContain('8시간');
  });
});

describe('open contradiction candidates in a document or decision detail', () => {
  const about: ContradictionsAbout = {
    open: [
      {
        id: 'ntc_x', status: 'open', reason: '마스터의 인증 방식이 다르다', sides: evidence.sides, check_id: 'mck_1', run_id: 'run_1', work_item_id: 'wrk_m',
        path: 'local', repeat_count: 1, created_at: iso(-60), last_seen_at: iso(-5), status_by: null, status_at: null, status_note: null, inbox_item_id: 'inb_x',
      },
      {
        id: 'ntc_y', status: 'acknowledged', reason: '보존 기간이 다르다', sides: [evidence.sides[1], { ...evidence.sides[0], quote: '보존은 30일이다.' }],
        check_id: 'mck_0', run_id: 'run_0', work_item_id: 'wrk_0', path: 'api', repeat_count: 0, created_at: iso(-600), last_seen_at: iso(-600),
        status_by: 'usr_owner', status_at: iso(-500), status_note: null, inbox_item_id: null,
      },
    ],
    closed_count: 3,
    checks: [
      { id: 'mck_1', trigger: 'decision_accepted', state: 'done', path: 'local', reason: null, subjects: [], compared: [], work_item_id: 'wrk_m', run_id: 'run_1', api_run_id: null, outcome: { kept: 1, proposed: 2 }, created_at: iso(-61), finished_at: iso(-60) },
    ],
  };

  it('puts this record first, links the other, and sends an open one to the first screen', () => {
    const html = renderToStaticMarkup(<ContradictionsView about="dcs_a" d={about} resolve={async () => {}} onResolved={() => {}} />);
    expect(html).toContain('열린 모순 후보 <span class="num">2</span>');
    // On the decision's own screen its side is 「이 결정」 and the document is the link.
    expect(html.indexOf('이 결정')).toBeLessThan(html.indexOf('마스터는 개인 구독으로 서버에서 돈다.'));
    expect(html).toContain('href="#documents?item=doc_b&amp;ver=1"');
    expect(html).toContain('href="#inbox?item=inb_x"');
    expect(html).toContain('나를 기다리는 것에서 판정');
    expect(html).toContain('고쳤으니 해결로 남기기');
    expect(html).toContain('판정 전');
    expect(html).toContain('확인함');
    expect(html).toContain('다시 나온 횟수 1');
    expect(html).toContain('마지막 모순 찾기');
    expect(html).toContain('후보 1건을 찾았다');
  });

  it('says there is none open when every candidate was judged, and keeps the newest check', () => {
    const html = renderToStaticMarkup(<ContradictionsView about="doc_b" d={{ ...about, open: [] }} resolve={async () => {}} onResolved={() => {}} />);
    expect(html).toContain('열린 후보가 없다');
    expect(html).toContain('판정을 마친 후보 3건');
    expect(html).toContain('실행 기록');
  });
});
