"""Destructive fresh-install browser check. Use only an isolated, empty preview database.

Install Python Playwright and Chromium. Supply BATON_PREVIEW_URL, BATON_SETUP_TOKEN,
and BATON_EVIDENCE_DIR. Tokens/passwords are never printed or captured in screenshots.
"""
import json
import os
from pathlib import Path
import secrets
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright, expect
from setup_recovery import evidence_directory


def main():
    base = os.environ["BATON_PREVIEW_URL"].rstrip("/")
    assert urlparse(base).hostname in {"localhost", "127.0.0.1"}, "preview must be local"
    token = os.environ["BATON_SETUP_TOKEN"]
    out = evidence_directory(os.environ["BATON_EVIDENCE_DIR"])
    password = secrets.token_urlsafe(24)
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        owner = browser.new_context(viewport={"width": 1440, "height": 1000})
        page = owner.new_page()
        errors = []
        page.on("pageerror", lambda _: errors.append('pageerror'))
        page.on("console", lambda event: errors.append('console error') if event.type == 'error' else None)
        page.on("requestfailed", lambda request: errors.append('network failure') if request.failure != 'net::ERR_ABORTED' else None)

        def call(ctx, method, path, data=None, expected=200):
            state = ctx.request.get(base + "/api/auth/state").json()
            csrf = state.get("csrf_token")
            headers = {"X-CSRF-Token": csrf} if csrf else {}
            res = ctx.request.fetch(base + path, method=method, data=data, headers=headers)
            assert res.status == expected, (path, res.status, "unexpected status")
            return res.json() if res.body() else None

        def capture(name):
            for width in (1440, 390):
                page.set_viewport_size({"width": width, "height": 1000})
                page.screenshot(path=str(out / f"{name}-{width}.png"), full_page=True)
                assert page.evaluate("document.documentElement.scrollWidth <= innerWidth"), name
            page.set_viewport_size({"width": 1440, "height": 1000})

        def variants(name, styles):
            # Prototypes use the live preview's synthetic records. Only layout changes; no API mocking.
            for variant, css in styles.items():
                style = page.add_style_tag(content=css or "/* Existing layout */")
                capture(f"{name}-variant-{variant}")
                style.evaluate("node => node.remove()")

        assert call(owner, "GET", "/api/auth/state")["setup_pending"], "requires an empty install"
        page.goto(base)
        page.get_by_label("설정 코드", exact=True).fill(token)
        page.get_by_label("아이디", exact=True).fill("preview-owner")
        page.get_by_label("새 비밀번호(12자 이상)", exact=True).fill(password)
        page.get_by_label("새 비밀번호 한 번 더", exact=True).fill(password)
        page.get_by_role("button", name="비밀번호 정하고 로그인", exact=True).click()
        expect(page.get_by_role("heading", name="내 조직의 서버 시작하기")).to_be_visible()
        capture("setup-empty")
        variants("setup", {
            "a": "",
            "b": ".au-col{width:min(100%,var(--w-list))}.au-form{display:flex;flex-direction:column}.au-form>p:last-of-type{order:-1;padding-bottom:var(--s-3);border-bottom:var(--rule) solid var(--c-rule)}",
            "c": ".au-col{width:100%;max-width:calc(var(--w-list) * 2)}.au-form{display:grid;grid-template-columns:1fr 1fr}.au-form>h1,.au-form>p,.au-form>.wd-acts{grid-column:1/-1}@media(max-width:600px){.au-form{grid-template-columns:1fr}.au-form>p:last-of-type{order:1}}",
        })
        # Complete the main setup using the keyboard, starting from the auto-focused field.
        page.get_by_label("조직 이름", exact=True).focus()
        page.keyboard.press("ControlOrMeta+A")
        page.keyboard.type("Preview Team")
        page.keyboard.press("Tab")
        page.keyboard.press("ControlOrMeta+A")
        page.keyboard.type("Preview Administrator")
        page.keyboard.press("Tab")
        page.keyboard.press("Enter")
        expect(page.get_by_role("heading", name="내 조직의 서버 시작하기")).not_to_be_visible()
        page.goto(base + "/#server")
        expect(page.get_by_role("heading", name="서버 관리", exact=True)).to_be_visible()
        expect(page.get_by_role("heading", name="서버 관리자 변경", exact=True)).to_be_visible()
        capture("server-solo")
        call(owner, "POST", "/api/server/initialize", {"organization_name": "again", "administrator_name": "again"}, 409)

        org = call(owner, "POST", "/api/org/people", {"display_name": "새 서버 관리자 — 긴 이름과 팀 표시를 확인하는 시험 구성원", "roles": ["requester"]}, 201)
        member = next(p["id"] for p in org["people"] if not p["owner"])
        setup = call(owner, "POST", f"/api/org/people/{member}/login/setup")
        member_ctx = browser.new_context()
        call(member_ctx, "POST", "/api/auth/setup", {"setup_token": setup["setup_token"], "login": "preview-member", "password": password})
        call(member_ctx, "GET", "/api/server", expected=403)
        page.reload()
        expect(page.get_by_label("넘겨받을 구성원").locator("option")).to_have_count(2)
        page.get_by_label("넘겨받을 구성원").select_option(member)
        capture("server-team")
        variants("server", {
            "a": "",
            "b": ".dt-grid{display:flex;flex-direction:column}.dt-aside{order:-1;border-left:0}.dt-aside .dt-sec{padding:var(--s-3) 0}.dt-main{width:100%}",
            "c": ".dt-grid{display:flex;flex-direction:row-reverse;gap:var(--s-4)}.dt-main{flex:1}.dt-aside{flex:0 1 var(--w-list);border-left:0}.dt-facts{display:grid;grid-template-columns:1fr 1fr}@media(max-width:600px){.dt-grid{flex-direction:column}.dt-aside{flex:auto}.dt-facts{grid-template-columns:1fr}}",
        })
        page.get_by_label("넘겨받을 구성원").focus()
        page.keyboard.press("Tab")
        page.keyboard.press("Space")
        page.keyboard.press("Tab")
        expect(page.get_by_role("button", name="서버 관리 넘기기", exact=True)).to_be_focused()
        page.keyboard.press("Enter")
        expect(page.get_by_role("heading", name="서버 관리", exact=True)).not_to_be_visible()
        call(owner, "GET", "/api/server", expected=403)
        assert call(member_ctx, "GET", "/api/server")["administrator_user_id"] == member
        call(owner, "PATCH", f"/api/org/people/{member}", {"status": "inactive"}, 409)
        # Restore the initial administrator so the environment is ready for manual inspection.
        call(member_ctx, "PUT", "/api/server/administrator", {"user_id": "usr_owner"})
        assert not errors, errors
        owner.storage_state(path=str(out / "owner-browser-state.json"))
        (out / "preview-login.json").write_text(json.dumps({"login": "preview-owner", "password": password}), encoding="utf-8")
        (out / "browser-results.json").write_text(json.dumps({"passed": ["fresh password", "keyboard setup", "single initialization", "solo and team layouts", "administrator transfer", "old administrator denied", "active administrator protected"], "page_errors": errors}, ensure_ascii=False, indent=2), encoding="utf-8")
        browser.close()
    print("PASS: fresh installation, keyboard setup, administrator transfer; screenshots at 1440 and 390px")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        import sys
        import traceback
        trace = traceback.extract_tb(error.__traceback__)
        own = next((frame for frame in reversed(trace) if Path(frame.filename).resolve() == Path(__file__).resolve()), trace[-1])
        print(f'FAIL: {type(error).__name__} at {own.name}:{own.lineno}', file=sys.stderr)
        raise SystemExit(1)
