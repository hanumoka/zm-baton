/**
 * What failed on the Web screen, one entry per source. A source's entry goes only when that
 * same source succeeds, so a working first-screen poll does not hide a failing session list.
 */
export type Source = 'inbox' | 'sessions' | 'judge' | 'work' | 'decisions' | 'documents' | 'accounts' | 'audit';
export type Problems = Partial<Record<Source, string>>;

const LABELS: Record<Source, string> = {
  inbox: '「나를 기다리는 것」을 읽지 못했다',
  sessions: '「세션 현황」을 읽지 못했다',
  judge: '알림 처리를 서버에 보내지 못했다',
  work: '「작업」을 읽거나 바꾸지 못했다',
  decisions: '「결정」을 읽지 못했다',
  documents: '「문서」를 읽거나 바꾸지 못했다',
  accounts: '「AI 계정」을 읽거나 바꾸지 못했다',
  audit: '「감사 기록」을 읽지 못했다',
};

export function fail(p: Problems, source: Source, e: unknown): Problems {
  return { ...p, [source]: e instanceof Error ? e.message : String(e) };
}

export function clear(p: Problems, source: Source): Problems {
  if (p[source] === undefined) return p;
  const next = { ...p };
  delete next[source];
  return next;
}

/** One line per failing source, in a fixed order. */
export function lines(p: Problems): string[] {
  return (Object.keys(LABELS) as Source[]).filter((s) => p[s] !== undefined).map((s) => `${LABELS[s]}: ${p[s]}`);
}
