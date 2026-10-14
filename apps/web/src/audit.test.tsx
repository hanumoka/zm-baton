import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { AuditDetail, RetentionPane } from './audit/AuditDetail';
import { AuditChipRow, AuditList } from './audit/AuditList';
import {
  actionLabel,
  actorClass,
  detailRows,
  groupByDay,
  listState,
  NO_FILTERS,
  periodAfter,
  readView,
  targetHref,
  targetOptions,
  writeView,
  type AuditView,
} from './audit/auditView';
import type { AuditEntry, AuditFacets, RetentionView } from './auditApi';
import { FOOT_ITEMS, matchScreen, screenFromHash } from './shell/screens';

// Local times, so the day groups read the same in any zone the tests run in.
const NOW = new Date(2026, 9, 14, 15, 0, 0).getTime();
const local = (day: number, h: number, m = 0) => new Date(2026, 9, day, h, m, 0).toISOString();

const entry = (over: Partial<AuditEntry> = {}): AuditEntry => ({
  id: 'aud_1',
  actor_kind: 'person',
  actor_id: 'usr_owner',
  on_behalf_of_user_id: 'usr_owner',
  action: 'work_item.stage_changed',
  target_kind: 'wrk',
  target_id: 'wrk_0123456789abcdef',
  details: { from: 'ready', to: 'in_progress', path: ['in_progress'], run_id: null, limits: { max_turns: 5 } },
  at: local(14, 13, 4),
  actor_class: 'person',
  target_work_item_id: null,
  ...over,
});

describe('the audit screen in the shell', () => {
  it('opens from its address and the command line, in the sidebar foot', () => {
    expect(screenFromHash('#audit?actor=run&item=aud_1')).toBe('audit');
    expect(matchScreen('감사')?.id).toBe('audit');
    expect(matchScreen('내보내기')?.id).toBe('audit');
    expect(FOOT_ITEMS).toContain('audit');
  });
});

describe('audit view', () => {
  it('keeps the filters, the selection and the open pane in the address, and ignores what it does not know', () => {
    const v: AuditView = { actor: 'run', target: 'wrk', period: '7d', targetId: 'wrk_x', selected: 'aud_1', pane: '' };
    expect(writeView(v)).toBe('#audit?actor=run&target=wrk&period=7d&tid=wrk_x&item=aud_1');
    expect(readView(writeView(v))).toEqual(v);
    expect(writeView({ ...v, pane: 'retention' })).toBe('#audit?actor=run&target=wrk&period=7d&tid=wrk_x&pane=retention');
    expect(readView('#audit?actor=robot&target=Bad%20Kind&period=year')).toEqual({ ...NO_FILTERS, selected: null, pane: '' });
    expect(writeView({ ...NO_FILTERS, selected: null, pane: '' })).toBe('#audit');
  });

  it('starts a period at local midnight, today included', () => {
    expect(periodAfter('', NOW)).toBe('');
    expect(new Date(periodAfter('today', NOW)).getTime()).toBe(new Date(2026, 9, 14).getTime());
    expect(new Date(periodAfter('7d', NOW)).getTime()).toBe(new Date(2026, 9, 8).getTime());
    expect(new Date(periodAfter('30d', NOW)).getTime()).toBe(new Date(2026, 8, 15).getTime());
  });

  it('groups by day, newest first, naming today and yesterday', () => {
    const groups = groupByDay([entry({ id: 'a' }), entry({ id: 'b', at: local(14, 9) }), entry({ id: 'c', at: local(13, 23) }), entry({ id: 'd', at: local(10, 8) })], NOW);
    expect(groups.map((g) => [g.label, g.items.map((e) => e.id)])).toEqual([
      ['오늘 2026-10-14', ['a', 'b']],
      ['어제 2026-10-13', ['c']],
      ['2026-10-10', ['d']],
    ]);
  });

  it('names the actor class, the action and where a target opens', () => {
    expect(actorClass({ actor_kind: 'system', actor_id: 'run_1' })).toBe('run');
    expect(actorClass({ actor_kind: 'system', actor_id: 'server' })).toBe('system');
    expect(actionLabel('decision.accepted')).toBe('결정 채택');
    expect(actionLabel('relay.answered')).toBe('중계: answered');
    expect(actionLabel('something.new')).toBe('something.new');
    expect(targetHref(entry())).toBe('#work?closed=1&item=wrk_0123456789abcdef');
    expect(targetHref(entry({ target_kind: 'dcs', target_id: 'dcs_1' }))).toBe('#decisions?past=1&item=dcs_1');
    expect(targetHref(entry({ target_kind: 'run', target_id: 'run_1', target_work_item_id: 'wrk_9' }))).toBe('#work?closed=1&item=wrk_9');
    expect(targetHref(entry({ target_kind: 'run', target_id: 'run_1' }))).toBeNull();
    expect(targetHref(entry({ target_kind: 'dev', target_id: 'dev_1' }))).toBeNull();
    expect(targetHref(entry({ target_kind: null, target_id: null }))).toBeNull();
  });

  it('reads details as key and value, nested values as JSON', () => {
    expect(detailRows(entry().details)).toEqual([
      { key: 'from', value: 'ready', json: false },
      { key: 'to', value: 'in_progress', json: false },
      { key: 'path', value: 'in_progress', json: false },
      { key: 'run_id', value: '없음', json: false },
      { key: 'limits', value: '{\n  "max_turns": 5\n}', json: true },
    ]);
    expect(detailRows({})).toEqual([]);
  });

  it('tells nothing logged yet from nothing that matches the filters', () => {
    expect(listState(null, null, NO_FILTERS)).toEqual({ kind: 'loading' });
    expect(listState(null, 'HTTP 500', NO_FILTERS)).toEqual({ kind: 'error', message: 'HTTP 500' });
    expect(listState([], null, NO_FILTERS)).toEqual({ kind: 'unused' });
    expect(listState([], null, { ...NO_FILTERS, actor: 'run', period: 'today' })).toEqual({ kind: 'filtered', conditions: ['행위자 실행', '오늘'] });
    expect(listState([entry()], null, NO_FILTERS)).toEqual({ kind: 'rows' });
  });
});

