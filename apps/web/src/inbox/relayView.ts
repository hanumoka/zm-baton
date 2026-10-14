import type { IconName } from '../icons/Icon';
import type { InboxItem, RelayAnswer } from '../sessionsApi';

/*
 * What the first screen shows of a relayed permission request or question (contract 「권한 요청과
 * 선택형 질문을 첫 화면으로 중계한다」), as plain functions: whether it can still be answered, how many
 * seconds are left, what state its answer is in, and what a person judges. The screen says
 * 「허용됨」·「거절됨」 only once the answer reached the session's hook (relay 5), and a delivered answer
 * always carries the reminder that the terminal may have answered first (relay 10).
 */

export const RELAY_KINDS = ['permission_request', 'session_question'];

export const isRelay = (item: Pick<InboxItem, 'kind'>) => RELAY_KINDS.includes(item.kind);

/** Whether the server waits for an answer here at all; a request it did not relay is answered in the terminal. */
export const relayed = (item: Pick<InboxItem, 'relay_deadline'>) => typeof item.relay_deadline === 'string' && item.relay_deadline !== '';

/** Seconds until the relay's deadline, never below 0; null when it was not relayed. */
export function secondsLeft(item: Pick<InboxItem, 'relay_deadline'>, now: number): number | null {
  if (!relayed(item)) return null;
  const ms = Date.parse(item.relay_deadline as string) - now;
  return Number.isNaN(ms) ? 0 : Math.max(0, Math.ceil(ms / 1000));
}

/** Open, relayed and before the deadline: a person can still answer here. */
export function canAnswer(item: InboxItem, now: number): boolean {
  const left = secondsLeft(item, now);
  return (item.state ?? 'open') === 'open' && left !== null && left > 0;
}

/** Still in the way of the session: open, or answered and on its way. A finished relay only reports how it ended. */
export const relayActive = (item: Pick<InboxItem, 'state'>) => item.state === undefined || item.state === 'open' || item.state === 'answered';

/** What the person pressed, in words: 허용, 거절 (with its reason) or the chosen options. */
export function answerWords(answer: InboxItem['answer']): string | null {
  if (!answer) return null;
  if (answer.decision === 'allow') return '허용';
  if (answer.decision === 'deny') return typeof answer.reason === 'string' && answer.reason ? `거절(이유: ${answer.reason})` : '거절';
  const answers = answer.answers;
  if (answers && typeof answers === 'object') {
    return Object.values(answers as Record<string, unknown>)
      .map((v) => (Array.isArray(v) ? v.join(', ') : String(v)))
      .join(' / ');
  }
  return null;
}

export type RelayStatus = {
  /** A short word for the row and the facts line. */
  label: string;
  /** What it means for the person, one sentence. */
  note: string | null;
  /** The shape beside it; status is never colour alone (DESIGN.md 「색」). */
  mark: IconName;
  /** `now`: a person can answer; `wait`: on its way; `done`: delivered; `fail`: not delivered; `off`: went to the terminal or closed. */
  tone: 'now' | 'wait' | 'done' | 'fail' | 'off';
};

const NOT_RELAYED: Record<string, { label: string; note: string }> = {
  secret_suspected: { label: '터미널에서 묻는 중', note: '도구 입력에 비밀값으로 보이는 것이 있어 내용을 서버로 보내지 않았다. 그 세션의 터미널에서 확인하고 답한다.' },
  too_large: { label: '터미널에서 묻는 중', note: '도구 입력이 길어 전체를 보여 줄 수 없어 중계하지 않았다. 그 세션의 터미널에서 확인하고 답한다.' },
  not_visible: { label: '터미널에서 묻는 중', note: '질문이 왔을 때 첫 화면이 보이지 않아 중계하지 않았다. 그 세션의 터미널에 질문이 떠 있다.' },
  focus_mode: { label: '터미널에서 묻는 중', note: '집중 모드라 중계하지 않았다. 그 세션의 터미널에 질문이 떠 있다.' },
};

