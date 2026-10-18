# 독립 검토 기록: work/preview-review-fixes (PR46·PR47·PR48)

## AI 검토 결과

- **적용한 기준**: zm-baton 검토 기준 v0.5와 `AGENTS.md`
- **검토자**: Claude Code CLI 2.1.302 / 모델 Claude Opus 5.5(`claude-opus-5-5`) / 세션 `310d4548-ab92-42da-8e00-037f41b2225e`
  - 역할은 다른 실행기에서 하는 읽기 전용 독립 검토다.
  - 코드와 파일은 고치지 않았고, 시험도 직접 돌리지 않았다(지시).
- **작성**: Codex CLI 0.162.1 / GPT-6 / 세션 `01a13c92-51f2-77d2-9414-39fce9e7aefc` / 개인 구독 개발 환경
- **독립성 근거**
  - 실행기(Codex CLI와 Claude Code CLI)와 세션 번호가 둘 다 다르다.
  - 이전 검토 두 세션(`6c7a26ef-…`, `52f62b9d-…`)과도 다른 세션이다.
  - 작성 대화를 이어받지 않았다. 읽은 것은 다음뿐이다.
    - 지시서
    - PR 본문 초안 `pr-review-fixes.md`
    - 통합 diff `changes.patch`
    - 시험 요약 JSON
    - 작업 폴더의 원본
    - 이전 검토 기록 두 개
- **검토한 커밋**: 작업 폴더 HEAD는 `d8b15a6`(`work/preview-review-fixes`)이고, 세션을 시작할 때 작업 폴더는 깨끗했다.
  - PR46: `03498cd → a193fdc`
  - PR47: `a193fdc → 6b320d4`
  - PR48: `6b320d4 → d8b15a6`
- **판정**

  | PR | 커밋 | 기술 판정 | 막는 지적 |
  |---|---|---|---|
  | PR46 | `a193fdc` | **병합 가능** | 없음 |
  | PR47 | `6b320d4` | **병합 막음(고친 뒤 병합 가능)** | N1 |
  | PR48 | `d8b15a6` | **병합 가능** | 없음. 단 아래 「병합 전 조건」 N6·N7이 남는다 |

  - PR48은 PR47 위에 쌓여 있다. PR47을 고치면 PR48을 다시 쌓고, 내용 차이가 없는지 다시 확인해야 한다.
  - 「판정 불가」는 없다.
- **소유자 호출 조건**: 세 PR 모두 **해당**한다.
  - **PR46**
    - 조건 1: `apps/server/src/main/kotlin/io/github/hanumoka/zmbaton/server/auth/Devices.kt`(경로 `**/auth/**`)
  - **PR47**
    - 조건 2: `apps/server/src/main/resources/db/migration/V16__notice_inbox_recipients.sql`(DB 구조)
  - **PR48**
    - 조건 1, 경로 표: `server/RunService.kt`와 `server/permission/` 아래 네 파일(`MasterVisibility.kt`, `PermissionRules.kt`, `Permissions.kt`, `RecordVisibility.kt`)
    - 조건 1, 변경 종류 「권한」(v0.4 확장 1): `server/MasterAccess.kt`, `server/MasterApi.kt`, `server/MasterChecks.kt`, `server/Questions.kt`
    - 조건 2: `deploy/worker/zm-baton-worker.service`
  - 이 기록에서 `server/`는 `apps/server/src/main/kotlin/io/github/hanumoka/zmbaton/server/`를 줄인 것이다.
- **소유자 호출 근거**
  - PR48: 물음·결정 대기. PR 본문 초안의 「소유자 호출」 칸에 조건 1·2와 2026-10-18 Goal 위임 범위가 적혀 있다.
  - PR46·PR47: 본문을 받지 못해 「확인 못 함」이다.
  - 세 PR 모두 PR 댓글과 소유자 결정 댓글은 확인 못 함이다.
- **경로 판정 방법의 한계**
  - 이 세션에는 셸이 없다. 그래서 `git diff --no-renames`와 pathspec 명령을 돌리지 못했고, 통합 diff의 파일 목록을 경로 표와 손으로 맞췄다.
  - 커밋별 파일 배정도 커밋 메시지, PR 본문, 변경 내용으로 추정한 것이다.

