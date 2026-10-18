# 독립 검토 기록 — work/preview-runtime(PR44) · work/preview-privacy(PR45)

저장 위치 제안: `docs/reviews/2026-10-18-work-preview-runtime.md`, `docs/reviews/2026-10-18-work-preview-privacy.md`. 두 PR에 같은 기록을 적용한다.

## AI 검토 결과

- **적용한 기준**: zm-baton 검토 기준 v0.5
- **검토자**: Claude Code CLI 2.1.302(지시서에 적힌 판) / 모델 Claude Opus 5.5(`claude-opus-5-5`) / 세션 `6c7a26ef-dada-4df8-812c-b30c68514270` / 역할: 다른 실행기의 독립 검토. 코드는 고치지 않았고 시험도 돌리지 않았다.
- **작성**: Codex CLI 0.162.1 / GPT-6 / 세션 `01a13c92-51f2-77d2-9414-39fce9e7aefc` / 개인 구독 개발 환경
- **독립성 근거**: 실행기(Codex CLI ↔ Claude Code CLI)와 세션 번호가 모두 다르다. 이 세션은 작성 대화를 이어받지 않았다. 지시서, PR 본문 초안 두 개(`pr-runtime.md`, `pr-privacy.md`), 통합 diff(`review-runtime-privacy.patch`), 작업 폴더의 소스만 읽었다.
- **검토한 커밋**: PR44는 `d93074b → a9be83b`, PR45는 `a9be83b → 03498cd`. 작업 폴더 HEAD는 `03498cd`다.
- **판정**
  - PR44 `a9be83b`: **병합 막음(고친 뒤 병합 가능)**. 막는 지적은 R1·R2 두 건이다.
  - PR45 `03498cd`: **병합 가능(기술 판정)**. 막는 기술 결함은 없다. 다만 소유자 호출 조건 1에 걸려 소유자 결정 전에는 병합하지 않는다.
- **소유자 호출 조건**
  - PR44: **해당**.
    - 조건 1: `apps/agent/internal/agent/runner.go`(경로 표 「권한·보안」).
    - 조건 2: `deploy/compose/compose.yaml`, `deploy/worker/worker.env.example`, `deploy/worker/zm-baton-worker.service`(`deploy/**`).
    - `apps/agent/internal/agent/handoff.go`는 경로 표에는 없지만 변경 종류 「보안」으로 걸린다.
  - PR45: **해당**.
    - 조건 1: `apps/server/src/main/kotlin/io/github/hanumoka/zmbaton/server/permission/PermissionRules.kt`, `…/permission/Permissions.kt`, `…/permission/RecordVisibility.kt`(`**/permission/**`). 변경 종류 「권한」「보안」으로도 걸린다.
    - 조건 2: 해당 없음. 마이그레이션과 배포 설정 변경이 없다.
- **소유자 호출 근거**: 물음·결정 대기(PR 본문 초안의 「소유자 호출」 칸). PR 댓글은 확인하지 못했다.
- **경로 판정 방법의 한계**: 이 세션에는 셸이 없어 `git diff --no-renames --name-only`와 pathspec 명령을 돌리지 못했다. 통합 diff의 파일 목록을 경로 표와 손으로 맞췄다. 커밋별 파일 구분도 PR 본문과 파일 종류로 나눈 것이다.

---

## 1. 커밋별 판정

| 커밋 | 범위 | 기술 판정 | 병합 전에 남은 일 |
|---|---|---|---|
| `a9be83b` (PR44) | server-worker, 상한, 종료 코드 78, systemd 예시, PostgreSQL restart | **고친 뒤 병합 가능** | R1·R2를 고치고 다시 검토 → 조건 1·2 소유자 결정(PR 댓글) |
| `03498cd` (PR45) | 연결 목록, 상위 작업, 내보내기, 감사, 모순 검사, 첫 화면의 열람 경계 | **병합 가능** | 조건 1 소유자 결정. 임시 정책 확정(§4). 고도화 후보를 `docs/hardening-backlog.md`로 옮김. 이 반영 커밋은 검토 기록 파일이 아니므로 다시 검토받는다 |

