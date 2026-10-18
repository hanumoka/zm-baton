# 독립 최종 검토 기록: `work/preview-final` (PR #41~#52 누적, `2d8e632`)

## AI 검토 결과

- **적용한 기준**: zm-baton 검토 기준 v0.5와 `AGENTS.md`. 웹 화면에는 `apps/web/DESIGN.md`도 적용했다.
- **검토자**: Claude Code CLI 2.1.302 / 모델 Claude Opus 5.5(`claude-opus-5-5`) / 세션 `5301b60f-0521-45c2-8792-bc90cf214631`
  - 역할: 읽기 전용 독립 최종 검토.
  - 파일을 고치지 않았다. 지시에 따라 시험과 Bash를 실행하지 않았다.
- **작성자**: Codex CLI 0.162.1 / GPT-6 / 세션 `01a13c92-51f2-77d2-9414-39fce9e7aefc` / 개인 구독 개발 환경
- **독립성 근거**
  - 실행기(Codex CLI와 Claude Code CLI)가 다르고 세션 번호도 다르다.
  - 이전 독립 검토 네 세션(`52f62b9d-…`, `6c7a26ef-…`, `310d4548-…`, `9084d7c6-…`)과도 다른 세션이다.
  - 작성 대화를 이어받지 않았다. 읽은 것은 다음뿐이다.
    - 추가 입력 폴더의 `prompt.txt`, `pr-final.md`, `changed-files.txt`, `last-fixes.patch`
    - 작업 폴더의 원본 파일
    - 저장소에 있는 이전 검토 기록 네 개
- **검토한 커밋**: `2d8e632dfe7f31acb2e161ad567ed55c46598785`(`work/preview-final`). 기준은 `main` = `f7897d7`이다.
  - 작업 폴더의 HEAD ref, 브랜치 ref, `main` ref 파일을 직접 읽어 확인했다.
  - 세션을 시작할 때 작업 폴더는 깨끗했다.
- **판정**: **병합 가능(기술 판정)**
  - 「병합 막음」 지적은 없다. 「판정 불가」도 없다.
  - 이 판정은 소유자 승인을 대신하지 않는다. 아래 「소유자 승인 대기」와 「병합 전에 남은 것」을 따로 본다.
- **소유자 호출 조건**: **해당**(조건 1·2). 아래 「소유자 호출 조건」 절에 걸린 파일을 적었다.
- **소유자 호출 근거**
  - PR 본문 「소유자 호출」 칸으로 보면 물음·결정 대기다.
  - GitHub PR 댓글은 읽지 못했다. 그래서 소유자 결정 댓글은 「확인 못 함」이다.

## 커밋 범위 확인

셸 없이 로컬 Git reflog 파일을 읽어 확인했다. 직선 이력이다.

| PR | 커밋 | reflog의 커밋 메시지 |
|---|---|---|
| #41 | `ed37fca` | `work/preview-setup` 기점 |
| #42 | `96f1bba` | guide the first administrator through setup |
| #43 | `d93074b` | add server management and a browser acceptance check |
| #44 | `a9be83b` | add bounded API-only server worker mode |
| #45 | `03498cd` | enforce visibility across related records and exports |
| #46 | `a193fdc` | serialize device issuance with member suspension |
| #47 | `6b320d4` | deliver notices to every recipient |
| #48 | `d8b15a6` | close preview review gaps in copied data and worker checks |
| #49 | `6827b8b`(rebase한 커밋)과 `46234e9` | preserve setup names…, retain setup recovery browser evidence |
| #50 | `69474ae` | refresh revoked administration and verify keyboard transfer |
| #51 | `9e2915b` | retain judgement rights for repeated notice recipients |
| #52 | `f73cb9d` | require real setup names and preserve pending edits |
| 조립 | `2d8e632` | docs: assemble independent server preview acceptance and rollback guide |

- UI 후속 검토는 「PR #48의 `AuthGate.tsx`·V15」라고 적었다. 커밋 메시지와 첫 관리 검토의 배정으로 보면 V15는 `ed37fca`(#41), `AuthGate.tsx`는 `96f1bba`(#42)에 들어갔다. `preview-review-followup.md:47`의 정정과 일치한다.
- 파일 단위 배정은 `git diff`로 확인하지 못했다(확인하지 않은 것 참조).