---

## 1. PR별 판정

| PR | 범위 | 기술 판정 | 병합 전에 남은 일 |
|---|---|---|---|
| PR46 `a193fdc` | 기기 승인·등록·재발급·poll의 구성원 잠금, OS별 프로세스 종료 문서 | **병합 가능** | 조건 1 소유자 결정. 재활성화 뒤 기존 비밀번호를 유지할지 정책 확정(§5) |
| PR47 `6b320d4` | V16 수신자별 유일 인덱스, 충돌·모순·사용량 알림의 수신자별 항목, 시작할 때 미전달 복구 | **병합 막음** | N1을 고친 뒤 다시 검토. 조건 2 소유자 결정(운영 적용과 되돌리는 방법) |
| PR48 `d8b15a6` | 마스터 복사 자료의 원본 권한, 청구 직전 재검사, 질문 수신자 거절, 상태 카드·계정 이력 거름, 서버 작업자 시험, systemd 130 | **병합 가능** | `d8b15a6`에서 CI 통과 확인(N6). 고도화 후보 이관(N7). 조건 1·2 소유자 결정 |

---

## 2. 지적

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| N1 (PR47) | `server/Usage.kt:499-524`(특히 `:507-510`, `:516-523`). 계정 단위 중복 키 `:267-271`, `:303-308`, `:363-366`, `:456-459`. 판정 권한 `server/permission/Permissions.kt:254-260`, `server/permission/PermissionRules.kt:128`, `server/InboxApi.kt:245-254`. 같은 모양 `server/ContradictionNotices.kt:141-166` | 1, 3 | **새로 첫 화면 항목을 받은 사람이 그 알림을 판정할 수 없다.**<br>• 사용량 알림의 계정 단위 중복 키(`usage_alert\|account\|…\|acc\|날짜` 등)는 작업과 무관하다. 그런데 받는 사람은 그때 실행한 작업의 책임자다(`Usage.kt:501`).<br>• 조직 계정과 API 키 계정은 남의 작업에도 쓸 수 있다(`Accounts.kt:346-348`이 막는 것은 개인 구독뿐이다). 그래서 같은 날 같은 계정으로 A와 B의 실행이 막히면, 두 번째 올림의 받는 사람이 B가 된다.<br>• `on conflict (dedupe_key) do update`는 `recipients`를 갱신하지 않는다. 알림의 받는 사람 목록은 `[A]` 그대로다.<br>• PR47 전에는 `(source_id)` 유일 인덱스 때문에 A의 항목이 열려 있는 동안 B 항목이 생기지 않았다. PR47 뒤에는 B에게 즉시 알림 항목이 생긴다.<br>• 알림 판정(`NOTICE_JUDGE`)은 받는 사람 목록에 든 사람이나 마스터 관리자만 할 수 있다. 그래서 B는 403을 받고, 자기 항목을 닫을 방법이 없다. 알림 항목을 사람별로 닫는 경로는 없다(검색으로 확인).<br>• 문서 모순 알림도 다시 올릴 때 `recipients(c)`를 새로 계산하지만 받는 사람 목록은 갱신하지 않는다. 이쪽은 문서 주인이 바뀌었을 때만 생겨 드물다.<br>**고칠 것**<br>• 다시 올릴 때 받는 사람 목록에 새 사람을 합친다(예: `recipients = array(select distinct unnest(notices.recipients \|\| excluded.recipients))`). 모순 알림의 갱신 분기도 같이 고친다.<br>• 또는 계정 단위 알림의 받는 사람을 하나로 고정한다.<br>• 두 사람이 같은 계정을 쓰다 막히는 시험(둘 다 받음, B도 판정 가능, 함께 닫힘)을 더한다.<br>• H-47 상태와 `docs/stage1/notice-recipients.md`를 맞춘다. | 높음(코드로 직접 추적했고 실행하지 않음) | **예** |
| N2 (PR48) | `server/MasterApi.kt:238-239`, `server/MasterAccess.kt:27-29`, `server/MasterChecks.kt:360-371`, `:228-245`, `server/RunService.kt:135-137` | 1, 6 | **API 경로가 원본 권한 때문에 거절되면 정리 작업이 이를 「API 장애」로 보고 로컬 경로로 넘긴다.**<br>• 거절된 실행은 `cancelled`로 끝난다. 정리 작업은 이 실행을 `path='api'`, `api_run_id is null`, 제출 아님으로 보고 `startLocal`을 부른다.<br>• 그러면 같은 지시문(비교 자료 포함)으로 소유자 기기에 위임과 실행을 하나 더 만들고, 감사 기록에 `master_check.fallback`을 남긴다.<br>• 기기가 청구하면 다시 거절된다. 최종 사유는 「마스터 실행이 끝났지만 결과가 없다(취소)」이다. 기기가 없으면 「API 경로가 실패했고 로컬 경로로도 넘기지 못했다」로 실패한다.<br>• 자료는 실행기에 가지 않는다(닫힌 쪽으로 처리됨). 남는 문제는 사유 문구가 사실과 다르고 위임이 하나 더 남는다는 것이다.<br>• API 경로에서 거절한 뒤 정리 작업이 어떻게 하는지 보는 시험이 없다. | 높음(코드), 실행 안 함 | 아니오. 안내 문구·견고성이라 고도화 후보(DEC-0106) |
| N3 (PR48) | `server/MasterAccess.kt:12-31`, `server/RunService.kt:135-140`, 비교 대상 `server/Usage.kt:259-263` | 4, 11 | • 원본 권한으로 거절할 때 위임을 거두지 않고 대기 사유도 달지 않는다. 계정 거절(`Usage.refuseClaim`)과 처리 방식이 다르다.<br>• 재검사와 `claimNow`가 서로 다른 트랜잭션이다. 둘 사이에 원본 권한이 좁아지면 통과한다(드문 타이밍). | 높음 / 중간 | 아니오(고도화 후보) |
| N4 (PR48) | `server/Questions.kt:195-197`, `:206-210`, `apps/server/src/test/kotlin/io/github/hanumoka/zmbaton/server/PermissionScopeTests.kt:163-171` | 7 | • 실행이 남긴 질문은 결과 검토 단계에서 상태 전이를 먼저 한 뒤 새 수신자 검사로 거절된다. `@Transactional`과 `ConflictException : RuntimeException`(`Api.kt:161`)이 함께 되돌리므로 코드로는 맞다.<br>• 그러나 시험은 사람이 묻는 경로만 본다. 그 경로는 검사 전에 아무것도 쓰지 않아서, 「버전이 그대로다」라는 단언이 되돌림을 실제로 확인하지 못한다. | 중간 | 아니오 |
| N5 (PR48 문서) | `docs/stage1/server-worker.md:59-60` | 12 | 이전 지적 R1이 남았다.<br>• 「Unix 자식 프로세스 처리는 H-16」으로 Linux와 macOS를 묶었다.<br>• macOS에서는 실행기 본체도 멈추지 않는다(`kill_unix.go`가 정지를 거절한다). `server-administration.md:52-54`에는 이 구분이 있는데 서버 작업자 문서에는 없다.<br>• 단언이 틀린 것은 아니고 빠진 것이다. | 높음 | 아니오 |
| N6 (검증) | PR 본문 검증 표, 시험 요약 JSON | 결론 7·8, G26.2 | • 전체 304건은 「마지막 정리 전」 작업 트리의 결과다(JSON의 `scope`에 그렇게 적혀 있다). 45개 묶음을 더하면 304로 맞다.<br>• `d8b15a6` 자체에서는 17건(AuditApi 6 + PermissionScope 11)과 bootJar만 돌렸다.<br>• 「탐색 함수 줄바꿈 정리」가 본 코드(`RecordVisibility.kt` 등)에 닿았는지는 확인하지 못했다.<br>• PR48 CI는 아직 진행 중이다.<br>• **병합 직전에 `d8b15a6`에서 CI 다섯 작업이 모두 통과했는지 확인한다.** 표의 「돌린 판」에는 304건이 `d8b15a6` 이전 트리의 결과라고 분명히 적는다. | 높음 | 아니오(병합 전 조건) |
| N7 (절차) | `docs/hardening-backlog.md`(H-59에서 끝남) | 절차 | 이전 검토의 고도화 후보가 목록에 옮겨지지 않았다.<br>• 옮겨지지 않은 것: P3(삭제 흔적 숨김), P5(처리량), R4 `KillMode=mixed`, `credentialVars` 판 확인, restricted·team 허용 시험, 관리 검토의 F7·F8·F9.<br>• `privacy-preview.md`의 「현재 한계」에만 일부가 있다.<br>• 기준에 따라 병합 전에 옮기고, N2·N3·N8·N9도 더한다. | 높음 | 아니오(병합 전 조건) |
| N8 (기존·관련) | `server/Usage.kt:509`, `server/ContradictionNotices.kt:146`, `server/SessionService.kt:523`, `server/InboxApi.kt:190`, `server/permission/RecordVisibility.kt:64-70` | 1, 3 | 다시 올릴 때 `evidence`를 마지막 것으로 덮어쓴다. 첫 화면은 알림 근거로 보이는지를 판정한다. 그래서 A가 B의 작업을 볼 수 없으면 A의 기존 항목이 A에게서 사라질 수 있다. PR45부터 있던 동작이고, N1과 함께 고칠 것을 권한다. | 중간 | 아니오(고도화 후보) |
| N9 (처리량, 알려짐) | `server/permission/Permissions.kt:163-173`, `server/permission/MasterVisibility.kt:17-25`, `server/Managers.kt:116-122`, `server/Accounts.kt:192-197` | 8 | 다음 넷 때문에 처리량이 늘어난다.<br>• 작업 판정마다 jsonb 질의를 하나 더 한다.<br>• 작업 목록 질의마다 열람 가능한 원본 CTE를 만든다.<br>• 상태 카드가 항목마다 질의한다(N+1).<br>• 계정 이력을 제한 없이 훑는다.<br>측정하지 않았다고 문서에 적혀 있다. | 중간 | 아니오 |
| N10 (부채) | `docs/reviews/2026-10-18-work-preview-*-initial.md` | 절차 | 파일 이름이 `AGENTS.md` 3절 6의 `YYYY-MM-DD-<브랜치 이름>.md`와 다르다. | 높음 | 아니오 |
| N11 (절차) | `SessionService.kt` 737줄, `WorkItemService.kt` 686줄, `Questions.kt` 613줄, `Usage.kt` 589줄, `Accounts.kt` 542줄 | 8 | 500줄을 넘는다. 1,000줄을 넘는 파일은 없다. PR 본문에 이유가 없다. 본문 첫 줄에 「에이전트가 썼다」는 표시(G38.2)와 브랜치 이름도 보완한다. | 높음 | 아니오 |