- PR45는 PR44 위에 쌓였다. R1·R2를 고쳐 PR44에 새 커밋이 생기면 PR45도 다시 쌓아야 한다. 그때 「내용 차이 없음」 확인이 필요하다.
- 「판정 불가」는 없다.

---

## 2. PR44 (`a9be83b`) 기술 지적

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| R1 | `docs/stage1/server-worker.md:59-60`, `:65` | 12(문서 사실), 4 | **OS를 가리지 않는 단언**: 「웹의 실행 취소는 … 실행기와 자식 프로세스를 멈춘다」, 「인증이 거절돼도 실행기를 멈춘다」.<br>Linux에서는 다르다. `apps/agent/internal/proc/kill_unix.go:28-31`과 `:69`가 실행기 하나만 멈춘다. `:75-82`는 하위 프로세스가 끝났는지 알 수 없다(H-16, 열림).<br>macOS에서는 `/proc`이 없어 `Kill`이 거절된다(`kill_unix.go:46-47`, `:63-65`). 그래서 취소해도 실행기가 멈추지 않는다.<br>하위 프로세스 종료가 확인되지 않으므로 Linux·macOS에서는 「이전 작업 트리와 세션 이어 쓰기」가 일어나지 않고 늘 새 작업 트리를 쓴다.<br>systemd 서비스로 돌 때 남은 하위 프로세스는 서비스를 멈출 때(`KillMode=control-group`)에야 정리된다.<br>**고칠 것**: OS별 동작과 H-16 링크를 적는다. macOS를 서버 작업자 지원 대상에서 빼거나 「취소 불가」를 명시한다. | 높음(코드로 확인) | **예** |
| R2 | `apps/agent/internal/agent/server_worker_test.go:11-46` | 1, 7 | **거절 시험이 새 검사를 판별하지 못한다**. 이 시험은 `ANTHROPIC_API_KEY`를 설정하지 않는다.<br>그래서 `no limits`·`no cost`·`no turns`·`no time`·`no configuration folder` 다섯 시나리오는 새 검사(`handoff.go:216-226`)를 지워도 결과가 같다. `signInFor`(`handoff.go:239` → `auth.go:89-91`)가 키 없음으로 실패해 똑같이 `EndFailed`·`executor_started=false`가 된다. 거절 사유도 단언하지 않는다.<br>`no account`·`subscription` 두 시나리오만 실제로 판별한다. 결과가 실행 환경의 환경 변수에 따라 달라진다는 점도 결함이다.<br>PR의 검증 표 「서버 모드 거절 … 통과」는 턴·시간·비용 상한 필수를 실제로 검증하지 못한다.<br>**고칠 것**: 거절 시험에 `t.Setenv("ANTHROPIC_API_KEY", fakeKeyValue)`를 둔다. `readinessCheck(t, ended, "account")`의 detail에 「공용 서버 실행」 문구가 있는지 단언한다. 같은 파일에 이미 쓰는 방식이다. | 높음(코드로 확인) | **예** |
| R3 | `apps/agent/cmd/zm-baton/handoff.go:287-289` | 1, 7 | 종료 코드 78 경로를 확인하는 시험이 없다. PR 검증 표의 `RevokedDevice` 시험(`control_test.go:95`)은 원래 있던 시험이다. 임대 갱신 중 401을 받아 실행기를 멈추는 것만 보고, 종료 코드는 보지 않는다. 명령 단위 시험을 더하거나 「미확인」으로 적는다. | 높음 | 아니오 |
| R4 | `deploy/worker/zm-baton-worker.service:16-21`, `handoff.go:282-284` | 4, 12 | SIGINT를 받으면 종료 코드 130으로 끝난다. systemd는 130을 정상 종료로 보지 않는다. 그래서 `systemctl stop` 뒤 유닛이 `failed`로 남을 가능성이 높다. `SuccessExitStatus=130`을 권한다. 실제 설치로 확인하지 않았다. | 중간 | 아니오 |
| R5 | `apps/agent/cmd/zm-baton/handoff.go:216` | 12 | 종료 코드 주석(`0/1/2/130`)에 78이 없다. | 높음 | 아니오 |
| R6 | `docs/stage1/server-worker.md:60` | 12 | 「계정 비활성화」가 두 뜻으로 읽힌다.<br>• 사람 계정을 정지하면 기기가 폐기되어 실행기가 멈춘다(`Org.kt:250`).<br>• AI 계정을 비활성화해도 이미 진행 중인 실행은 계속된다(`Accounts.kt:279`).<br>「사람 계정 정지」로 고쳐 쓴다. | 높음 | 아니오 |
| R7 | PR 본문 「소유자 호출」 | 절차 | 조건 1의 걸린 파일을 경로 표 기준으로 고친다. 경로 표에 걸린 것은 `runner.go`다. `handoff.go`는 변경 종류 「보안」으로 걸린다. | 높음 | 아니오 |

