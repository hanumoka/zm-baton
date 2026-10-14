// Sign-in and devices (docs/stage1/auth.md): the server's mode, signing in and out, the first
// password with the one-time setup token, changing the password, my devices, and approving a device
// login that `zm-baton login` shows on a server in `required` mode.

import { call } from './workApi';

export type AuthMode = 'local' | 'required';

export type AuthState = {
  mode: AuthMode;
  /** The browser reaches the server over TLS. */
  secure: boolean;
  /** Who the screen acts for: the signed-in person, or in `local` mode the owner. Null when nobody is signed in. */
  person: { id: string; display_name: string; login: string | null } | null;
  /** Sent back with every changing request (http.ts). */
  csrf_token: string | null;
  /** No owner has a password yet: the screen offers the first password with the setup token. */
  setup_pending: boolean;
};

export type MyDevice = {
  id: string;
  name: string;
  os: string | null;
  created_at: string;
  last_seen_at: string | null;
  revoked_at: string | null;
  /** A device registered before tokens has none: it works while the server stays in `local` mode. */
  has_token: boolean;
  token_issued_at: string | null;
};

export type DeviceLoginState = 'pending' | 'approved' | 'denied' | 'expired' | 'done';

export type DeviceLoginView = {
  user_code: string;
  name: string;
  os: string | null;
  /** The device that asks for a new token for itself (mine); null for a new device. */
  device_id: string | null;
  device_name: string | null;
  state: DeviceLoginState;
  created_at: string;
  expires_at: string;
};

const enc = encodeURIComponent;

export const authApi = {
  state: (signal?: AbortSignal) => call<AuthState>('GET', '/api/auth/state', undefined, signal),
  login: (login: string, password: string) => call<AuthState>('POST', '/api/auth/login', { login, password }),
  setup: (setupToken: string, login: string, password: string) =>
    call<AuthState>('POST', '/api/auth/setup', { setup_token: setupToken, login, password }),
  logout: () => call<AuthState>('POST', '/api/auth/logout'),
  changePassword: (current: string, next: string) =>
    call<{ other_logins_ended: number }>('PUT', '/api/auth/password', { current_password: current, new_password: next }),
  devices: (signal?: AbortSignal) => call<MyDevice[]>('GET', '/api/me/devices', undefined, signal),
  revoke: (id: string) => call<MyDevice>('POST', `/api/me/devices/${enc(id)}/revoke`),
  deviceLogin: (code: string, signal?: AbortSignal) => call<DeviceLoginView>('GET', `/api/device-logins/${enc(code)}`, undefined, signal),
  approve: (code: string) => call<DeviceLoginView>('POST', `/api/device-logins/${enc(code)}/approve`),
  deny: (code: string) => call<DeviceLoginView>('POST', `/api/device-logins/${enc(code)}/deny`),
};

/** The server's rules for a password, checked before sending so the button can say why it waits. */
export const MIN_PASSWORD = 12;
export const MAX_PASSWORD_BYTES = 72;

/** Why a new password cannot be sent yet, or null when it can. */
export function passwordProblem(password: string, again: string, login = ''): string | null {
  if (password.length < MIN_PASSWORD) return `비밀번호는 ${MIN_PASSWORD}자 이상이다`;
  if (new TextEncoder().encode(password).length > MAX_PASSWORD_BYTES) return `너무 길다. UTF-8로 ${MAX_PASSWORD_BYTES}바이트까지다(한국어는 24자쯤)`;
  if (login !== '' && password.trim().toLowerCase() === login.trim().toLowerCase()) return '아이디와 다르게 정한다';
  if (password !== again) return '두 칸의 비밀번호가 다르다';
  return null;
}

/** `bcdf ghjk` and `BCDFGHJK` read as `BCDF-GHJK`; anything that is not eight code letters is null. */
export function normalCode(raw: string): string | null {
  const letters = raw.toUpperCase().replace(/[^BCDFGHJKLMNPQRSTVWXZ]/g, '');
  return letters.length === 8 ? `${letters.slice(0, 4)}-${letters.slice(4)}` : null;
}

export const DEVICE_LOGIN_LABELS: Record<DeviceLoginState, string> = {
  pending: '허락을 기다린다',
  approved: '허락했다. 그 기기가 토큰을 가져가기를 기다린다',
  denied: '거절했다',
  expired: '기한이 지났다',
  done: '연결했다. 그 기기가 토큰을 가져갔다',
};