---

## 3. 집중 검토 항목의 결과

| 항목 | 결과 | 근거 | 확신 |
|---|---|---|---|
| MasterVisibility SQL과 PermissionFacts의 동등성 | **일치** | • 두 경로 모두 같은 `REFERENCES` SQL로 참조를 뽑는다(`MasterVisibility.kt:6-11`, `Permissions.kt:164`).<br>• 참조 모양이 V12 주석과 맞는다(`V12__doc_contradictions.sql:20-24`). 주제는 `document_version`의 `document_id`를 쓰고, 자료는 `document`·`decision`의 `id`를 쓴다(`Contradictions.kt:392-399`).<br>• Kotlin은 모든 원본을 `requiredParents`로 요구한다(`PermissionRules.kt:201`). SQL은 「보이지 않는 원본이 하나도 없음」(`not exists`)을 바깥 괄호와 `and`로 붙인다(`:246-251`).<br>• 매개변수 이름이 겹치지만 같은 사람이라 값이 같다.<br>• 시험은 문서 공유 범위 5종 × 사람 6명으로 목록 SQL과 `canSee`를 대조한다(`PermissionScopeTests.kt:251-287`). | 높음(코드), 실행 안 함 |
| 삭제된 자료·없는 자료 | **닫힌 쪽으로 처리됨** | • Kotlin: 없는 원본이나 모르는 종류는 `Target("missing", orgId "", PERSONAL)`이 되어 `canSee`가 거짓이다(`Permissions.kt:166-172`).<br>• SQL: 왼쪽 결합 결과가 null이면 숨긴다. `document_id`가 null인 경우도 양쪽 모두 숨긴다.<br>• `material`·`subjects`는 NOT NULL이라 `\|\|`가 null이 되지 않는다(V12:21,24).<br>• 본 코드에 문서·결정을 지우는 경로가 없다(검색으로 확인). 손상된 참조에서만 이 경우가 생긴다. | 높음 |
| 자동 검사 후보와 청구 직전 재검사 | **동작함. N2·N3 남음** | • 조직 소유자가 볼 수 없는 주제는 건너뛴다(`MasterChecks.kt:150-154`).<br>• 비교 후보를 실행자 권한으로 거른다(`Contradictions.kt:358,368,383`).<br>• 로컬은 `RunService.kt:135-137`, API는 `MasterApi.kt:239`에서 재검사한다.<br>• 실행자는 기기의 사람이거나 조직 소유자다(`MasterAccess.kt:14-20`). 로컬 기기도 소유자 것에서 고르므로(`MasterChecks.kt:248-257`) 판정 대상이 같다. | 높음 |
| 질문 대상 판정과 트랜잭션 | **맞음** | • 묻는 사람의 열람을 먼저 확인한다. 그래서 숨긴 작업은 404를 먼저 받고 존재가 드러나지 않는다(`Questions.kt:553-555`).<br>• 잠금(`lockFacts`)을 잡은 뒤 같은 트랜잭션 안에서 검사하고, 쓰기 전에 거절한다(`:184`, `:206-210`).<br>• 다시 보내는 경로는 없다.<br>• 실행 경로 시험이 빠졌다(N4). | 높음 / 중간 |
| NoticeInboxRecovery | **재시작·중복·닫힘 보존이 맞음. 대상은 받는 사람 목록 안으로 한정** | • 여러 번 돌려도 결과가 같다(`not exists`와 `on conflict`, `NoticeInboxRecovery.kt:20-21,35`).<br>• `status='open'`인 알림만 보고, 알림 행을 잠근 뒤 다시 확인한다(`:19,26-28,34`).<br>• 판정은 모든 항목을 닫는다(`InboxApi.kt:213-218`). 사람별로 닫는 경로는 없고, 보존 규칙은 닫힌 항목만 지운다(`Retention.kt:171`).<br>• 잠금 순서(알림 → 항목)가 판정·올림과 같다. Spring 플러그인이 있어 트랜잭션이 적용된다(`build.gradle.kts:3`).<br>• N1의 B는 받는 사람 목록에 없어 복구 대상이 아니다. | 높음 |
| DeviceAuth 잠금 순서 | **일관됨** | • poll: 사람 행 → 로그인 요청 행 → 기기(`Devices.kt:259-261,266-271,288`).<br>• 승인·등록·재발급: 사람 행을 먼저 잠근다(`:118,126,212`).<br>• 비활성화: 사람 → 웹 로그인 → 기기 → 로그인 요청(`Org.kt:244-253`).<br>• 거꾸로 잠그는 경로를 찾지 못했다. 두 읽기 사이에 승인자가 바뀌면 「대기」를 돌려준다(`:283`). | 높음(코드), 실행 안 함 |
| 새 API의 열람 우회 | **찾지 못함** | • 새 엔드포인트가 없다.<br>• 실행 읽기는 `see`를 거친다(`ReadApi.kt:245,257`).<br>• 작업 거름(`WORK_ITEMS`)을 쓰는 여덟 곳이 모두 `PermissionRules.filter`를 통해 원본 거름을 받는다.<br>• 상태 카드, 매니저 목록, 계정 실행·이력, 내보내기도 거른다. | 높음 |
| 문서·시험 근거의 사실성 | **대체로 일치. N5·N6 남음** | • 시험 묶음 합계와 17건의 구성이 맞다.<br>• `notice-recipients.md`의 절차가 코드와 맞다. 다만 사용량 알림의 받는 사람 목록이 맞지 않는 문제(N1)는 적혀 있지 않다. | 높음 |