**PR44에서 확인한 것(문제 없음)**

- **API 키 대신 개인 로그인을 쓰지 않는다.**
  - 서버 작업자 모드는 계정이 없거나, 구독 계정이거나, 자격 위치가 `env:ANTHROPIC_API_KEY`가 아니면 실행기를 띄우기 전에 거절한다(`handoff.go:216-219`).
  - API 키 실행은 다른 인증 변수 7종을 뺀다. 계정 전용 `CLAUDE_CONFIG_DIR`을 주고 사람의 설정 폴더를 덮어쓰지 않는다(`auth.go:81-116`).
  - Claude Code 공식 문서를 Context7로 조회해 확인했다: `-p` 모드에서는 `ANTHROPIC_API_KEY`가 있으면 구독 로그인보다 항상 먼저 쓰인다.
  - 실제로 쓴 인증 방식이 다르면 서버가 `auth_mismatch` 알림을 낸다(`Usage.kt:406-411`).
  - 마스터 실행도 같은 `prepareExecutor`를 거친다(`master.go:50`).
  - 확신: 높음(코드와 공식 문서). 실제 조직 API로는 돌려 보지 않았다.
- **자기가 띄운 프로세스만 멈춘다.**
  - Unix 판은 `Start`가 돌려준 `*os.Process`에 고정하고, 명령줄 표식을 확인한 뒤 그 PID만 멈춘다(`kill_unix.go:46-73`).
  - systemd 유닛이 정리하는 범위도 그 유닛의 cgroup(서비스가 띄운 프로세스)뿐이다.
  - 남는 위험은 반대 방향이다. 하위 프로세스가 남는다(R1, H-16).
- **종료 코드가 문서와 맞는다.** 401을 받으면 `ErrUnauthorized`가 그대로 올라온다(`handoff.go:67-69` → `client.go:176-179`). 서버 작업자 모드에서만 78로 끝난다. 잘못된 설정은 `fail()`이 2를 돌려준다(`cmd/zm-baton/handoff.go:230-233`). 문서의 「78·2는 재시작하지 않는다」와 `RestartPreventExitStatus=2 78`이 맞는다.
- **compose 변경은 범위가 좁다.** `restart: unless-stopped`를 postgres에만 더했다. server와 backup에는 이미 있었다. 포트는 계속 `127.0.0.1`에만 연다. PR이 적은 대로 기존 컨테이너에는 적용하지 않았다.
- **공개 저장소에 올리면 안 되는 것이 없다.** `worker.env.example`의 키 값은 비어 있다. 예시 경로(`/srv`·`/opt`·`/etc`)와 `baton.example.org`는 일반 값이다. 비밀값, 개인 정보, 소유자 PC의 절대 경로는 없다.

---

