/*
 * The design kit reads tokens.css itself, so a token added there shows in the kit without a
 * second edit and the kit can never show a value the screens do not use.
 */

export type Token = { name: string; value: string };

/** Every `--name: value;` in the order written. */
export function parseTokens(css: string): Token[] {
  const out: Token[] = [];
  const re = /(--[\w-]+)\s*:\s*([^;]+);/g;
  for (let m = re.exec(css); m !== null; m = re.exec(css)) out.push({ name: m[1], value: m[2].trim() });
  return out;
}

export type ColourGroup = { label: string; tokens: Token[] };

const COLOUR_GROUPS: { label: string; test: RegExp }[] = [
  { label: '바탕: 사이드바 < 목록 < 상세', test: /^--c-(side|canvas|paper)/ },
  { label: '선', test: /^--c-rule/ },
  { label: '글자', test: /^--c-ink/ },
  { label: '강조: 주 동작, 현재 선택, 포커스에만', test: /^--c-(accent|on-accent)/ },
  { label: '상태: 강조색과 따로', test: /^--c-(block|warn)/ },
];

/** Colour tokens in named groups; whatever no group claims goes under 「브라우저가 그리는 면」. */
export function colourGroups(tokens: Token[]): ColourGroup[] {
  const colours = tokens.filter((t) => t.name.startsWith('--c-'));
  const groups = COLOUR_GROUPS.map((g) => ({ label: g.label, tokens: colours.filter((t) => g.test.test(t.name)) }));
  const claimed = new Set(groups.flatMap((g) => g.tokens.map((t) => t.name)));
  groups.push({ label: '브라우저가 그리는 면', tokens: colours.filter((t) => !claimed.has(t.name)) });
  return groups.filter((g) => g.tokens.length > 0);
}

export const byPrefix = (tokens: Token[], prefix: string) => tokens.filter((t) => t.name.startsWith(prefix));

/** Tokens that are not colours, type sizes, spacing, radii or shadows. */
export function otherTokens(tokens: Token[]): Token[] {
  return tokens.filter((t) => !/^--(c|fs|s|r|sh)-/.test(t.name));
}