---

## 4. 이전 지적에 대한 대응 대조

| 지적 | 대응 위치 | 결과 |
|---|---|---|
| R1 OS별 종료 | `server-worker.md:59-60`, `server-administration.md:52-54` | 대부분 해결. 서버 작업자 문서의 macOS 구분이 남았다(N5) |
| R2 거절 시험 | `server_worker_test.go:13,47-50`. 같은 준비로 허용 시험(`:56-72`)이 제출에 성공 | 해결(코드). 정책 조건을 지운 복사본으로 시험이 실패함을 본 실험은 주장만 받았다 |
| R3 종료 78 | `cmd/zm-baton/server_worker_test.go:15-43` | 해결(코드). 실행은 확인하지 않았다 |
| R4 systemd 130 | `zm-baton-worker.service:19` | 반영. 실제 설치는 확인하지 않았다. `KillMode=mixed` 검토는 남았다(N7) |
| R5·R6 | `handoff.go:216-217`, `server-worker.md:60` | 해결 |
| P1 마스터 복사본 | §3 | 해결. N2·N3이 남았다 |
| P2 질문 수신자 | `Questions.kt:206-210` | 해결(정책 확정 대기) |
| P3·P5 | `privacy-preview.md:44-45` | 바꾸지 않았다. 한계로 적었고 고도화 목록에는 옮기지 않았다(N7) |
| P4·P6·P7·부채(`viewer` 기본값) | `Managers.kt:115-122`, `Accounts.kt:171-197`, `RecordVisibility.kt:34-53`, 고도화 목록 H-45·H-46 | 해결 |
| F4·H-56 | PR46 | 해결. 비밀번호 유지 정책은 대기 |
| F2·F14 | 문서, `ServerAdministrationTests` | 해결 |
| F6 | 승인 로그인 거절 시험(`DeviceIssuanceTests.kt:64`) | 일부만 해결. ①③④⑤가 남았다 |
| F1 | Windows 실행 | 주장만 받았다(기록 파일 없음) |
| F5·F8·F13 | — | 바꾸지 않았다(정책·고도화) |
| F7·F9·F11·F12(UI) | — | UI 후속은 별도로 진행 중이다. **이번 수정으로 끝난 것으로 보지 않는다** |
| H-47 | PR47 | 대부분 해결. N1이 남았다 |