## 3. PR45 (`03498cd`) 기술 지적

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| P1 | `Contradictions.kt:342-376`, `MasterChecks.kt:157-163`, `:254-263`, `:293-297`, `ReadApi.kt:147,190,239-247` | 3, 1 | **마스터 실행으로 비공개 문서가 드러나는 경로가 남아 있다.** 원래 있던 경로이고 이번 PR이 닫지 않았다.<br>• 비교 대상을 고를 때 공유 범위를 보지 않는다.<br>• 마스터 작업은 공유 범위를 적지 않아 기본값 `org`로 만들어진다(V13:217).<br>• 실행 지시(`runs.instruction`)에 비교한 글의 본문이 들어간다.<br>• `RunView`가 그 지시를 그대로 보여 준다.<br>결과: `/api/master-checks/{id}`는 숨겨도, 같은 조직 구성원이 마스터 작업의 `/work-items/{id}/runs`와 `/runs/{id}/events`로 같은 비교 본문과 마스터의 답을 읽을 수 있다. 작업 제목에도 문서 이름이 들어간다(`MasterChecks.kt:163`).<br>내보내기에서는 연결이 숨김 판정으로 이어져 실행이 빠진다. 위임의 `check_id` → 마스터 검사가 숨겨지고, 그 위임과 실행도 빠진다. 다만 작업 제목은 남는다.<br>DEC-0106 기준으로는 보안 고도화 후보다. 다중 사용자 시험판 전에 고칠 것을 권한다(§4-6). | 높음(코드 경로 확인, 실행 안 함) | 아니오 |
| P2 | `Questions.kt:126-130`, `:206`, `:224-229`, `Permissions.kt:207`, `InboxApi.kt:189-191` | 1 | **질문이 받는 사람에게 닿지 않을 수 있다.** 승인·정책 예외 질문은 승인자에게, 해석 질문은 연결 문서의 주인에게 간다. 그런데 이제 받는 사람이 상위 작업을 읽을 수 없으면 질문 상세도 첫 화면 항목도 숨겨진다.<br>`ask()`는 이것을 확인하지도 알리지도 않는다. 작업에는 `waiting_answer`가 붙는다.<br>결과적으로 그 승인 요청에 답할 수 있는 사람은 요청자 본인뿐이다. 요청자는 INVOLVED라서 답할 수 있다(`PermissionRules.kt:117,215-216`).<br>정책을 확정한 뒤 `ask()`에서 거절하거나 안내하는 보완이 필요하다. | 중간 | 아니오(소유자 정책 결정 대기, §4-5) |
| P3 | `RecordVisibility.kt:41,46-47,61-63`, `Managers.kt:244-248`, `Retention.kt:176-182` | 6 | **감사 화면에서 삭제 흔적이 사라진다.** 지운 매니저 메모의 `manager_note.deleted` 행과, 보존 규칙으로 지운 알림의 감사 행은 대상을 찾을 수 없어 모두 숨겨진다.<br>1인 조직의 소유자에게도 이 PR 전에는 보이던 행이 안 보이게 된다(퇴행). 원본 행은 남는다(`privacy-preview.md:41`에 적혀 있음).<br>모듈 4(삭제 전파)를 추적하기 어려워진다. 대안: 행동한 본인이나 운영자에게 세부를 가린 행을 보여 준다. | 높음 | 아니오(정책 결정, §4-5) |
| P4 | `Managers.kt:111-113` (StatusCards) | 3 | **첫 화면 개수가 새 거름을 거치지 않는다.** 상태 카드의 버킷별 개수는 숨긴 항목까지 센다. 내용은 드러나지 않고 존재와 개수만 드러난다.<br>`Accounts.kt:183-191`의 계정 이력도 숨긴 실행의 `run_id`를 보여 줄 수 있다. 번호만 드러난다. | 높음 | 아니오 |
| P5 | `Audit.kt:100-119`, `:128-134`, `ContradictionNotices.kt:208-228`, `InboxApi.kt:189-191` | 8 | **처리량.** 감사 목록이 SQL에서 행 수를 제한하지 않고 전부 읽은 뒤 거른다. 개수 칩은 전체를 세 번 훑는다. 마스터 검사·알림은 서로를 가리키므로 기억해 두지 않는다. 그래서 한 검사의 알림 N건을 거르면 질의가 N²번에 가깝다. 첫 화면은 항목마다 질의를 따로 보낸다(N+1). 대규모 이력은 측정하지 않았다고 문서에 적혀 있다. | 중간 | 아니오 |
| P6 | `RecordVisibility.kt:33,50` | 3 | `resolving`을 `try/finally`로 정리하지 않는다. 예외가 난 뒤 같은 판독기를 다시 쓰면 그 ID를 「허용」으로 볼 수 있다. 지금은 예외가 요청 전체를 실패시키고 판독기를 다시 쓰는 호출자가 없어 실제로는 생기지 않는다. | 중간 | 아니오 |
| P7 | `docs/hardening-backlog.md:66-67` | 절차 | PR 본문은 H-45·H-46을 보완했다고 적는다. 그러나 목록의 상태는 아직 「열림」이다. 새 고도화 후보(§5)와 함께 병합 전에 갱신한다. | 높음 | 아니오 |

