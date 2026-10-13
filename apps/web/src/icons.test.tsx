import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Icon, ICONS } from './icons/Icon';
import { circleFill, FILL, squareFill, StageGlyph } from './icons/StageGlyph';
import { STAGES } from './workModel';

describe('icons', () => {
  it('draws every icon on the 16px grid, hidden from screen readers, with no width or colour of its own', () => {
    for (const { name } of ICONS) {
      const svg = renderToStaticMarkup(<Icon name={name} />);
      expect(svg, name).toContain('viewBox="0 0 16 16"');
      expect(svg, name).toContain('aria-hidden="true"');
      expect(svg, name).toContain('class="ico"');
      // The line width and colour come from `.ico` and the --stroke token, never from the shape.
      expect(svg, name).not.toMatch(/stroke-width|stroke="#|fill="#/);
      expect(svg.match(/<(path|circle|rect)/g)?.length ?? 0, name).toBeGreaterThan(0);
    }
  });

  it('names each icon once and stays under the 30 that would call for an icon set', () => {
    const names = ICONS.map((i) => i.name);
    expect(new Set(names).size).toBe(names.length);
    expect(names.length).toBeLessThanOrEqual(30);
    for (const i of ICONS) expect(i.label.length, i.name).toBeGreaterThan(0);
  });

  it('adds a class without losing the base one', () => {
    expect(renderToStaticMarkup(<Icon name="lock" className="lock" />)).toContain('class="ico lock"');
  });
});

describe('StageGlyph', () => {
  it('fills by place in the order: 2.75, 5.5, 8.25 and 11px in a 16px glyph', () => {
    expect(STAGES.map((s) => FILL[s.stage])).toEqual([0, 0, 2.75, 5.5, 8.25, 11, 0]);
  });

  it('cuts the circle and the square at the fill height', () => {
    expect(circleFill(2.75)).toBe('M3.237 10.75A5.5 5.5 0 0 0 12.763 10.75Z');
    expect(circleFill(5.5)).toBe('M2.5 8A5.5 5.5 0 0 0 13.5 8Z');
    expect(circleFill(8.25)).toBe('M3.237 5.25A5.5 5.5 0 1 0 12.763 5.25Z');
    expect(squareFill(5.5)).toBe('M2.5 8H13.5V11.5A2 2 0 0 1 11.5 13.5H4.5A2 2 0 0 1 2.5 11.5Z');
  });

  it('draws draft dashed, plan review empty, done full and cancelled crossed', () => {
    const draft = renderToStaticMarkup(<StageGlyph stage="draft" />);
    expect(draft).toContain('stroke-dasharray');
    expect(draft).not.toContain('class="fill"');
    expect(renderToStaticMarkup(<StageGlyph stage="plan_review" />)).not.toContain('class="fill"');
    expect(renderToStaticMarkup(<StageGlyph stage="done" />)).toContain('<circle class="fill" cx="8" cy="8" r="7">');
    expect(renderToStaticMarkup(<StageGlyph stage="cancelled" />)).toContain('d="M5 11 11 5"');
  });

  it('turns a blocked open stage into a square filled to the same height, and leaves closed stages alone', () => {
    const blocked = renderToStaticMarkup(<StageGlyph stage="in_progress" blocked />);
    expect(blocked).toContain('class="glyph is-block"');
    expect(blocked).toContain('<rect');
    expect(blocked).toContain(squareFill(5.5));
    expect(renderToStaticMarkup(<StageGlyph stage="plan_review" blocked />)).toContain('<rect');
    expect(renderToStaticMarkup(<StageGlyph stage="done" blocked />)).not.toContain('is-block');
    expect(renderToStaticMarkup(<StageGlyph stage="cancelled" blocked />)).not.toContain('<rect');
  });

  it('is hidden from screen readers, because the stage name is written next to it', () => {
    for (const { stage } of STAGES) expect(renderToStaticMarkup(<StageGlyph stage={stage} />)).toContain('aria-hidden="true"');
  });
});