---

## 5. 소유자 결정 대기 (기술 결함과 따로 적음)

1. **PR46**
   - 조건 1 결정.
   - 다시 활성화한 사람이 기존 비밀번호로 로그인하게 둘지(H-56의 남은 부분).
2. **PR47**
   - 조건 2 결정.
   - V16 운영 적용과 되돌리는 방법. 되돌리려면 적용 전 백복원과 이전 서버 판을 함께 써야 한다.
3. **PR48**
   - 조건 1·2 결정.
   - 임시 정책 확정. 다음 다섯을 정한다.
     - (a) 받을 사람이 상위 작업을 볼 수 없으면 질문 생성을 거절한다.
     - (b) 조직 소유자가 볼 수 없는 원본이면 자동 검사를 건너뛴다.
     - (c) 원본 하나만 좁아져도 끝난 마스터 작업과 실행 전체를 숨긴다.
     - (d) 이미 실행기에 전달된 내용은 회수하지 못한다는 것을 받아들인다.
     - (e) systemd `SuccessExitStatus=130`을 운영에 적용한다.
4. **미확인 항목**
   - 조직 API 실제 실행, Linux systemd 실제 설치, Unix 자식 프로세스 종료(H-16)는 미확인이다.
   - 지시에 따라 다른 작업을 막는 이유로 삼지 않는다.
