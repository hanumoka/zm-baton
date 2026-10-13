/// <reference types="node" />
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// Design rules a machine can check (apps/web/DESIGN.md "기계로 막는 것"). Each rule names the
// file and line, so a failing run says exactly what to change. The files are read from disk:
// Vitest turns imported CSS into an empty string, which would make every CSS rule pass.

const SRC = fileURLToPath(new URL('.', import.meta.url));

/** Every screen file under src, subfolders included (icons/, shell/, ui/, kit/), as `dir/file`. */
function sources(): { name: string; text: string }[] {
  return (readdirSync(SRC, { recursive: true }) as string[])
    .map((f) => f.replace(/\\/g, '/'))
    .filter((f) => /\.(css|tsx?)$/.test(f) && !/\.test\.tsx?$/.test(f))
    .map((name) => ({ name, text: readFileSync(join(SRC, name), 'utf8') }));
}

/** "file:line  text" for every line that matches. */
function hits(files: { name: string; text: string }[], pattern: RegExp): string[] {
  const found: string[] = [];
  for (const f of files) {
    f.text.split('\n').forEach((line, i) => {
      if (pattern.test(line)) found.push(`${f.name}:${i + 1}  ${line.trim()}`);
    });
  }
  return found;
}

const files = sources();
const outsideTokens = files.filter((f) => f.name !== 'tokens.css');

describe('design rules', () => {
  it('reads real text from the token file and the screens it checks', () => {
    expect(files.map((f) => f.name)).toContain('tokens.css');
    // Parts in subfolders are checked too, not only the files at the top of src.
    expect(files.map((f) => f.name)).toEqual(expect.arrayContaining(['icons/Icon.tsx', 'shell/shell.css', 'ui/ui.css', 'kit/Kit.tsx']));
    expect(outsideTokens.length).toBeGreaterThan(5);
    // A rule that sees empty files passes for nothing; make that a failure.
    for (const f of files) expect(f.text.length, f.name).toBeGreaterThan(0);
    expect(files.find((f) => f.name === 'tokens.css')?.text).toContain('--c-accent');
  });

  it('writes colours only in tokens.css', () => {
    expect(hits(outsideTokens, /#[0-9a-fA-F]{3,8}\b|\b(rgb|rgba|hsl|hsla|oklch)\(/)).toEqual([]);
  });

  it('writes font sizes in px only in tokens.css', () => {
    expect(hits(outsideTokens, /font-size:\s*\d+(\.\d+)?px/)).toEqual([]);
  });

  it('uses no gradients', () => {
    expect(hits(files, /(linear|radial|conic)-gradient\(/)).toEqual([]);
  });

  it('marks nothing with a coloured left border thicker than 1px', () => {
    expect(hits(files, /border-left(-width)?:\s*([2-9]|\d{2,})px/)).toEqual([]);
  });

  it('uses no all-caps labels and no transition on every property', () => {
    expect(hits(files, /text-transform:\s*uppercase|transition:\s*all\b/)).toEqual([]);
  });

  it('puts no emoji where an icon belongs', () => {
    expect(hits(files.filter((f) => /\.tsx?$/.test(f.name)), /\p{Extended_Pictographic}/u)).toEqual([]);
  });
});