## 집중 항목 결과

| 항목 | 결과 | 근거 | 확신 |
|---|---|---|---|
| 백엔드 N1: 새 수신자가 알림 항목은 받는데 판정은 못 함 | **해결** | • `Usage.kt:510`이 `on conflict … do update`에서 `notices.recipients \|\| excluded.recipients`의 중복을 지우고 합친다.<br>• `ContradictionNotices.kt:126,148,155,159`가 새로 계산한 수신자를 갱신 분기에서도 합치고, 같은 목록으로 항목을 만든다.<br>• 판정 권한은 수신자 목록으로 정해진다(`Permissions.kt:254-260`, `PermissionRules.kt:128`). 그래서 목록에 들어간 사람은 판정할 수 있다.<br>• 파일 충돌 알림은 중복 키가 두 세션 쌍을 고정하므로 수신자가 바뀌지 않는다(`SessionService.kt:492-493`). 고칠 대상이 아니다. | 높음(코드) |
| 백엔드 N1 회귀 시험 | **사용량 알림만 가름** | • `NoticeRecipientsTests.kt:36-38`은 수신자 집합이 둘이고 중복이 없는지 단언한다. 고치기 전 코드에서는 실패한다.<br>• 모순 알림 쪽 시험은 고치기 전 코드에서도 통과한다(Z1). | 높음 |
| UI N1: 새 설치에서 시드 값 `owner`를 실제 이름처럼 채움 | **해결** | • `ServerSetup.tsx:29-30`이 `owner`를 빈칸으로 바꾼다. 그러면 `why`가 생겨 제출 단추가 잠긴다(`:20,44,85`).<br>• 합성 시험 `seed_names`는 빈칸, 잠긴 단추, Enter를 눌러도 제출 0건을 확인한다(`setup_recovery.py:117-118,131-134`).<br>• 실제 빈 DB 시험도 빈칸과 잠금을 단언한다(`server_admin.py:61-63`).<br>• 실제 빈 DB의 스크린샷 `setup-empty-1440.png`에서 새 머리 문구와 빈칸을 직접 확인했다. | 높음 |
| UI N2: 인증 상태를 다시 읽을 때 콜백이 바뀌어 입력과 오류가 지워짐 | **해결** | • 콜백을 ref에 담고(`ServerSetup.tsx:17-18`), effect는 `[refresh]`에만 의존한다(`:38`).<br>• 저장소의 기존 방식과 같다(`shell/Sheet.tsx:14`).<br>• `csrf_refresh` 사례는 다음을 단언한다(`setup_recovery.py:140-153`). 고치기 전 코드에서는 이 단언이 실패하므로 시험이 새 동작을 실제로 가른다.<br>  - 403 뒤 인증 상태를 다시 읽는다.<br>  - `server_reads == 1`이다.<br>  - 고친 이름과 거절 문구가 그대로 남는다.<br>• 결과 JSON에 10개 사례가 모두 통과로 있다. | 높음(코드와 시험 코드), 실행은 안 함 |

### 이전 「병합 막음」 지적과 대조

| 지적 | 결과 |
|---|---|
| 첫 관리 검토 F1: 자식 프로세스 종료 시험이 Linux에서 건너뛰어짐 | **해결.** `agent-review-windows.txt`에 `TestRevokedDevice…` PASS가 있다. 단 파일에 Go 판과 커밋이 없다 |
| 첫 관리 검토 F3: 다른 커밋에서 돌린 검증 | **해소.** 누적 판으로 제출해 해당 커밋 단독 병합이 없다 |
| 런타임 검토 R1: OS를 가리지 않는 종료 단언 | **해결.** `server-worker.md:59`, `server-administration.md:53-54` |
| 런타임 검토 R2: 거절 시험이 새 검사를 가르지 못함 | **해결.** 합성 키를 둔다(`server_worker_test.go:13`). 거절 사유의 「공용 서버 실행」 접두를 단언한다(`:47-50`) |
| 백엔드 후속 N1 | 해결(위 표) |
| UI 후속 N1 | 해결(위 표) |

