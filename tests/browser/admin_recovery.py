"""Actual server administration regression on a disposable restored preview only.

Requires the synthetic preview-owner/preview-member accounts from server_admin.py.
Supply BATON_PREVIEW_URL, BATON_EVIDENCE_DIR and BATON_FIXTURE_LOGIN_FILE (the private
preview-login.json). --before captures the previous UI without changing administration.
After a check, administration is restored to the original owner even on failure.
"""
import argparse
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright
from setup_recovery import evidence_directory


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--before', action='store_true')
    args = parser.parse_args()
    base = os.environ['BATON_PREVIEW_URL'].rstrip('/')
    assert urlparse(base).hostname in ('127.0.0.1', 'localhost'), 'use a local disposable preview'
    out = evidence_directory(os.environ['BATON_EVIDENCE_DIR'])
    login = json.loads(Path(os.environ['BATON_FIXTURE_LOGIN_FILE']).read_text(encoding='utf-8-sig'))
    assert login['login'] == 'preview-owner', 'requires the synthetic fixture'
    results = []
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        owner = browser.new_context(viewport={'width': 1440, 'height': 1000})
        member = browser.new_context()

        def call(ctx, method, path, data=None, expected=200):
            state = ctx.request.get(base + '/api/auth/state').json()
            headers = {'X-CSRF-Token': state['csrf_token']} if state.get('csrf_token') else {}
            res = ctx.request.fetch(base + path, method=method, data=data, headers=headers)
            assert res.status == expected, 'unexpected API status'
            return res.json() if res.body() else None

        call(owner, 'POST', '/api/auth/login', login)
        call(member, 'POST', '/api/auth/login', {**login, 'login': 'preview-member'})
        original = call(owner, 'GET', '/api/server')
        assert original['organization_name'].startswith('Preview')
        original_id = original['administrator_user_id']
        target_id = call(member, 'GET', '/api/auth/state')['person']['id']
        assert original_id != target_id
        page = owner.new_page()
        errors, failed, statuses = [], [], []
        page.on('pageerror', lambda _: errors.append('pageerror'))
        page.on('console', lambda m: errors.append(m.text) if m.type == 'error' else None)
        page.on('requestfailed', lambda r: failed.append(r.failure))
        page.on('response', lambda r: statuses.append(r.status) if r.status >= 400 else None)

        def open_screen():
            page.goto(base + '/#server')
            page.reload()  # A same-hash goto otherwise keeps the previous AuthGate role.
            expect(page.get_by_label('넘겨받을 구성원')).to_be_visible()

        def restore():
            if member.request.get(base + '/api/server').status == 200:
                call(member, 'PUT', '/api/server/administrator', {'user_id': original_id})

        try:
            open_screen()
            for width in (1440, 390):
                page.set_viewport_size({'width': width, 'height': 1000})
                page.screenshot(path=str(out / f"admin-{'before' if args.before else 'after'}-{width}.png"), full_page=True)
                assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
                if not args.before:
                    expect(page.get_by_role('navigation', name='현재 위치').locator('[aria-current="page"]')).to_have_text('서버 관리')
                    if width == 390:
                        assert page.locator('.server-management .btn, .server-management .field').evaluate_all(
                            'nodes => nodes.every(node => node.getBoundingClientRect().height >= 44)')
            if not args.before:
                page.set_viewport_size({'width': 1440, 'height': 1000})
                select = page.get_by_label('넘겨받을 구성원')
                select.focus()
                page.keyboard.press('ArrowDown')
                expect(select).to_have_value(target_id)
                page.keyboard.press('Tab')
                expect(page.get_by_role('checkbox')).to_be_focused()
                page.keyboard.press('Space')
                page.keyboard.press('Tab')
                expect(page.get_by_role('button', name='서버 관리 넘기기', exact=True)).to_be_focused()
                page.keyboard.press('Enter')
                expect(page).to_have_url(base + '/#me')
                expect(page.get_by_role('link', name='서버 관리 열기')).not_to_be_visible()
                assert call(member, 'GET', '/api/server')['administrator_user_id'] == target_id
                results.append('actual keyboard transfer and old administrator view removed')
                restore()

                # Keep the old page open while authority changes in another browser.
                open_screen()
                page.get_by_label('넘겨받을 구성원').select_option(target_id)
                page.get_by_role('checkbox').check()
                call(owner, 'PUT', '/api/server/administrator', {'user_id': target_id})
                page.get_by_role('button', name='서버 관리 넘기기', exact=True).click()
                expect(page.get_by_text('서버 관리자만 볼 수 있다', exact=True)).to_be_visible()
                expect(page.get_by_label('넘겨받을 구성원')).not_to_be_visible()
                results.append('actual transfer 403 rereads authentication')
                restore()

                # Revoke only after AuthGate read the old role, before the server view is read.
                def revoke_before_read(route):
                    call(owner, 'PUT', '/api/server/administrator', {'user_id': target_id})
                    route.continue_()
                page.route('**/api/server', revoke_before_read, times=1)
                page.reload()
                expect(page.get_by_text('서버 관리자만 볼 수 있다', exact=True)).to_be_visible()
                results.append('actual view 403 rereads authentication')
                restore()
            assert all(s == 403 for s in statuses), 'unexpected HTTP status'
            assert all('Failed to load resource' in e and '403' in e for e in errors), 'unexpected console/page error'
            assert all(reason == 'net::ERR_ABORTED' for reason in failed), 'unexpected network failure'
            (out / f"admin-{'before' if args.before else 'after'}-results.json").write_text(json.dumps(
                {'kind': 'actual API on disposable restored preview', 'passed': results, 'expected_http_errors': statuses,
                 'navigation_aborted_requests': len(failed), 'unexpected_browser_errors': 0}, ensure_ascii=False, indent=2), encoding='utf-8')
        finally:
            restore()
            browser.close()
    print('PASS: administration browser check; restored the original administrator')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import traceback
        trace = traceback.extract_tb(error.__traceback__)
        own = next((frame for frame in reversed(trace) if Path(frame.filename).resolve() == Path(__file__).resolve()), trace[-1])
        print(f'FAIL: {type(error).__name__} at {own.name}:{own.lineno}', file=sys.stderr)
        raise SystemExit(1)
