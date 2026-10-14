// The one place the screen talks to the server (docs/stage1/auth.md). Every API module sends its
// requests through `apiFetch`, which:
// 1. sends the sign-in cookie (same origin, the browser's default) and, on a request that changes
//    something, the login's CSRF token in `X-CSRF-Token`, when the server is in `required` mode;
// 2. tells the shell when the server answers 401, so it can show the sign-in screen again.
// The token is kept in memory only; a reload reads it again from `GET /api/auth/state`.

const SAFE = new Set(['GET', 'HEAD', 'OPTIONS']);

let csrf: string | null = null;
const listeners = new Set<() => void>();

/** The CSRF token of the current login, or null in `local` mode and when signed out. */
export function setCsrf(token: string | null) {
  csrf = token;
}

/** Called whenever the server answers 401 to a screen's request; returns how to stop listening. */
export function onUnauthorized(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const method = (init.method ?? 'GET').toUpperCase();
  let headers = init.headers;
  if (csrf !== null && !SAFE.has(method)) {
    headers = { ...(headers as Record<string, string> | undefined), 'X-CSRF-Token': csrf };
  }
  const res = await fetch(path, headers === init.headers ? init : { ...init, headers });
  if (res.status === 401) for (const l of listeners) l();
  return res;
}
