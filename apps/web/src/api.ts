// Server API for the compat screen. Paths are relative: the page is served from the
// same origin as the API (Vite proxy in dev, the server itself otherwise).

export type WorkItem = {
  id: string;
  title: string;
  stage: string;
  wait_reasons: string[];
  generation: number;
  created_at: string;
};

export type Run = {
  id: string;
  work_item_id: string;
  executor: string;
  generation: number | null;
  device_id: string | null;
  late_reports: boolean;
  created_at: string;
};

export type RunEvent = {
  event_id: string;
  generation: number;
  seq: number;
  kind: string;
  payload: Record<string, unknown>;
  occurred_at: string | null;
  received_at: string;
};

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${method} ${path}: HTTP ${res.status}`);
  return (await res.json()) as T;
}

const enc = encodeURIComponent;

export const api = {
  workItems: () => call<WorkItem[]>('GET', '/api/work-items?limit=20'),
  runs: (workItemId: string) => call<Run[]>('GET', `/api/work-items/${enc(workItemId)}/runs`),
  events: (runId: string, afterSeq: number) =>
    call<RunEvent[]>('GET', `/api/runs/${enc(runId)}/events?after_seq=${afterSeq}`),
  createWorkItem: (title: string) =>
    call<{ id: string }>('POST', '/api/work-items', { title, responsible_user_id: 'usr_owner' }),
  createRun: (workItemId: string) =>
    call<{ id: string }>('POST', `/api/work-items/${enc(workItemId)}/runs`, { executor: 'claude_code' }),
};