**PR45 집중 검토 항목의 결과**

| 항목 | 결과 | 근거 | 확신 |
|---|---|---|---|
| 기존 판정과 새 SQL의 NULL 의미 일치 | **일치** | Kotlin `canSee`에서 소유자·실행자·프로젝트가 NULL이면 `false`다(`PermissionRules.kt:200-216`). SQL `filter`에서는 같은 경우 NULL이 된다(`:246-264`). WHERE 문맥(`Accounts.kt:172`, 연결 목록 두 곳)에서는 NULL이 제외되고, 내보내기는 `is not true`로 숨긴다(`ExportScope.kt:33`). `filter`의 결과가 바깥 괄호로 감싸져 있어(`:250-255`) `and ${scope.sql}`의 연산자 우선순위 문제도 없다. 내보내기 시험은 공유 범위 5종 × 사람 6명으로 `canSee`와 결과를 대조한다 | 높음 |
| 재귀 종속 행 필터의 누락·과다 제외 | **누락 없음. 과다 제외는 의도된 보수 동작** | 숨긴 ID와 완전히 같은 문자열이면 그 행을 빼고, 더 바뀌지 않을 때까지 되풀이한다(`ExportScope.kt:59-66`). 기본 키가 `id`가 아닌 표(`run_events`·`session_events`·연결 표)는 숨김을 퍼뜨리지 않지만, 그 표를 참조하는 표가 없어 누락이 생기지 않는다. 같은 조직의 사람은 항상 멤버십과 함께 만들어지므로(`Org.kt:205-208`) 사람 행이 숨겨져 자료가 대량으로 빠지는 일은 없다. 1인 조직 소유자의 내보내기 결과는 이전과 같다. 남은 누락은 자유문 사본과 P1의 마스터 작업 제목이다 | 높음 |
| 상태값(enum)과 ID 구별 | **맞음** | 문자열 전체가 접두어 3글자 + `_` + 소문자 UUID일 때만 참조로 본다(`RecordVisibility.kt:29,71-73`). `Ids.kt`가 소문자 UUID를 쓴다. 그래서 `run_snapshot`·`doc_contradiction`·`run_question` 같은 상태값은 참조로 보지 않는다. 문장 안에 박힌 ID(`dedupe_key` 등)는 읽지 않는다. 문서화한 한계다 | 높음 |
| 모순 알림·마스터 검사의 순환 참조 | **맞음** | 마스터 검사와 알림만 순환에 다시 들어올 때 「허용」으로 두고, 결과를 기억하지 않는다. 바깥 판정이 모든 말단을 검사하므로 최종 결과가 맞다. 기억해 두는 노드(`dvr`→`doc`, `dlg`→`wrk`, `inp`→`run`, `apv`→`dcs`)는 마스터 검사·알림에 기대지 않는다. 승인의 대상 종류는 `decision`으로 제한되어 있다(V5:64). 그래서 기억해 둔 값이 틀어지지 않는다. 처리량은 P5 | 높음 |
| 감사 목록·단건·개수 우회 | **감사 API 안에는 없음** | 목록·단건·개수가 모두 같은 판독기를 쓰고, 컨트롤러 세 곳이 모두 보는 사람을 넘긴다(`Audit.kt:114-134`, `:240-264`). 감사 API 밖의 개수 우회는 P4 | 높음 |
| 역할별 시험이 실제로 구분하는가 | **일부만** | 내보내기 시험은 6명 × 5범위로 실제 구분한다. 연결 목록·첫 화면·감사 시험은 「보면 안 되는 사람」(소유자·운영자·바깥 사람)과 기록 주인만 본다. 프로젝트·팀 구성원이 `restricted`·`team` 기록을 보는 허용 시험은 이 세 경로에 없다(고도화 후보) | 높음 |

