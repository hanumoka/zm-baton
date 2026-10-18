import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { authApi, normalCode, passwordProblem, type AuthState, type MyDevice } from './authApi';
import { LOCAL_FALLBACK } from './auth/AuthGate';
import { Login } from './auth/Login';
import { apiFetch, onUnauthorized, setCsrf } from './http';
import { codeInAddress, DeviceApproval, DeviceRow, MyDevices, SignedIn } from './me/MyAccess';

// Sign-in on the screen (docs/stage1/auth.md). Synthetic values only.

const required = (over: Partial<AuthState> = {}): AuthState => ({
  mode: 'required',
  secure: false,
  person: null,
  csrf_token: null,
  setup_pending: false,
  ...over,
});

const device = (over: Partial<MyDevice> = {}): MyDevice => ({
  id: 'dev_a',
  name: 'my-pc',
  os: 'windows',
  created_at: '2026-10-14T00:00:00Z',
  last_seen_at: '2026-10-14T01:00:00Z',
  revoked_at: null,
  has_token: true,
  token_issued_at: '2026-10-14T00:00:00Z',
  ...over,
});

describe('the sign-in screen', () => {
  it('asks for a login name and a password, and the button says why it waits', () => {
    const html = renderToStaticMarkup(<Login state={required()} onSignedIn={() => {}} />);
    expect(html).toContain('<h1 id="au-title" class="dt-title">로그인</h1>');
    expect(html).toContain('autoComplete="username"');
    expect(html).toContain('type="password"');
    expect(html).toMatch(/<button type="submit" class="btn btn-primary" disabled="">로그인<\/button>/);
    expect(html).toContain('아이디를 넣는다');
    expect(html).toContain('설정 토큰으로 비밀번호 정하기');
  });

  it('opens on the first password while no owner has one, and says where the setup token is', () => {
    const html = renderToStaticMarkup(<Login state={required({ setup_pending: true })} onSignedIn={() => {}} />);
    expect(html).toContain('처음 비밀번호 정하기');
    expect(html).toContain('zmbt_');
    expect(html).toContain('조직 소유자에게 받은 설정 코드');
    expect(html).toContain('최초 소유자는 서버 시작 기록');
    expect(html).toContain('설정 토큰을 넣는다');
    expect(html).toContain('로그인으로 돌아가기');
  });

  it('checks a new password the way the server does', () => {
    expect(passwordProblem('short', 'short')).toContain('12자');
    expect(passwordProblem('a'.repeat(73), 'a'.repeat(73))).toContain('72바이트');
    expect(passwordProblem('가'.repeat(25), '가'.repeat(25))).toContain('72바이트');
    expect(passwordProblem('owner-owner1', 'owner-owner1', 'OWNER-owner1')).toContain('아이디');
    expect(passwordProblem('a long passphrase', 'a long passphrasE')).toContain('다르다');
    expect(passwordProblem('a long passphrase', 'a long passphrase', 'owner')).toBeNull();
  });

  it('reads a device login code however it was typed', () => {
    expect(normalCode('bcdf ghjk')).toBe('BCDF-GHJK');
    expect(normalCode('BCDFGHJK')).toBe('BCDF-GHJK');
    expect(normalCode('ABCD-EFGH')).toBeNull();
    expect(codeInAddress('#me?device_login=bcdf-ghjk')).toBe('BCDF-GHJK');
    expect(codeInAddress('#me')).toBeNull();
  });
});

