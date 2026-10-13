import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ICONS } from './icons/Icon';
import { Kit } from './kit/Kit';
import { STATES } from './kit/States';
import { colourGroups, otherTokens, parseTokens } from './kit/tokenList';
import tokensCss from './tokens.css?raw';
import { EmptyState } from './ui/EmptyState';
import { GroupHead } from './ui/Group';
import { STAGES } from './workModel';

describe('token list', () => {
  const tokens = parseTokens(tokensCss);

  it('reads every token from tokens.css, in order', () => {
    expect(tokens.length).toBeGreaterThan(40);
    expect(tokens[0].name).toBe('--c-side');
    expect(tokens.find((t) => t.name === '--stroke')?.value).toBe('1.5px');
  });

  it('puts every colour in exactly one group and leaves the rest to 「그 밖의 값」', () => {
    const grouped = colourGroups(tokens).flatMap((g) => g.tokens.map((t) => t.name));
    const colours = tokens.filter((t) => t.name.startsWith('--c-')).map((t) => t.name);
    expect([...grouped].sort()).toEqual([...colours].sort());
    const other = otherTokens(tokens).map((t) => t.name);
    expect(other).toContain('--font-sans');
    expect(other).toContain('--w-rail');
    expect(other).not.toContain('--s-4');
    expect(other).not.toContain('--sh-tip');
  });
});

describe('design kit', () => {
  const html = renderToStaticMarkup(<Kit />);

  it('shows the tokens with their values', () => {
    expect(html).toContain('--c-accent');
    expect(html).toContain('var(--c-accent)');
    expect(html).toContain('--fs-2xl');
    expect(html).toContain('--r-lg');
  });

  it('shows every icon with its name', () => {
    for (const i of ICONS) {
      expect(html, i.name).toContain(`<code>${i.name}</code>`);
      expect(html, i.name).toContain(i.label);
    }
  });

  it('shows six parts in all seven states, held on by class, with reasons for what cannot be used', () => {
    expect(html.match(/class="state-cell"/g)).toHaveLength(6 * STATES.length);
    for (const forced of ['is-hover', 'is-focus', 'is-active']) expect(html).toContain(forced);
    expect(html).toContain('aria-busy="true"');
    expect(html).toContain('aria-invalid="true"');
    expect(html).toContain('class="ctl-why"');
    expect(html).toContain('class="ctl-err"');
  });

  it('shows the seven stage glyphs and the blocked ones, each with its name', () => {
    for (const s of STAGES) expect(html).toContain(`data-stage="${s.stage}"`);
    expect(html.match(/glyph is-block/g)?.length ?? 0).toBeGreaterThanOrEqual(5);
    expect(html).toContain('수행, 막힘');
  });

  it('shows group heads for one person and for a team, three empty states and keys', () => {
    expect(html).toContain('1인 조직');
    expect(html).toContain('팀 조직');
    expect(html).toContain('팀원');
    for (const t of ['아직 만든 작업이 없다', '막힌 작업이 없다', '작업 목록을 읽지 못했다']) expect(html).toContain(t);
    expect(html).toContain('<kbd>Esc</kbd>');
  });
});

describe('parts', () => {
  it('writes a group head with its count and an empty state with its kind', () => {
    expect(renderToStaticMarkup(<GroupHead mark={null} label="AI 세션" count={0} />)).toBe('<h3 class="grp">AI 세션 <span class="n num">0</span></h3>');
    const error = renderToStaticMarkup(<EmptyState kind="error" title="읽지 못했다" />);
    expect(error).toContain('empty-state is-error');
    expect(error).toContain('class="ico es-mark"');
    expect(renderToStaticMarkup(<EmptyState kind="empty" title="비었다" />)).not.toContain('es-mark');
  });
});