5. **이전 PR44·PR45의 결정 대기 항목**은 그대로 남아 있다.

## 6. 고도화 후보 (병합 전에 `docs/hardening-backlog.md`로 옮길 것)

- **N2**: 원본 권한 거절을 API 장애와 구분한다.
  - 예: 거절할 때 마스터 검사를 「원본 권한 변경」 사유로 실패시킨다.
  - 또는 `stopped_by`를 표시해 로컬로 넘기지 않게 한다.
- **N3**: 원본 권한 거절 때 위임을 거두고 대기 사유를 단다. 재검사와 청구를 한 트랜잭션으로 묶는다.
- **N8**: 다시 올릴 때 근거를 덮어써서 기존 받는 사람의 항목이 숨겨지는 문제를 고친다.
- **N9**: 처리량. 원본 판정을 일괄로 하고, 첫 화면 N+1을 없애고, 계정 이력을 SQL에서 제한한다.
- **이전 후보**: P3, P5, `KillMode=mixed`, `credentialVars`, restricted·team 허용 시험, F7·F8·F9.

## 7. 부채 후보

- N10: 검토 기록 파일 이름.
- N11: 500줄 초과 파일의 이유를 PR 본문에 적는다. 본문 첫 줄에 에이전트가 썼다고 표시한다.
- `MasterAccess.kt`가 `permission/` 밖에 있어 경로 표에 걸리지 않는다. 이번에는 변경 종류 표시로 호출 대상이 됐다.