const facets: AuditFacets = { total: 12, actor_classes: { person: 5, session: 3, run: 2, system: 2 }, target_kinds: { wrk: 7, none: 2, mck: 3 } };

describe('audit list and chips', () => {
  it('draws who, what, when and on what in each row, and offers more rows', () => {
    const items = [entry(), entry({ id: 'aud_2', actor_kind: 'system', actor_id: 'run_abcdef0123456789', actor_class: 'run', action: 'run.ended', target_kind: 'run', target_id: 'run_abcdef0123456789' })];
    const html = renderToStaticMarkup(
      <AuditList
        state={{ kind: 'rows' }}
        groups={groupByDay(items, NOW)}
        head={{ base: '감사 기록', count: 2, narrowed: false }}
        selected="aud_1"
        onSelect={() => {}}
        onPick={() => {}}
        onOpen={() => {}}
        onRetention={() => {}}
        retentionOpen={false}
        onClearFilters={() => {}}
        onReload={() => {}}
        more="more"
        onMore={() => {}}
      />,
    );
    expect(html).toContain('작업 단계 넘김');
    expect(html).toContain('실행 끝남');
    expect(html).toContain('>사람<');
    expect(html).toContain('>실행<');
    expect(html).toContain('13:04:00');
    expect(html).toContain('오늘 2026-10-14');
    expect(html).toContain('더 보기');
    expect(html).toContain('보존 규칙과 내보내기');
    expect(html).toContain('aria-current="true"');
  });

  it('shows each chip value with its count, and the target-number chip when one is chosen', () => {
    const html = renderToStaticMarkup(<AuditChipRow filters={{ ...NO_FILTERS, targetId: 'wrk_0123456789abcdef' }} onFilters={() => {}} facets={facets} />);
    expect(html).toContain('사람 (5)');
    expect(html).toContain('실행 (2)');
    expect(html).toContain('작업 (7)');
    expect(html).toContain('모순 찾기 (3)');
    expect(html).toContain('대상 없음 (2)');
    expect(html).toContain('최근 7일');
    expect(html).toContain('대상 번호 wrk_0123456789abcdef 거르기 풀기');
    expect(targetOptions(facets, 'dcs').map((o) => o.kind)).toEqual(['wrk', 'mck', 'none', 'dcs']);
  });
});

describe('audit detail and retention', () => {
  it('shows the facts, the target with its link, and the details as a table', () => {
    const html = renderToStaticMarkup(<AuditDetail entry={entry()} onTarget={() => {}} />);
    expect(html).toContain('작업 단계 넘김');
    expect(html).toContain('<code>work_item.stage_changed</code>');
    expect(html).toContain('href="#work?closed=1&amp;item=wrk_0123456789abcdef"');
    expect(html).toContain('작업 화면에서 열기');
    expect(html).toContain('이 대상의 기록만 보기');
    expect(html).toContain('<code>from</code>');
    expect(html).toContain('<pre class="au-json">');
    expect(html).toContain('감사 기록은 고치지 않는다');
  });

  it('says when a row has no target', () => {
    const html = renderToStaticMarkup(<AuditDetail entry={entry({ action: 'export.downloaded', target_kind: null, target_id: null, details: {} })} onTarget={() => {}} />);
    expect(html).toContain('대상이 없는 기록이다');
    expect(html).toContain('남긴 내용이 없다');
    expect(html).not.toContain('이 대상의 기록만 보기');
  });

  it('downloads the export and shows the retention settings read only', () => {
    const view: RetentionView = {
      kinds: [
        { kind: 'audit_log', label: '감사 기록', days: null, setting: 'ZM_BATON_RETENTION_AUDIT_LOG_DAYS' },
        { kind: 'closed_notices', label: '해결했거나 충돌 아님으로 판정한 충돌 알림', days: 90, setting: 'ZM_BATON_RETENTION_CLOSED_NOTICES_DAYS' },
      ],
      last_run: null,
    };
    const html = renderToStaticMarkup(<RetentionPane view={view} error={null} />);
    expect(html).toContain('href="/api/export" download=""');
    expect(html).toContain('내보내기 파일 받기');
    expect(html).toContain('영원히 둔다');
    expect(html).toContain('90일');
    expect(html).toContain('ZM_BATON_RETENTION_AUDIT_LOG_DAYS');
    expect(html).toContain('아직 지운 적이 없다');
    expect(html).toContain('화면에서 바꾸지 않는다');
    expect(html).not.toMatch(/<input|<select/);
  });
});