- `d8b15a6` 뒤 Go 코드는 바뀌지 않았다(마지막 수정 patch에 `apps/agent`가 없다).
- 실제 실행에 쓴 작업자 `d93074b`와 최종 판의 차이는 `--server-worker`로 막아 둔 검사(`handoff.go:216-226`)와 종료 코드 78뿐이다. 그래서 개인 구독 경로의 실제 실행 증거는 최종 코드의 그 경로를 대표한다.

## 지적

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| Z1 | `apps/server/src/test/kotlin/io/github/hanumoka/zmbaton/server/MasterCheckTests.kt:290-293` | 7 | 모순 알림의 수신자 합치기를 시험이 가르지 못한다.<br>• 고치기 전에도 항목 생성 루프는 새 주인에게 항목을 만들었다.<br>• 판정은 권한 검사가 없는 서비스 `people.judgeNotice`를 직접 부른다.<br>• 그래서 수신자 목록을 바꾸지 않던 옛 코드에서도 통과한다.<br>• 수신자 목록 단언이나 HTTP 판정을 더해야 한다. | 높음 | 아니오 |
| Z2 | `NoticeRecipientsTests.kt:39`, `InboxApi.kt:251-253` | 7, 3 | 두 시험 모두 판정의 권한 경로(`NOTICE_JUDGE`)를 거치지 않는다.<br>• 권한 판정은 수신자 목록 단언과 기존 `PermissionRulesTests.kt:118`로 간접 확인할 뿐이다.<br>• 원래 지적의 증상(새 수신자의 403)을 직접 재현한 거부·허용 시험이 없다. | 높음 | 아니오 |
| Z3 | `docs/stage1/notice-recipients.md:12` / `Permissions.kt:254-260`, `PermissionRules.kt:225` | 12, 3 | 문서는 「새 사람도 근거 자료를 읽을 수 있어야 판정할 수 있다」고 쓴다.<br>• 그런데 알림 대상에는 `requiredParents`가 없다. API는 수신자이기만 하면 판정을 허용한다.<br>• 근거가 보이지 않으면 첫 화면에서 항목만 숨겨진다.<br>• 드러나는 자료는 없다. 다만 문서의 단언이 코드와 다르다. | 높음 | 아니오 |
| Z4 | `Usage.kt:517-524`, `ContradictionNotices.kt:159-168` / `NoticeInboxRecovery.kt:15-37` | 4 | 다시 열린 알림의 첫 화면 항목을 받는 사람이 서버 재시작 여부에 따라 달라진다.<br>• 다시 올릴 때 항목은 이번 발생의 사람에게만 생긴다.<br>• 시작할 때 복구는 합쳐진 수신자 전원의 빠진 항목을 채운다.<br>• V16부터 있던 비대칭이고, 이번 합치기로 범위가 조금 넓어졌다. H-60과 관련된다. | 중간 | 아니오(고도화 후보) |
| Z5 | `apps/web/src/server/ServerSetup.tsx:28` | 1 | 서버는 「설정 완료」로 답했는데 인증 상태가 아직 설정 대기이면 자리 표시만 남는다. 「다시 읽기」 단추가 없다.<br>• 인증 상태 읽기가 버려진 경우(H-69)에 이렇게 된다.<br>• 고치기 전에는 반복 요청이 났다. 지금은 멈춘 화면이다. 둘 다 드물다. | 중간 | 아니오(고도화 후보) |
| Z6 | `ServerSetup.tsx:11,29-30` | 설계 규칙 3·5 | 시드 값인지를 화면이 문자열 `'owner'`로 판별한다.<br>• 시드 값이 바뀌거나 실제 이름이 `owner`이면 어긋난다.<br>• 서버 응답이 「시드 값임」을 알려 주는 편이 맞다. | 높음(사실) | 아니오(부채 후보) |
| Z7 | `pr-final.md` 「핵심 모듈」 / `docs/core-modules/README.md:16` | 결론 9 기록 | 본문은 「모듈 1·2·3·4·6의 후속, 첫 병합 아님」이라고 쓴다. 그러나 모듈 4는 「코드 없음」이고, 이 변경에도 기억 모듈 코드가 없다(검색으로 확인). 본문에서 4를 빼야 한다. | 높음 | 아니오(PR 본문 정정) |
| Z8 | `pr-final.md` 검증 표의 서버 행 | 결론 7, G26.2 | 서버 전체 시험의 범위가 본문만으로 분명하지 않다.<br>• 전체 304건은 `d8b15a6`의 마지막 정리 전 트리에서 돌았다.<br>• `9e2915b`·`2d8e632`에서는 로컬 전체 시험이 없다. 48건과 10건을 골라 돌렸을 뿐이다.<br>• 그래서 전체 시험은 진행 중인 CI에 기댄다.<br>• 「최종 소스」라는 표현은 최종 HEAD로 읽힐 수 있다. | 높음 | 아니오(병합 전 조건) |
| Z9 | `docs/stage1/independent-preview-guide.md:45,47,48` / `docs/reviews/assets/2026-10-18-runtime-proof/results.json:41` | 12, G26 | 안내와 공개 근거가 다르다.<br>• 안내는 「`d8b15a6`와 같은 기능 빌드」라고 쓴다. 공개 근거에는 `uncommitted, base 6b320d4`라고 적혀 있다.<br>• 안내 `:48`과 본문 표가 적은 「V14→V16 업그레이드」 결과와 「브라우저 종료」 단계는 공개 `results.json`에 없다. 주장만 있다.<br>• 과장이라기보다 근거 표기가 불일치한다. 소유자의 조건 2 판단에 쓰이는 자료이니 요약을 추가하기를 권한다. | 높음 | 아니오 |
| Z10 | `independent-preview-guide.md:14` | 12 | 「기존 조직·관리자 이름을 확인」은 업그레이드에만 맞다. 새 설치는 이제 이름을 입력해야 한다. `server-administration.md:15`는 정확하다. | 높음 | 아니오 |
| Z11 | `README.md:15`, `independent-preview-guide.md:6` | 12 | 브랜치 이름과 「병합 전」 상태 문장이 squash 병합 뒤 `main`에서 틀린 말이 된다. 병합할 때 고친다. | 높음 | 아니오 |
| Z12 | `pr-final.md` 「소유자 호출」, 본문 전체 | 8, 절차 | 다음이 빠졌다.<br>• 500줄을 넘는 파일의 이유: `SessionService.kt` 737, `WorkItemService.kt` 686, `Questions.kt` 613, `Usage.kt` 590, `RunService.kt` 564, `Org.kt` 544, `Accounts.kt` 542, `runner.go` 794줄.<br>• 걸린 파일의 목록.<br>1,000줄을 넘는 파일은 없다. | 높음 | 아니오 |