/** The state of a relay's answer, as the person reads it. */
export function relayStatus(item: InboxItem, now: number): RelayStatus {
  const state = item.state ?? 'open';
  const words = answerWords(item.answer);
  const delivered = state === 'delivered' || (state === 'closed' && !!item.delivered_at);
  if (delivered) {
    return {
      label: words ? `전달됨: ${words}` : '전달됨',
      note: '답이 그 세션의 훅에 전달됐다. 터미널에서 먼저 답했으면 쓰이지 않았을 수 있다.',
      mark: 'done',
      tone: 'done',
    };
  }
  if (state === 'open' && !relayed(item)) {
    const why = NOT_RELAYED[item.relay_reason ?? ''] ?? { label: '터미널에서 묻는 중', note: '중계하지 않았다. 그 세션의 터미널에서 답한다.' };
    return { label: why.label, note: why.note, mark: 'session', tone: 'off' };
  }
  if (state === 'open') {
    const left = secondsLeft(item, now) ?? 0;
    if (left > 0) return { label: `${left}초 남음`, note: '기한 안에 답하지 않으면 그 세션의 터미널로 넘어간다.', mark: 'wait', tone: 'now' };
    return { label: '이미 터미널로 넘어감', note: '기한이 지났다. 그 세션의 터미널에서 답한다.', mark: 'session', tone: 'off' };
  }
  const picked = words ? `고른 답: ${words}. ` : '';
  if (state === 'answered') return { label: '전달하는 중', note: `${picked}그 세션의 훅이 답을 가져가기를 기다린다.`, mark: 'wait', tone: 'wait' };
  if (state === 'undelivered') {
    return { label: '전달 안 됨', note: `${picked}답이 실행기에 닿지 않았다. 기한이 지났거나 세션이 끝났다. 터미널에서 답한다.`, mark: 'alert', tone: 'fail' };
  }
  if (state === 'moved_to_terminal') {
    if (item.relay_reason === 'person') return { label: '터미널로 넘김', note: '여기서 답하지 않고 그 세션의 터미널로 넘겼다.', mark: 'session', tone: 'off' };
    return { label: '이미 터미널로 넘어감', note: '기한 안에 답이 없어 그 세션의 터미널로 넘어갔다.', mark: 'session', tone: 'off' };
  }
  if (item.relay_reason === 'session_ended') return { label: '세션이 끝나 닫힘', note: '답하기 전에 그 세션이 끝났다.', mark: 'session', tone: 'off' };
  return { label: '세션이 다음으로 움직여 닫힘', note: '그 세션이 터미널에서 답을 받아 다음으로 움직였다.', mark: 'session', tone: 'off' };
}

const text = (v: unknown): string | null => (typeof v === 'string' && v.trim() !== '' ? v : null);

/** What a person judges for a permission request (relay 2.3). */
export type PermissionView = {
  tool: string;
  command: string | null;
  filePath: string | null;
  description: string | null;
  /** The whole tool input, pretty printed, for tools other than a shell command. */
  input: string | null;
  location: { remote: string | null; repoKey: string | null; branch: string | null; path: string | null; inRepo: boolean } | null;
  permissionMode: string | null;
};

export function permissionView(item: InboxItem): PermissionView | null {
  const d = item.detail;
  if (!d) return null;
  const loc = d.work_location && typeof d.work_location === 'object' ? (d.work_location as Record<string, unknown>) : null;
  const command = text(d.command);
  const input = d.input && typeof d.input === 'object' ? (d.input as Record<string, unknown>) : null;
  // A shell command is shown in full on its own; for any other tool the whole input is the thing to judge.
  const rest = input && command !== null ? Object.keys(input).filter((k) => k !== 'command' && k !== 'description') : null;
  return {
    tool: text(d.tool_name) ?? '도구',
    command,
    filePath: text(d.file_path),
    description: text(d.description),
    input: input && (command === null || (rest && rest.length > 0)) ? JSON.stringify(input, null, 2) : null,
    location: loc
      ? { remote: text(loc.remote), repoKey: text(loc.repo_key), branch: text(loc.branch), path: typeof loc.path === 'string' ? loc.path : null, inRepo: loc.in_repo === true }
      : null,
    permissionMode: text(d.permission_mode),
  };
}

