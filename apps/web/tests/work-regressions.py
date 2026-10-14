"""Browser regressions for polling drafts, failed submissions and addressed mobile details.

Build apps/web first. Requires Python Playwright and its Chromium browser. Every API call is
intercepted with synthetic records; no running zm-baton server or real work item is used.
"""

import argparse
import functools
import json
import re
import tempfile
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Directory for screenshots and result.json")
    args = parser.parse_args()
    output = args.output or Path(tempfile.mkdtemp(prefix="zm-baton-web-regressions-"))
    output.mkdir(parents=True, exist_ok=True)
    (output / "result.json").unlink(missing_ok=True)
    dist = Path(__file__).resolve().parents[1] / "dist"
    if not (dist / "index.html").is_file():
        raise SystemExit("Build apps/web with npm run build first.")

    class Quiet(SimpleHTTPRequestHandler):
        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Quiet, directory=str(dist)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_port}/"
    item = dict(id="wrk_a", title="작성한 작업 내용을 유지한다", stage="ready", wait_reasons=[],
                generation=0, version=3, external_ref=None, project=None, source_of_truth="product",
                visibility="org", executor_kind="human", next_action="결과를 확인한다", wait_note=None,
                created_at="2026-10-14T00:00:00Z", opened_on="2026-10-14", due_on=None,
                closed_at=None, updated_at="2026-10-14T01:00:00Z")
    other = {**item, "id": "wrk_b", "title": "다른 작업을 확인한다"}
    items = {row["id"]: row for row in [item, other]}
    notices = [dict(id=f"inb_{key}", bucket=3, kind="notice", source_kind="ntc",
                    source_id=f"ntc_{key}", summary=f"두 세션이 같은 파일을 고침: {key}.md",
                    detail=dict(path=f"{key}.md", source="file_touch", sessions=[]),
                    wait_kind=None, repeat_count=0, waiting_since="2026-10-14T00:00:00Z",
                    notify="immediate") for key in ["a", "b"]]
    inbox = list(notices)
    updates, questions, errors, checks = [], [], [], []
    reject_update = True
    reject_question = True

    def detail(row):
        return dict(item=row, responsible_user_id="usr_owner", executor_id="usr_owner",
                    acceptance="작성한 내용과 선택한 작업을 유지한다", source_ref=None, git_ref=None,
                    live_run=False, read_only_reason=None, history=[],
                    moves=[dict(target="in_progress", path=["in_progress"], allowed=True,
                                reason=None, needs_note=False)],
                    delegation=dict(allowed=False, reason="시험 자료에는 실행 가능한 저장소가 없다"),
                    question=dict(allowed=True, reason=None))

    def route(r):
        nonlocal reject_update, reject_question
        req = r.request
        path = urlparse(req.url).path
        if path == "/api/me/first-screen":
            r.fulfill(json=dict(user_id="usr_owner", focus_on=False, first_screen_visible=False,
                                relay_wait_seconds=30))
            return
        if req.method == "POST" and path == "/api/notices/ntc_a/status":
            assert req.post_data_json == {"status": "acknowledged"}
            inbox[:] = [row for row in inbox if row["id"] != "inb_a"]
            r.fulfill(json={})
            return
        if req.method == "PATCH" and path == "/api/work-items/wrk_a":
            body = req.post_data_json
            updates.append(body)
            if reject_update:
                reject_update = False
                r.fulfill(status=409, json={"message": "다른 세션에서 변경했다. 다시 확인한다."})
            else:
                assert body["version"] == item["version"], body
                item.update({k: v for k, v in body.items() if k != "version"})
                item["version"] += 1
                r.fulfill(json=detail(item))
            return
        if req.method == "POST" and path == "/api/work-items/wrk_a/questions":
            questions.append(req.post_data_json)
            if reject_question:
                reject_question = False
                r.fulfill(status=409, json={"message": "다른 세션에서 변경했다. 다시 확인한다."})
            else:
                item["version"] += 1
                r.fulfill(json=detail(item))
            return
        if req.method != "GET":
            raise AssertionError(f"Unexpected write: {req.method} {path}")
        if path == "/api/auth/state":
            data = dict(mode="local", secure=False, person=None, csrf_token=None, setup_pending=False)
        elif path == "/api/me":
            data = dict(user_id="usr_owner", focus_on=False, first_screen_visible=False, relay_wait_seconds=30)
        elif path == "/api/work-items":
            data = list(items.values())
        elif path in [f"/api/work-items/{key}" for key in items]:
            data = detail(items[path.rsplit("/", 1)[-1]])
        elif path == "/api/settings/workflow":
            data = dict(skipped_stages=[])
        elif path == "/api/inbox":
            data = inbox
        elif path == "/api/review-settings":
            data = dict(org=dict(patterns=[], customized=False), default_patterns=[],
                        sample_rate=0, sample_daily_cap=0, promote_after_hours=24,
                        projects=[], proposals=[])
        else:
            data = []
        r.fulfill(json=data)

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch()
            page = browser.new_page(viewport={"width": 1440, "height": 900})
            page.route("**/api/**", route)
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(base + "#work?item=wrk_a")
            edit = page.get_by_role("button", name="내용 고치기", exact=True)
            expect(edit).to_be_visible()
            edit.focus()
            page.keyboard.press("Enter")
            form = page.locator("form.wd-edit").filter(has=page.get_by_label(re.compile(r"^다음 행동")))
            draft = "자동 조회와 저장 실패 뒤에도 남아야 하는 문장"
            form.get_by_label(re.compile(r"^다음 행동")).fill(draft)
            item.update(version=4, title="다른 세션이 고친 제목")
            merge = page.get_by_role("button", name="내 수정과 최신 내용 합치기", exact=True)
            expect(merge).to_be_visible(timeout=8000)
            expect(form.get_by_label(re.compile(r"^다음 행동"))).to_have_value(draft)
            expect(form.get_by_label("제목", exact=True)).to_have_value("작성한 작업 내용을 유지한다")
            save = page.get_by_role("button", name="고친 내용 저장", exact=True)
            expect(save).to_be_disabled()
            page.evaluate("window.scrollTo(0, 0)")
            page.screenshot(path=str(output / "draft-after-poll-1440.png"), full_page=True)
            checks.append("poll_keeps_draft_and_requires_explicit_merge")

            merge.click()
            expect(form.get_by_label("제목", exact=True)).to_have_value(item["title"])
            expect(form.get_by_label(re.compile(r"^다음 행동"))).to_have_value(draft)
            save.click()
            expect(page.get_by_role("alert")).to_contain_text("HTTP 409")
            expect(form.get_by_label(re.compile(r"^다음 행동"))).to_have_value(draft)
            expect(save).to_be_enabled()
            assert updates == [{"version": 4, "next_action": draft}], updates
            save.click()
            expect(edit).to_be_visible()
            assert updates == [{"version": 4, "next_action": draft}] * 2, updates
            assert item["title"] == "다른 세션이 고친 제목"
            checks.append("failed_save_keeps_draft_and_retry_only_sends_edited_fields")

            edit.click()
            expect(form.get_by_label(re.compile(r"^다음 행동"))).to_have_value(draft)
            form.get_by_role("button", name="그만두기", exact=True).click()
            page.get_by_role("button", name="질문 남기기", exact=True).click()
            question = "전송 실패 뒤에도 이 질문을 다시 보낼 수 있어야 한다"
            page.get_by_label(re.compile(r"^질문")).fill(question)
            send = page.get_by_role("button", name="질문 보내기", exact=True)
            send.click()
            expect(page.get_by_role("alert")).to_contain_text("HTTP 409")
            expect(page.get_by_label(re.compile(r"^질문"))).to_have_value(question)
            expect(send).to_be_enabled()
            page.evaluate("window.scrollTo(0, 0)")
            page.screenshot(path=str(output / "failed-question-1440.png"), full_page=True)
            send.click()
            expect(send).to_have_count(0)
            assert len(questions) == 2 and all(q["body"] == question for q in questions)
            checks.append("failed_question_is_preserved_and_success_closes_form")

            # Switching records never carries one work item's draft into another.
            edit.click()
            form.get_by_label(re.compile(r"^다음 행동")).fill("첫 작업에만 속하는 초안")
            page.locator('[data-row="wrk_b"]').click()
            expect(page.get_by_role("heading", name=other["title"], exact=True)).to_be_visible()
            expect(page.get_by_role("button", name="고친 내용 저장", exact=True)).to_have_count(0)
            edit.click()
            expect(form.get_by_label(re.compile(r"^다음 행동"))).to_have_value(other["next_action"])
            checks.append("switching_work_items_does_not_reuse_a_draft")

            page.set_viewport_size({"width": 390, "height": 844})
            page.goto(base + "#work?item=wrk_a")
            page.reload()
            dialog = page.get_by_role("dialog")
            expect(dialog).to_be_visible()
            expect(dialog.get_by_role("heading", name=item["title"], exact=True)).to_be_visible()
            page.wait_for_timeout(250)  # finish the sheet transition for the screenshot
            page.screenshot(path=str(output / "direct-link-390.png"), full_page=True)
            assert not page.evaluate("document.documentElement.scrollWidth > innerWidth")
            page.reload()
            expect(dialog).to_be_visible()
            expect(dialog.get_by_role("heading", name=item["title"], exact=True)).to_be_visible()
            checks.append("mobile_direct_link_and_reload_restore_detail")

            dialog.get_by_role("button", name="닫기", exact=True).click()
            expect(dialog).to_have_count(0)
            expect(page.locator('[data-row="wrk_a"]')).to_be_focused()
            page.wait_for_timeout(5500)
            expect(dialog).to_have_count(0)
            page.locator('[data-row="wrk_a"]').click()
            expect(dialog).to_be_visible()
            page.keyboard.press("Escape")
            expect(dialog).to_have_count(0)
            expect(page.locator('[data-row="wrk_a"]')).to_be_focused()
            checks.append("closing_stays_closed_during_poll_and_reopening_restores_focus")

            page.evaluate("location.hash = '#work?item=wrk_b'")
            expect(dialog.get_by_role("heading", name=other["title"], exact=True)).to_be_visible()
            page.go_back()
            expect(dialog.get_by_role("heading", name=item["title"], exact=True)).to_be_visible()
            page.go_forward()
            expect(dialog.get_by_role("heading", name=other["title"], exact=True)).to_be_visible()
            page.evaluate("location.hash = '#work'")
            expect(dialog).to_have_count(0)
            checks.append("mobile_hash_back_forward_and_clear_follow_selection")

            page.evaluate("location.hash = '#work?item=wrk_a'")
            expect(dialog.get_by_role("heading", name=item["title"], exact=True)).to_be_visible()
            edit.click()
            form.get_by_label(re.compile(r"^다음 행동")).fill("휴대전화에서 작성 중인 내용")
            item["version"] += 1
            expect(merge).to_be_visible(timeout=8000)
            expect(form.get_by_label(re.compile(r"^다음 행동"))).to_have_value("휴대전화에서 작성 중인 내용")
            assert not page.evaluate("document.documentElement.scrollWidth > innerWidth")
            assert dialog.evaluate("e => e.scrollWidth <= e.clientWidth")
            merge.scroll_into_view_if_needed()
            page.screenshot(path=str(output / "draft-after-poll-390.png"), full_page=True)
            checks.append("mobile_draft_conflict_fits_without_horizontal_scroll")

            # Selection within a list is not navigation into a detail sheet.
            page.goto(base + "#work")
            page.reload()
            first, second = page.locator('[data-row="wrk_a"]'), page.locator('[data-row="wrk_b"]')
            expect(first).to_be_visible()
            first.focus()
            page.keyboard.press("ArrowDown")
            expect(second).to_be_focused()
            expect(second).to_have_attribute("aria-current", "true")
            expect(dialog).to_have_count(0)
            page.keyboard.press("Enter")
            expect(dialog.get_by_role("heading", name=other["title"], exact=True)).to_be_visible()
            checks.append("mobile_arrow_selects_without_opening_and_enter_opens")

            page.set_viewport_size({"width": 1440, "height": 900})
            page.goto("about:blank")  # new document: no old hash effect can race a reload
            page.goto(base + "#work")
            expect(first).to_have_attribute("aria-current", "true")
            expect(page).to_have_url(re.compile(r"[?&]item=wrk_a(?:&|$)"))
            page.set_viewport_size({"width": 390, "height": 844})
            expect(dialog).to_have_count(0)
            checks.append("wide_unaddressed_entry_auto_selects_without_opening_sheet_after_resize")

            page.goto(base + "#inbox?item=inb_a")
            expect(dialog).to_be_visible()
            dialog.get_by_role("button", name=re.compile(r"^확인")).click()
            expect(dialog).to_have_count(0)
            expect(page.locator('[data-row="inb_b"]')).to_be_focused()
            page.wait_for_timeout(5500)
            expect(dialog).to_have_count(0)
            page.screenshot(path=str(output / "inbox-after-judgement-390.png"), full_page=True)
            checks.append("mobile_inbox_judgement_closes_and_focuses_next_row")

            inbox[:] = notices
            page.goto(base + "#inbox?item=inb_a")
            page.reload()
            expect(dialog).to_be_visible()
            dialog.get_by_role("button", name="닫기", exact=True).click()
            expect(page.locator('[data-row="inb_a"]')).to_be_focused()
            inbox[:] = [notices[1]]
            expect(page.locator('[data-row="inb_a"]')).to_have_count(0, timeout=8000)
            expect(page.locator('[data-row="inb_b"]')).to_have_attribute("aria-current", "true")
            expect(dialog).to_have_count(0)
            checks.append("mobile_inbox_closed_sheet_stays_closed_when_item_leaves_on_poll")

            page.goto(base + "#risk?item=org")
            expect(dialog.locator(".dt-title")).to_have_text("조직 기본")
            expect(dialog.locator(".dt-title")).to_be_visible()
            page.reload()
            expect(dialog.locator(".dt-title")).to_have_text("조직 기본")
            expect(dialog.locator(".dt-title")).to_be_visible()
            page.screenshot(path=str(output / "risk-direct-link-390.png"), full_page=True)
            checks.append("mobile_risk_direct_link_and_reload_restore_detail")
            assert not errors, errors
            browser.close()
        result = {"passed": checks, "page_errors": errors}
        (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