## 소유자 호출 조건

`changed-files.txt`의 경로 끝부분을 경로 표와 손으로 맞췄다. `git diff --no-renames`는 돌리지 못했다.

- **조건 1(경로 표)**
  - `apps/server/…/server/auth/AuthApi.kt`, `…/auth/Devices.kt`
  - `apps/web/src/auth/AuthGate.tsx`
  - `…/server/permission/MasterVisibility.kt`, `PermissionRules.kt`, `Permissions.kt`, `RecordVisibility.kt`
  - `…/server/RunService.kt`
  - `apps/agent/internal/agent/runner.go`
- **조건 1(변경 종류 「권한·보안」, v0.4 확장 1)**
  - `server/admin/ServerAdministration.kt`
  - `MasterAccess.kt`, `MasterApi.kt`, `MasterChecks.kt`, `Questions.kt`
  - `Org.kt`, `SessionService.kt`, `Export.kt`, `ExportScope.kt`
  - `apps/agent/internal/agent/control.go`, `handoff.go`, `apps/agent/cmd/zm-baton/handoff.go`
- **조건 2**
  - `db/migration/V15__server_administration.sql`, `V16__notice_inbox_recipients.sql`
  - `deploy/compose/compose.yaml`, `deploy/worker/worker.env.example`, `deploy/worker/zm-baton-worker.service`