## 8. 검증 근거 확인

- 시험 요약 JSON을 직접 확인했다.
  - 45개 묶음을 더하면 304건이고, 실패·오류·건너뜀이 0이다.
  - 다만 마지막 정리 전 트리의 결과다(N6).
- 다음은 기록 파일 없이 주장만 받았다.
  - Go 시험(Linux, Windows 교차 빌드)
  - 정책 조건을 지운 복사본으로 시험이 실패하는지 본 실험
  - PR46·PR47 CI
  - 본인 구독으로 한 실제 실행

## 9. 확인한 것

- 통합 diff 전체와 이전 검토 기록 두 개, `preview-review-followup.md`, `privacy-preview.md`, `notice-recipients.md`, `server-worker.md`, `hardening-backlog.md`.
- 작업 폴더 원본
  - `PermissionRules.kt`, `Permissions.kt`, `RecordVisibility.kt`, `MasterVisibility.kt`, `MasterAccess.kt`
  - `MasterChecks.kt`, `MasterApi.kt`(청구), `Contradictions.kt`(비교·자료), `ContradictionNotices.kt`(올림)
  - `Questions.kt`(묻기·라우팅), `NoticeInboxRecovery.kt`, `Usage.kt`(거절·올림), `SessionService.kt`(충돌)
  - `InboxApi.kt`(첫 화면·판정), `Devices.kt`, `Org.kt`(비활성화)
  - `Accounts.kt`(위임 계정), `Delegations.kt`(지시문), V12·V2·V16
  - Go `server_worker_test.go`, `internal/agent/handoff.go`(서버 작업자 검사), `cmd/zm-baton/handoff.go`
- 추가한 파일에 비밀값과 개인 절대 경로가 없다(검색으로 확인). 시험 토큰은 합성 값이다.
- 1,000줄을 넘는 손으로 쓴 파일이 없다. 새 의존성이 없다. 핵심 모듈 1·2·6을 고쳤지만 첫 병합은 아니다.

## 10. 확인하지 않은 것

- 시험, CI, `go vet`·`-race`, 실제 PostgreSQL 질의 계획. 하나도 돌리지 않았다.
- V15 백업에서 V16으로 실제로 올리는 업그레이드.
- `git` 기반 경로 판정과 커밋별 파일 배정(셸 없음).
- PR46·PR47 본문, PR 댓글, 소유자 결정 댓글.
- 웹 화면. 409 문구가 어떻게 보이는지는 UI 후속에서 본다.
- 조직 API, systemd, Linux·macOS 실제 동작.
- 추가 입력 폴더 밖의 산출물과 사용자 홈의 로그인 자료. 지시에 따라 열지 않았다.

---

- 적용한 검토 기준: zm-baton 검토 기준 v0.5