---

## 4. 소유자 결정 대기 (기술 결함과 분리)

1. **PR44 조건 1·2**: `runner.go`와 `deploy/**`의 병합과 기존 설치 적용 여부.
2. **PR44 임시 정책 확정**:
   - 공용 서버는 「API 키 계정만」 쓴다.
   - 턴·시간·비용 상한 셋을 모두 요구한다.
   - 인증이 거절되면 종료 코드 78로 끝내 재시작을 반복하지 않게 한다.
3. **PR44 미확인 항목**: 조직 API 실제 실행(키를 준비한 뒤)과 실제 systemd 설치·중지·재시작. 지시에 따라 「미확인」으로 기록하며, 다른 작업을 막는 이유로 삼지 않는다.
4. **PR45 조건 1**: `**/permission/**` 열람 판정 변경의 병합.
5. **PR45 임시 정책 확정**:
   - (a) 질문·검토를 받는 사람도 상위 작업을 읽을 수 있어야 한다. 정하면 P2의 `ask()` 보완 방식도 정한다.
   - (b) 구조화된 참조가 숨긴 기록을 가리키면 그 행 전체를 뺀다.
   - (c) 대상을 찾을 수 없는 감사 행을 숨긴다(P3).
   - (d) 운영자·서버 관리자에게 개인 자료 열람 권한을 주지 않는다.
   - (e) 내보내기는 「읽을 수 있는 범위의 스냅숏」이고 전체 백업이 아니다.
6. **P1(마스터 실행 경로)을 이번 PR 범위에 넣을지**. 넣지 않으면 고도화 후보로 등록하고 `privacy-preview.md` 「현재 한계」에 적는다.

---

## 5. 고도화 후보 (병합 전에 `docs/hardening-backlog.md`로 옮길 것)

- **P1** 마스터 작업·실행이 비교한 비공개 문서 본문을 조직 공개 범위로 남긴다. 고치는 방법 후보:
  - 비교 대상을 `org`·`public`으로 제한한다.
  - 마스터 작업의 공유 범위를 비교 대상 가운데 가장 좁은 것으로 정한다.
  - 마스터 용도의 실행에 `RecordVisibility`를 적용한다.
- **P2** 받는 사람이 볼 수 없는 질문이 조용히 남는다. `ask()`에서 거절하거나 안내한다(정책 확정 뒤).
- **P3** 삭제된 대상의 감사 행을 세부를 가린 형태로라도 보여 줄지 정한다.
- **P4** 상태 카드의 첫 화면 개수와 계정 이력의 `run_id`도 같은 거름을 거치게 한다.
- **P5** 감사 거름을 SQL로 옮기거나 일괄 판정한다. 한 요청 안에서 마스터 검사·알림 판정을 기억해 둔다. 첫 화면 N+1 질의를 없앤다.
- **P6** `resolving`을 `try/finally`로 정리한다.
- 연결 목록·첫 화면·감사의 `restricted`·`team` 허용 시험(프로젝트·팀 구성원)을 더한다.
- **R4** systemd `SuccessExitStatus=130`을 더한다. `KillMode=mixed`도 검토한다(연결 프로그램이 표식을 확인하고 실행기를 먼저 멈추게 하기 위해).
- `credentialVars`(`auth.go:35-38`)가 Claude Code 새 판의 공급자 전환 변수를 모두 담는지 판을 올릴 때마다 확인한다. 공식 문서에 `ANTHROPIC_AWS_*` 계열이 보인다. 확신 낮음.
- H-16(Linux 하위 프로세스 종료)은 서버 작업자를 Linux에서 운영하기 전에 우선순위를 올린다.