- **나머지 조건**
  - 조건 3: 해당 없음.
  - 조건 4: 작성 AI의 이견은 아직 없다.
  - 조건 5: CI가 끝나기 전에 병합하면 결론 7의 면제 시도가 된다.
- `.github/**`, `AGENTS.md`, `**/.gitignore`의 변경은 없다. 검사를 끄거나 약하게 하지 않았다.

## 소유자 승인 대기 (기술 판정과 별개)

1. 조건 1·2의 병합 결정을 PR 댓글로 남긴다.
2. V15·V16의 운영 적용과 되돌리는 방식을 정한다. 적용 전 백업과 이전 이미지를 한 쌍으로 쓴다.
3. 임시 정책을 정한다.
   - 서버 관리자 1명과 이전 방식, 조직 소유권과의 분리.
   - 구성원 정지의 범위, 재활성화 때 기존 비밀번호를 유지할지(H-56의 남은 부분).
   - 질문 수신자와 마스터 원본의 거절.
   - 삭제된 대상의 감사 행을 숨길지(H-61).
   - 서버 작업자의 API 키 전용과 상한 셋 필수.
   - systemd의 `SuccessExitStatus=130`과 `KillMode`.
4. 기존 `required` 설치가 다음 로그인 때 한 번 설정 화면을 거치는 동작을 받아들일지 정한다(첫 관리 검토 F5).
5. 화면 A/B/C 가운데 어느 배치로 할지 정한다. 다른 안을 고르면 다시 검토한다.
6. 경로 표에 `**/admin/**`와 `apps/agent/cmd/zm-baton/**`를 더할지 정한다(정책 문서 변경).
7. 소유자가 미룬 확인: 조직 API 실제 실행, Linux systemd·macOS 확인.

## 병합 전에 남은 것

1. 정확히 `2d8e632`에서 CI 다섯 작업이 통과한 것을 PR에 적는다(Z8).
2. PR 본문을 고친다(Z7, Z12). 본문만 고치면 새 커밋이 생기지 않는다.
3. 고도화 후보를 `docs/hardening-backlog.md`로 옮기고 문서를 고친다(Z3, Z9~Z11).
   - 이것은 새 커밋이라 다시 검토받아야 한다.
   - 예외는 `docs/reviews/`의 검토 기록만 바뀐 경우다.
4. 소유자 결정 댓글을 남긴다(위 절).
5. 이 기록을 `docs/reviews/2026-10-18-work-preview-final.md`로 보존한다. 이 파일만 더하면 다시 검토받지 않아도 된다.

## 고도화 후보

- **Z4**: 다시 올릴 때 첫 화면 항목을 만드는 대상과, 시작할 때 복구가 채우는 대상을 맞춘다. H-60과 함께 정한다.
- **Z3**: 알림 판정에 근거 열람을 요구할지 정한다. 요구하지 않으면 문서를 고친다.
- **Z5**: 설정이 끝났다는 응답을 받은 뒤에도 인증 상태가 설정 대기이면 「다시 읽기」를 보이거나 다시 읽기를 예약한다. H-69와 함께 본다.

## 부채 후보

- **Z6**: 서버 응답에 「시드 값임」 표시를 둔다.
- **Z1·Z2**: 모순 알림의 수신자 목록 단언을 더하고, 새 수신자의 HTTP 판정 허용 시험을 더한다.
- **UI 후속 N8**: `ServerSetup`·`ServerManagement`의 Vitest 단위 시험이 없다. N1·N2 회귀 시험은 CI 밖의 브라우저 스크립트에만 있다. 고도화 목록에도 아직 없다.
- **인지부채**: 「서버 관리자 1명」 정책을 정한 이유가 결정 기록에 없다.

## 안내 문서와 README의 정확성

- **기능**: 대체로 정확하다. 예외는 Z10이다.
- **절차**: 확인 절차, 반영 절차, 되돌리기 절차에 전제조건, 단계, 실패 대응이 있다.
  - 「V16 DB에 이전 코드만 연결하지 않는다」는 맞다. `on conflict` 조건이 새 인덱스와 다르기 때문이다(`V16:3-6`).
  - `update.ps1`이 이전 이미지에 이름을 붙여 남기는 동작과도 맞다.
