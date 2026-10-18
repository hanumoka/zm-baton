"""Setup UI regression check with synthetic API responses; never reaches a real server.

Build apps/web first. Run with --dist apps/web/dist --evidence-dir an ignored/outside
directory. Optional --baseline-dist captures the old screen with the same fixture.
Python Playwright and Chromium are required. This is not a backend integration test.
"""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
from threading import Thread
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass


def evidence_directory(path):
    """Do not create browser evidence in a potentially tracked source directory."""
    out = Path(path).resolve()
    parent = next(path for path in (out, *out.parents) if path.is_dir())
    found = subprocess.run(['git', '-C', str(parent), 'rev-parse', '--show-toplevel'], capture_output=True, text=True)
    if found.returncode == 0:
        repo = Path(found.stdout.strip()).resolve()
        result = subprocess.run(
            ['git', '-C', str(repo), 'check-ignore', '--quiet', '--', str(out) + '/'],
            capture_output=True,
        )
        if result.returncode != 0:
            raise ValueError('evidence directory must be outside the checkout or Git-ignored')
    out.mkdir(parents=True, exist_ok=True)
    return out


def exercise(pw, dist, out, baseline=False):
    assert (dist / 'index.html').is_file(), 'build the web app first'
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(QuietHandler, directory=str(dist)))
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    browser = pw.chromium.launch()
    base = f'http://127.0.0.1:{server.server_port}'
    results = []
    cases = ['normal'] if baseline else ['normal', 'long_names', 'seed_names', 'csrf_refresh', 'retry', 'offline', 'submit_409', 'submit_403', 'load_403', 'already_initialized']
    try:
        for case in cases:
            context = browser.new_context(viewport={'width': 1440, 'height': 1000})
            page = context.new_page()
            errors, failed, statuses, unexpected = [], [], [], []
            page.on('pageerror', lambda _: errors.append('pageerror'))
            page.on('console', lambda m: errors.append(m.text) if m.type == 'error' else None)
            page.on('requestfailed', lambda r: failed.append(urlparse(r.url).path))
            page.on('response', lambda r: statuses.append(r.status) if r.status >= 400 else None)
            state = {'signed_out': False, 'server_reads': 0, 'auth_reads': 0, 'submitted': None, 'submissions': 0}
            org = 'owner' if case == 'seed_names' else 'Preview Team' if case != 'long_names' else '제품 개발과 운영을 함께 맡는 플랫폼 연구 조직'
            admin = 'owner' if case == 'seed_names' else 'Preview Administrator' if case != 'long_names' else '서버를 처음 설정하는 운영 담당 구성원'

            def api(route):
                path = urlparse(route.request.url).path
                code = 200
                if path == '/api/auth/state':
                    state['auth_reads'] += 1
                    data = {'mode': 'required', 'secure': False, 'csrf_token': None, 'setup_pending': False,
                            'server_admin': not state['signed_out'], 'server_setup_pending': not state['signed_out'],
                            'person': None if state['signed_out'] else {'id': 'usr_owner', 'display_name': 'Different authentication name', 'login': 'fixture-owner'}}
                elif path == '/api/server' and route.request.method == 'GET':
                    state['server_reads'] += 1
                    data = {'organization_id': 'org_fixture', 'organization_name': org, 'owner_user_id': 'usr_owner',
                            'administrator_user_id': 'usr_owner', 'administrator_name': admin, 'auth_mode': 'required',
                            'initialized_at': '2026-10-18T00:00:00Z' if case == 'already_initialized' else None}
                    if case == 'load_403':
                        code, data, state['signed_out'] = 403, {'message': '서버 관리자가 바뀌었다'}, True
                    elif case == 'already_initialized':
                        state['signed_out'] = True
                    elif case == 'retry' and state['server_reads'] == 1:
                        code, data = 500, {'message': '설정을 읽지 못했다. 다시 읽어 주세요.'}
                    elif case == 'offline' and state['server_reads'] == 1:
                        route.abort('connectionfailed')
                        return
                elif path == '/api/server/initialize':
                    state['submitted'] = route.request.post_data_json
                    state['submissions'] += 1
                    if case == 'csrf_refresh' and state['submissions'] == 1:
                        route.fulfill(status=403, json={'message': '인증 상태가 바뀌었다. 다시 시도한다.'})
                        return
                    state['signed_out'] = True
                    code = int(case.split('_')[1]) if case.startswith('submit_') else 200
                    data = {'message': '설정 또는 권한이 바뀌었다'} if code != 200 else {}
                else:
                    unexpected.append(path)
                    code, data = 500, {'message': 'unexpected synthetic endpoint'}
                route.fulfill(status=code, json=data)

            context.route('**/api/**', api)
            page.goto(base)
            heading = page.get_by_role('heading', name='내 조직의 서버 시작하기')
            if case in ('load_403', 'already_initialized'):
                expect(page.get_by_label('아이디', exact=True)).to_be_visible()
                assert state['auth_reads'] >= 2
            else:
                expect(heading).to_be_visible()
                if case in ('retry', 'offline'):
                    expect(page.get_by_role('alert')).to_be_visible()
                    expect(page.get_by_role('button', name='조직 설정 마치기')).to_be_disabled()
                    retry = page.get_by_role('button', name='설정 다시 읽기')
                    retry.focus()
                    page.keyboard.press('Enter')
                organization = page.get_by_label('조직 이름', exact=True)
                expect(organization).to_be_visible()
                if not baseline:
                    expect(organization).to_have_value('' if case == 'seed_names' else org)
                    expect(page.get_by_label('관리자 이름', exact=True)).to_have_value('' if case == 'seed_names' else admin)
                    expect(organization).to_be_focused()
                if case in ('normal', 'long_names', 'seed_names'):
                    for width in (1440, 390):
                        page.set_viewport_size({'width': width, 'height': 1000})
                        page.screenshot(path=str(out / f"setup-{'before' if baseline else 'after'}-{case}-{width}.png"), full_page=True)
                        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
                        if not baseline and width == 390:
                            assert page.locator('.server-setup .btn, .server-setup .field').evaluate_all(
                                'nodes => nodes.every(node => node.getBoundingClientRect().height >= 44)'
                            ), 'touch targets must be at least 44px'
                    page.set_viewport_size({'width': 1440, 'height': 1000})
                if not baseline:
                    if case == 'seed_names':
                        expect(page.get_by_role('button', name='조직 설정 마치기')).to_be_disabled()
                        page.keyboard.press('Enter')
                        assert state['submissions'] == 0, 'seed names must not initialize an installation'
                        page.keyboard.type('New Team')
                        page.keyboard.press('Tab')
                        page.keyboard.type('New Administrator')
                        org, admin = 'New Team', 'New Administrator'
                        organization.focus()
                    if case == 'csrf_refresh':
                        organization.fill('Edited Team')
                        page.get_by_label('관리자 이름', exact=True).fill('Edited Administrator')
                        with page.expect_response(lambda response: urlparse(response.url).path == '/api/auth/state') as reread:
                            page.get_by_role('button', name='조직 설정 마치기').click()
                        assert reread.value.json()['server_setup_pending']
                        expect(page.get_by_role('alert')).to_contain_text('다시 시도한다')
                        # Let React commit the authenticated state before checking for field resets.
                        page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
                        assert state['auth_reads'] >= 2
                        assert state['server_reads'] == 1, 'authentication refresh must not reload setup fields'
                        expect(organization).to_have_value('Edited Team')
                        expect(page.get_by_label('관리자 이름', exact=True)).to_have_value('Edited Administrator')
                        expect(page.get_by_role('alert')).to_contain_text('다시 시도한다')
                        org, admin = 'Edited Team', 'Edited Administrator'
                        organization.focus()
                    # Keyboard-only completion, preserving the prefetched names without retyping.
                    page.keyboard.press('Tab')
                    expect(page.get_by_label('관리자 이름', exact=True)).to_be_focused()
                    page.keyboard.press('Tab')
                    expect(page.get_by_role('button', name='조직 설정 마치기')).to_be_focused()
                    page.keyboard.press('Enter')
                    expect(page.get_by_label('아이디', exact=True)).to_be_visible()
                    assert state['submitted'] == {'organization_name': org, 'administrator_name': admin}
                    assert state['auth_reads'] >= 2
            allowed_status = {'retry': 500, 'load_403': 403, 'submit_403': 403, 'submit_409': 409, 'csrf_refresh': 403}.get(case)
            assert all(s == allowed_status for s in statuses), 'unexpected HTTP error'
            # Expected synthetic refusals and the intentionally failed request appear in Chromium's console.
            assert all('Failed to load resource' in e and (
                (allowed_status and str(allowed_status) in e) or (case == 'offline' and 'ERR_CONNECTION_FAILED' in e)
            ) for e in errors), 'unexpected browser console/page error'
            assert not failed or (case == 'offline' and failed == ['/api/server']), 'unexpected request failure'
            assert not unexpected, 'unexpected API request'
            results.append({'case': case, 'passed': True, 'expected_http_errors': statuses,
                            'intentional_network_failures': len(failed), 'unexpected_browser_errors': 0})
            context.close()
    finally:
        browser.close()
        server.shutdown()
        server.server_close()
        thread.join()
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dist', type=Path, required=True)
    parser.add_argument('--baseline-dist', type=Path)
    parser.add_argument('--evidence-dir', required=True)
    args = parser.parse_args()
    out = evidence_directory(args.evidence_dir)
    with sync_playwright() as pw:
        before = exercise(pw, args.baseline_dist.resolve(), out, True) if args.baseline_dist else []
        after = exercise(pw, args.dist.resolve(), out)
    (out / 'setup-recovery-results.json').write_text(json.dumps(
        {'kind': 'synthetic UI responses, no real backend', 'before': before, 'after': after}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'PASS: setup recovery {len(after)} cases, keyboard, 1440/390 screenshots')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Playwright errors may include request headers; never print the exception body.
        import traceback
        trace = traceback.extract_tb(error.__traceback__)
        own = next((frame for frame in reversed(trace) if Path(frame.filename).resolve() == Path(__file__).resolve()), trace[-1])
        print(f'FAIL: {type(error).__name__} at {own.name}:{own.lineno}', file=sys.stderr)
        raise SystemExit(1)
