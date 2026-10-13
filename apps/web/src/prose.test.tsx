import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { linkTarget, Prose } from './ui/Prose';

const draw = (text: string, ids: Record<string, string> = {}) =>
  renderToStaticMarkup(<Prose text={text} resolveDecision={(ref) => ids[ref] ?? null} onJump={() => {}} />);

describe('linkTarget', () => {
  it('reads a decision number from a decision file, with or without a folder and section', () => {
    expect(linkTarget('DEC-0048-resume-running-work-after-account-switch.md')).toEqual({ kind: 'decision', ref: 'DEC-0048' });
    expect(linkTarget('../governance/decisions/DEC-0015-job-change-as-top-goal.md#결과')).toEqual({ kind: 'decision', ref: 'DEC-0015' });
    expect(linkTarget('DEC-0007.md')).toEqual({ kind: 'decision', ref: 'DEC-0007' });
  });

  it('keeps web links and treats every other address as a file path', () => {
    expect(linkTarget('https://example.com/a')).toEqual({ kind: 'web', href: 'https://example.com/a' });
    expect(linkTarget('../projects/zm-baton/index.md')).toEqual({ kind: 'path', path: '../projects/zm-baton/index.md' });
    expect(linkTarget('DEC-00480-x.md')).toEqual({ kind: 'path', path: 'DEC-00480-x.md' });
    expect(linkTarget(undefined)).toEqual({ kind: 'path', path: '' });
  });
});

describe('Prose', () => {
  it('draws the marks as text people read: bold, nested lists, code and tables', () => {
    const html = draw('1. **목적은 유지한다.** 설명\n   1. 안쪽 항목\n\n`zm-baton import`\n\n| 역할 | 사람 |\n|---|---|\n| approver | 소유자 |');
    expect(html).toContain('<strong>목적은 유지한다.</strong>');
    expect(html).toMatch(/<ol>\s*<li>[\s\S]*<ol>\s*<li>안쪽 항목<\/li>/);
    expect(html).toContain('<code>zm-baton import</code>');
    expect(html).toContain('<th>역할</th>');
    expect(html).not.toContain('**');
  });

  it('opens a linked decision here when this screen has read it, and leaves it as text when not', () => {
    const read = draw('[DEC-0048](DEC-0048-resume.md)', { 'DEC-0048': 'dcs_48' });
    expect(read).toContain('href="#decisions?item=dcs_48"');
    const unread = draw('[DEC-0048](DEC-0048-resume.md)');
    expect(unread).not.toContain('<a');
    expect(unread).toContain('title="DEC-0048"');
  });

  it('opens web links in a new tab and keeps checkout paths as text with the path on hover', () => {
    expect(draw('[문서](https://example.com)')).toContain('target="_blank" rel="noreferrer"');
    const path = draw('[홈](../projects/zm-baton/index.md)');
    expect(path).not.toContain('<a');
    expect(path).toContain('title="../projects/zm-baton/index.md"');
  });

  it('draws no raw HTML, no script address and no image from a record', () => {
    const html = draw('<script>alert(1)</script>\n\n[x](javascript:alert(1))\n\n![그림 설명](https://example.com/a.png)');
    expect(html).not.toContain('<script');
    expect(html).not.toContain('javascript:');
    expect(html).not.toContain('<img');
    expect(html).toContain('그림 설명');
  });
});