export type QuestionView = { question: string; header: string | null; multiSelect: boolean; options: { label: string; description: string | null }[] };

/** The questions of an AskUserQuestion, as asked. */
export function questionsOf(item: InboxItem): QuestionView[] {
  const raw = item.detail?.questions;
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((q): QuestionView[] => {
    if (typeof q !== 'object' || q === null) return [];
    const r = q as Record<string, unknown>;
    const question = text(r.question);
    if (!question || !Array.isArray(r.options)) return [];
    const options = r.options.flatMap((o) => {
      const label = typeof o === 'object' && o !== null ? text((o as Record<string, unknown>).label) : null;
      return label ? [{ label, description: text((o as Record<string, unknown>).description) }] : [];
    });
    return [{ question, header: text(r.header), multiSelect: r.multiSelect === true, options }];
  });
}

/** Chosen labels per question. */
export type Choices = Record<string, string[]>;

/** Picks an option: one per question, or toggled when the question takes several. */
export function choose(choices: Choices, q: QuestionView, label: string): Choices {
  const now = choices[q.question] ?? [];
  if (!q.multiSelect) return { ...choices, [q.question]: [label] };
  return { ...choices, [q.question]: now.includes(label) ? now.filter((l) => l !== label) : [...now, label] };
}

/** The answer to send, or the reason it cannot go yet. */
export function questionAnswer(questions: QuestionView[], choices: Choices): { answer: RelayAnswer } | { missing: string } {
  const answers: Record<string, string[]> = {};
  for (const q of questions) {
    const picked = (choices[q.question] ?? []).filter((l) => q.options.some((o) => o.label === l));
    if (picked.length === 0) return { missing: q.question };
    answers[q.question] = picked;
  }
  return { answer: { answers } };
}

/** A permission request's answer: a reason goes with a deny only, and only when one was written. */
export function permissionAnswer(decision: 'allow' | 'deny', reason: string): RelayAnswer {
  const why = reason.trim();
  return decision === 'deny' && why ? { decision, reason: why } : { decision };
}

/** The row's title: the tool and its command or file, the first question, or why it waits in the terminal. */
export function relayTitle(item: InboxItem): string {
  if (!relayed(item) && item.relay_reason === 'secret_suspected') return '비밀값이 섞여 터미널에서 확인';
  if (!relayed(item) && item.relay_reason === 'too_large') return '상세가 길어 터미널에서 확인';
  if (item.kind === 'session_question') return questionsOf(item)[0]?.question ?? '선택형 질문';
  const p = permissionView(item);
  if (!p) return item.summary;
  const what = (p.command ?? p.filePath)?.split('\n')[0];
  return what ? `${p.tool}: ${what}` : `${p.tool} 권한 요청`;
}

/** The detail's title: what the session asks, in one sentence. */
export function relayDetailTitle(item: InboxItem): string {
  const what = item.kind === 'session_question' ? '질문' : '권한 요청';
  if (!relayed(item) && item.relay_reason === 'secret_suspected') return `비밀값이 섞인 ${what}은 터미널에서 확인한다`;
  if (!relayed(item) && item.relay_reason === 'too_large') return `상세가 긴 ${what}은 터미널에서 확인한다`;
  if (item.kind === 'session_question') return 'AI 세션이 선택형 질문을 묻는다';
  const tool = permissionView(item)?.tool;
  return tool ? `AI 세션이 ${tool} 권한을 묻는다` : 'AI 세션이 도구 권한을 묻는다';
}