## 6. 부채 후보

- `Accounts.kt:164`의 기본 인자 `viewer = "usr_owner"`: 지금 호출자는 모두 보는 사람을 넘긴다. 하지만 빠뜨리면 소유자 시점으로 조회된다. 기본값을 없앤다. 같은 줄의 완전한 패키지 이름도 `import`로 바꾼다.
- 경로 표의 `apps/agent/cmd/zm-baton-agent/main.go`: 서버 작업자 옵션과 허용 도구 흐름은 이제 `apps/agent/cmd/zm-baton/`에 있다. 경로 표에 그 폴더를 더할지 소유자 판단이 필요하다(정책 문서 변경).
- `me.person!!`에 이유 주석이 없다(`Accounts.kt:470` 등). 기존 컨트롤러와 같은 방식이다.

---

## 7. 검증 근거 확인

- `privacy-full-tests.log`: `> Task :test`가 실행됐고 `BUILD SUCCESSFUL in 1m 44s`로 끝났다. **시험 건수 297건과 실패·오류·건너뜀 0건은 이 로그에 없어 확인하지 못했다.** XML 보고서는 받지 않았다. 「03498cd 커밋 전 동일 소스」였는지도 확인할 수 없다.
- Go 시험(Linux 전체 패키지, Windows 교차 빌드 바이너리) 기록 파일은 읽지 않았다. 주장만 받았다.
- PR41/42/43의 CI 결과는 이번 판정의 근거로 쓰지 않았다.

## 8. 확인한 것

- 통합 diff 전체: Go 5개 파일, Kotlin 13개 파일, deploy 3개 파일, 문서 2개 파일.
- 관련 소스를 직접 읽었다:
  - 서버: `PermissionRules.kt`, `Permissions.kt`, `RecordVisibility.kt`, `ExportScope.kt`, `Export.kt`, `Audit.kt`, `Accounts.kt`·`Questions.kt`·`Reviews.kt`·`MasterChecks.kt`·`Contradictions.kt`·`Usage.kt`·`SessionService.kt`·`Managers.kt`의 해당 부분, `ReadApi.kt` 검색 결과, 마이그레이션 V1·V2·V4~V7·V9~V13의 해당 표.
  - Go: `cmd/zm-baton/handoff.go`, `internal/agent/handoff.go`·`auth.go`·`server_worker_test.go`·`account_test.go`, `internal/proc/kill_unix.go`.
- 문서: `AGENTS.md`, `docs/review-standard.md` v0.5, `docs/hardening-backlog.md`, PR 본문 초안 두 개.
- 손으로 쓴 파일은 모두 1,000줄 이하다. 가장 긴 것은 `runner.go` 794줄이고 원래 있던 파일이다. PR45는 400줄이 넘지만 PR 본문에 이유가 있다. 새 의존성은 없다. 두 PR 모두 핵심 모듈의 첫 병합이 아니다. PR 본문 여덟 항목이 모두 채워져 있다(R7 정정 필요).
- Claude Code 인증 우선순위: Context7로 공식 문서를 조회했다.

## 9. 확인하지 않은 것

- 시험은 하나도 돌리지 않았다(지시). 서버·Go·웹 시험, `go vet`·`-race`, 실제 PostgreSQL 질의 계획.
- 조직 API 키로 하는 실제 서버 작업자 실행, 실제 systemd 설치·중지·재시작·종료 코드 처리, Linux·macOS의 실제 취소 동작.
- `git diff`·pathspec 명령으로 하는 경로 판정과 커밋별 파일 구분(셸 없음).
- Windows 판 프로세스 종료(`kill_windows.go`). 이번 변경 밖이라 다시 읽지 않았다.
- 웹 화면. diff에 `apps/web` 변경이 없다.
- PR 댓글, CI 실행 결과, 소유자 결정 댓글.
- 지시로 제외한 파일: `preview-env.json`, browser의 상태·계정 JSON, `worker-profile`, 원본 서버·작업자 로그, 실제 DB 백업.

---

- 적용한 검토 기준: zm-baton 검토 기준 v0.5