- **한계**: 과장이 없다.
  - Windows만 검증했다고 쓴다.
  - Linux는 실행기 본체만 멈추고, macOS는 멈추지 않는다고 쓴다.
  - 조직 API와 systemd는 미검증으로 표시했다.
- **백업 관계**: 실제 되돌리기는 물리 백업으로만 시험했다. 일반 절차인 `pg_dump`/`pg_restore`로 V16에서 V14로 되돌리는 것은 이번에 돌리지 않았다. 안내도 그렇게 밝힌다.

## 확인한 것

- `AGENTS.md`, 검토 기준 v0.5 전문, `pr-final.md`, `changed-files.txt`, `prompt.txt`.
- `last-fixes.patch` 전체. 그 안의 검토 기록 부분은 저장소 원본으로 읽었다.
- 이전 검토 기록 네 개, `preview-review-followup.md`, `hardening-backlog.md`(H-16, H-44~H-72).
- 원본 코드
  - 서버: `ServerAdministration.kt`, `AuthApi.kt`, `Usage.kt`(알림 올리기), `ContradictionNotices.kt`, `SessionService.kt`(충돌), `InboxApi.kt`(판정), `NoticeInboxRecovery.kt`, `Permissions.kt`·`PermissionRules.kt`의 알림·판정 부분, V15·V16.
  - 시험: `NoticeRecipientsTests.kt`, `MasterCheckTests.kt`의 해당 시험.
  - 웹: `ServerSetup.tsx`, `AuthGate.tsx`, `ServerManagement.tsx`.
  - Go: 서버 작업자 시험 두 개, 두 `handoff.go`의 해당 부분.
  - 배포: `compose.yaml`, systemd unit, `worker.env.example`.
- 문서: 안내 문서, README, `server-worker.md`, `server-administration.md`, `notice-recipients.md`, `core-modules/README.md`.
- 공개 근거: `results.json`, `notice-reopen-targeted-summary.json`, 시드 결과 JSON 두 개, Windows Go 출력 두 개.
- 스크린샷 네 장: `setup-after-seed_names-1440/390`, `setup-empty-1440`, `setup-variant-c-1440`.
- 공개 문서·시험·배포 파일에서 비밀값, 이메일, 개인 절대 경로를 검색했다. 찾은 것이 없다.
- 1,000줄을 넘는 손으로 쓴 파일이 없다. 새 의존성이 없다. 계약(`packages/contracts`)을 바꾸지 않았다.

## 확인하지 않은 것

- **시험을 하나도 실행하지 않았다.** Gradle, npm, Go, Playwright, CI 모두다. 수치는 작성자의 진술과 공개 JSON으로만 봤다.
- **`git diff --no-renames`를 쓰지 못했다.**
  - 경로 조건은 diffstat의 경로 끝부분으로 맞췄다.
  - 커밋별 파일 배정도 diff로 확인하지 못했다.
  - `2d8e632`가 문서만 바꿨다는 것은 커밋 메시지와 patch 내용으로만 확인했다.
- **GitHub에서 보지 못한 것**: PR 본문 원본, 댓글, 소유자 결정, CI #41~#52와 최종 HEAD의 결과.
- **원본이 없어 진술로만 받은 것**: 실제 AI 실행 원본 로그, DB 백업, V14→V16 업그레이드 원본 결과, 실제 빈 DB 시험을 돌린 커밋.
- Windows Go 출력에는 Go 판과 커밋이 없다.
- 이전 검토 이후 바뀌지 않은 코드는 깊게 다시 읽지 않았다. 원본 권한 SQL, `Devices.kt`, `Org.kt`, `Export*`, `RecordVisibility.kt`, `Questions.kt`, `admin_recovery.py`, `Me.tsx`, `Shell.tsx`가 여기에 든다.
- 나머지 스크린샷.
- 비공개 저장소에 있는 협업 계약 v1과 위협 모델.
- 조직 API, Linux systemd, macOS의 실제 동작.
- 지시에 따라 열지 않은 것: 추가 입력 폴더 밖의 임시 자료와 로그인 자료.

---

- 적용한 검토 기준: zm-baton 검토 기준 v0.5