describe('requests', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setCsrf(null);
  });

  it('sends the CSRF token on a changing request only, and only while signed in', async () => {
    const fetch = vi.fn(async () => new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetch);
    await apiFetch('/api/x', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    setCsrf('csrf-synthetic');
    await apiFetch('/api/x', { method: 'GET' });
    await apiFetch('/api/x', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    await apiFetch('/api/x', { method: 'DELETE' });
    const headers = (fetch.mock.calls as unknown as [string, RequestInit][]).map(([, i]) => (i.headers as Record<string, string> | undefined)?.['X-CSRF-Token'] ?? null);
    expect(headers).toEqual([null, null, 'csrf-synthetic', 'csrf-synthetic']);
  });

  it('tells the shell when the server answers 401', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"message":"로그인이 필요하다"}', { status: 401 })));
    const heard = vi.fn();
    const stop = onUnauthorized(heard);
    await expect(authApi.devices()).rejects.toThrow('로그인이 필요하다');
    stop();
    await expect(authApi.devices()).rejects.toThrow();
    expect(heard).toHaveBeenCalledTimes(1);
  });

  it('uses the sign-in and device paths', async () => {
    const fetch = vi.fn(async () => new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetch);
    await authApi.login('owner', 'pw');
    await authApi.setup('zmbt_x', 'owner', 'pw');
    await authApi.logout();
    await authApi.changePassword('a', 'b');
    await authApi.revoke('dev_a');
    await authApi.approve('BCDF-GHJK');
    await authApi.deny('BCDF-GHJK');
    const calls = (fetch.mock.calls as unknown as [string, RequestInit][]).map(([p, i]) => [i.method, p, i.body ? JSON.parse(i.body as string) : null]);
    expect(calls).toEqual([
      ['POST', '/api/auth/login', { login: 'owner', password: 'pw' }],
      ['POST', '/api/auth/setup', { setup_token: 'zmbt_x', login: 'owner', password: 'pw' }],
      ['POST', '/api/auth/logout', null],
      ['PUT', '/api/auth/password', { current_password: 'a', new_password: 'b' }],
      ['POST', '/api/me/devices/dev_a/revoke', null],
      ['POST', '/api/device-logins/BCDF-GHJK/approve', null],
      ['POST', '/api/device-logins/BCDF-GHJK/deny', null],
    ]);
  });
});

describe('the 나 screen, signed in', () => {
  it('says who is signed in, with sign-out and the password form', () => {
    const html = renderToStaticMarkup(
      <SignedIn auth={required({ person: { id: 'usr_owner', display_name: 'owner', login: 'owner' }, csrf_token: 'c' })} onAuthChanged={() => {}} />,
    );
    expect(html).toContain('아이디 <b>owner</b>로 로그인했다');
    expect(html).toContain('로그아웃');
    expect(html).toContain('비밀번호 바꾸기');
    expect(html).toContain('지금 비밀번호를 넣는다');
  });

  it('shows each device with its token state and what to do about a missing one', () => {
    const ok = renderToStaticMarkup(<DeviceRow device={device()} required onChanged={() => {}} />);
    expect(ok).toContain('기기 토큰 있음');
    expect(ok).toContain('폐기하기');
    const legacyRequired = renderToStaticMarkup(<DeviceRow device={device({ has_token: false, token_issued_at: null })} required onChanged={() => {}} />);
    expect(legacyRequired).toContain('기기 토큰 없음');
    expect(legacyRequired).toContain('이 서버가 받지 않는다');
    const legacyLocal = renderToStaticMarkup(<DeviceRow device={device({ has_token: false })} required={false} onChanged={() => {}} />);
    expect(legacyLocal).toContain('required로 바꾸기 전에');
    const revoked = renderToStaticMarkup(<DeviceRow device={device({ revoked_at: '2026-10-14T02:00:00Z' })} required onChanged={() => {}} />);
    expect(revoked).toContain('폐기함');
    expect(revoked).not.toContain('폐기하기');
  });

  it('offers the code box in required mode only, and reads a device login before showing its buttons', () => {
    const box = renderToStaticMarkup(<MyDevices auth={required()} onEnterCode={() => {}} />);
    expect(box).toContain('zm-baton login</code>이 보여 준 코드');
    expect(box).toContain('코드 여덟 글자를 넣는다');
    expect(renderToStaticMarkup(<MyDevices auth={LOCAL_FALLBACK} onEnterCode={() => {}} />)).not.toContain('보여 준 코드');
    const approval = renderToStaticMarkup(<DeviceApproval code="BCDF-GHJK" />);
    expect(approval).toContain('기기 연결 요청');
    expect(approval).toContain('BCDF-GHJK');
    expect(approval).not.toContain('연결 허락');
  });
});
