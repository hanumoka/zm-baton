# 독립 검토 기록: TSK-028 서버 관리자 · 최초 설정 · 관리자 이전 (시험판)

## AI 검토 결과

- **적용한 기준**: zm-baton 검토 기준 v0.5. 웹 화면은 `apps/web/DESIGN.md`(마지막 검토 2026-10-18)도 함께 적용했다.
- **검토자**: Claude Code CLI 2.1.302 / 모델 Claude Opus 5.5(`claude-opus-5-5`) / 세션 `52f62b9d-8d39-4e1b-9613-d5cecddfb3c5` / 역할은 독립 검토이고 구현하지 않았다.
- **작성자**: Codex(GPT-6) / 세션 `01a13c92-51f2-77d2-9414-39fce9e7aefc`.
  - 실행기(Claude Code와 Codex)와 세션 번호가 둘 다 다르다. 이 검토 세션은 작성 대화를 이어받지 않았다.
- **검토한 커밋**: 기준 `f7897d7`부터 다음 세 커밋까지다. 작업 폴더 HEAD는 `d93074b`(`work/preview-admin-ui`)이다.
  - `ed37fca`
  - `96f1bba`
  - `d93074b`
- **검토 입력**: `f7897d7..d93074b` 누적 diff(21개 파일)와 현재 작업 폴더의 원본 파일.
  - 커밋하지 않은 `deploy/compose/compose.yaml` 수정은 범위 밖이라 보지 않았다.
- **판정**: 아래 「커밋별 판정」 표를 따른다. 이번 검토에 「판정 불가」는 없다.
- **소유자 호출 조건**: 해당한다. 조건 1과 조건 2이고, 걸린 파일은 아래 「소유자 호출 조건」 절에 있다.
- **소유자 호출 근거**: 물음·결정 대기.
  - 사용자 진술에 따르면, 소유자는 2026-10-18 Goal에서 구현 · 격리 검증 · 커밋 · 브랜치 push · PR까지 맡겼다.
  - 정책 확정, 병합, 기존 운영 DB 적용은 보류했다.
  - GitHub PR 본문과 댓글은 열람하지 못했다. 그래서 PR 댓글 형태의 결정이 있는지는 「확인 못 함」이다.

## 커밋별 판정

기술 판정, 검증 기록 판정, 사람의 최종 병합 대기를 따로 적는다. 소유자 승인을 기다린다는 사실만으로 코드 결함으로 보지 않았다.

| 커밋 | 범위(커밋 메시지와 PR 초안 기준 추정) | 코드 기술 판정 | 검증·기록 판정 | 이 검토의 판정 | 사람의 최종 병합 |
|---|---|---|---|---|---|
| `ed37fca` | 서버 관리자 · 초기 설정 · 이전 API, V15, 비활성화 때 기기 · 세션 폐기, 연결 프로그램의 401 정지, 문서 | 막는 코드 결함 없음 | **F1 병합 막음.** 새 Go 시험의 핵심 단언이 Linux에서 건너뛰어졌고 Windows에서는 실행하지 않았다. F2 문서 과장. | **고친 뒤** (코드 수정 없이 검증 기록 보완과 Windows 실행) | 대기. 조건 1·2, 임시 정책 확정, F5 결정 |
| `96f1bba` | 최초 설정 화면, `AuthGate` 분기 | 막는 코드 결함 없음 | **F3 병합 막음.** PR 초안의 검증이 이 커밋이 아닌 후속 화면을 포함한 트리에서 돌았다. Playwright 스크립트는 이 커밋에 없다. | **고친 뒤** (이 커밋에서 다시 돌리거나, 세 커밋을 한 번에 `d93074b` 검증으로 병합) | 대기. 조건 1(`apps/web/src/auth/AuthGate.tsx`), UI A/B/C 선택 |
| `d93074b` | 서버 관리 화면, 「나」 연결, Playwright 시험 | 막는 코드 결함 없음 | 막는 것 없음. 돌린 커밋의 정확한 해시는 보완을 권한다. | **병합 가능** (자체 변경 기준이고, 앞 두 커밋이 먼저 들어가야 한다) | 대기. 앞 두 커밋에 의존하고, 임시 정책 확정과 UI 선택이 남았다 |

- 소유자가 UI로 A가 아닌 B나 C를 고르면 `96f1bba`나 `d93074b`가 바뀐다. 그때는 다시 검토받는다.

## 지적

`server/`는 `apps/server/src/main/kotlin/io/github/hanumoka/zmbaton/server/`를 줄인 것이다.

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| F1 | `apps/agent/internal/agent/control_test.go:95-121`, `child_other_test.go:1,9-12` | 7, 결론 7 | 새 시험 `TestRevokedDeviceStopsItsExecutorInsteadOfRetryingItsLease`는 끝에서 `childGone`을 부른다. `!windows` 빌드에서 `childGone`은 `t.Skip`을 부르므로, 보고된 「Go 1.27 Linux Docker 통과」에서 이 시험의 결과는 SKIP이다. 「401이면 EndCancelled로 정지」 단언은 Skip 전에 실행된다. 그러나 「자식 프로세스까지 종료」는 어떤 실행에서도 확인되지 않았다. 1단계는 Windows에서만 쓰는데(H-16) Windows Go 실행 기록이 없다. PR 초안은 「2 패키지 통과」라고만 쓰고 건너뜀을 적지 않았다. | 높음 (빌드 태그와 `t.Skip`을 코드로 직접 확인) | **예** |
| F2 | `docs/stage1/server-administration.md:52` | 12 | 「자기가 시작한 실행기와 자식 프로세스를 중단한다」고 OS 구분 없이 단언한다. Linux와 macOS에서는 자식 프로세스가 남는다(`docs/hardening-backlog.md:37` H-16). | 높음 | 아니오 (F1과 함께 고친다) |
| F3 | PR 초안 검증 표(`96f1bba` 행) | 결론 7, G26.2 | `96f1bba`의 `npm test` 행은 「후속 관리 화면 포함 통합 시험」이라고 적혀 있다. Playwright 행이 가리키는 스크립트(`tests/browser/server_admin.py`)와 `#server` 화면은 `d93074b`에서 생긴다. 다른 커밋에서 돌린 결과를 이 커밋의 검증으로 쓸 수 없다. `ed37fca`·`d93074b` 행의 「(동일 코드)」도 실제로 어느 커밋에서 돌렸는지 적어 달라. | 높음 (`96f1bba`) / 중간 (나머지) | **예** (`96f1bba`) |
| F4 | `server/Org.kt:244-255`, `server/auth/Devices.kt:253-283`(특히 :279), :209-231 | 3, 4 | 기기 로그인 poll이 기기를 만드는 트랜잭션과 비활성화가 겹치면 문제가 생긴다. poll은 `device_logins` 행만 잠그고 승인자의 `users` 행은 잠그거나 확인하지 않는다. 비활성화의 `update devices` 문이 아직 커밋되지 않은 새 기기를 보지 못한다. 그러면 기기가 폐기되지 않은 채 남고, **다시 활성화하면 그 토큰이 되살아난다.** `approve`와 비활성화가 겹칠 때도 같다. 세션 등록은 기기 행을 잠가(`server/SessionService.kt:102-104`) 안전하다. | 중간 (읽어서 추론했고 실행하지 않음) | 아니오 (드문 경합이라 고도화 후보, DEC-0106). 다만 사용자가 짚은 「예전 자격 부활」 경로라 바로 고치기를 권한다 |
| F5 | `apps/server/src/main/resources/db/migration/V15__server_administration.sql:13-14`, `apps/web/src/auth/AuthGate.tsx:66-68`, `apps/web/src/server/ServerSetup.tsx:7-8`, `server/admin/ServerAdministration.kt:94-97` | 1, 설계 규칙 5 | V15는 기존 설치에도 `initialized_at = null`을 넣는다. 서버는 시작할 때 마이그레이션을 자동으로 적용한다고 전제한다. 그러면 병합 뒤 `required` 모드의 기존 설치 소유자는 다음 로그인 때 최초 설정 화면에 막힌다. 조직 이름 칸은 비어 있고, 제출하면 지금의 조직 이름과 표시 이름을 덮어쓴다. 문구 「비밀번호 설정을 마쳤다」도 기존 설치에는 맞지 않는다. `local` 모드는 막히지 않지만 「나」에 서버 관리 칸이 새로 보인다. 자료를 잃지는 않지만 기존 설치의 동작이 바뀐다. | 높음 (코드 경로) / 기존 설치 모드는 확인 못 함 | 아니오. 다만 **병합 전에 소유자 결정이 필요하다** (조건 2와 함께) |
| F6 | `server/.../ServerAdministrationTests.kt`, `LoginAdministrationTests.kt:130-146`, `apps/web/src/auth.test.tsx`(바뀌지 않음) | 7 | 다음 시험이 없다. ① 이전과 비활성화의 동시성. 문서의 핵심 주장인데 코드로는 맞지만 시험이 없다. ② 비활성화 때 승인된 기기 로그인 거절. ③ 비활성 · 없는 · 다른 조직 대상으로의 이전. ④ `local` 모드의 초기화 · 이전 거절(문서 :36의 주장). ⑤ 웹 단위 시험(`AuthGate` 분기, `ServerSetup`·`ServerManagement`의 오류 · 빈 상태). | 높음 (없음을 직접 확인) | 아니오 (병합 전 보완 권고) |
| F7 | `ServerSetup.tsx:20-24`, `ServerManagement.tsx:21-25,47-49` | 1, 디자인 「상태와 문구」 | 다른 창에서 설정이 끝난 뒤 409를 받으면 양식에 문구만 남고 상태를 다시 읽지 않는다. 다른 곳에서 이전이 일어난 뒤 poll이 403을 받으면 경고와 자리 표시가 계속 남는다. 새 관리자는 새로고침 전까지 「서버 관리 열기」가 보이지 않는다. | 중간 | 아니오 (고도화 후보) |
| F8 | `tests/browser/server_admin.py:103-104` | 5 | 시험 계정의 평문 비밀번호와 브라우저 로그인 상태를 증거 폴더에 쓴다. 증거 폴더가 저장소 안이면 막지 않는다. 문서(:66)는 「Git에 넣지 않는다」고 안내만 한다. 공개 저장소이므로 저장소 안 경로를 거부하거나 비밀번호를 쓰지 않기를 권한다. | 높음 | 아니오 (지금 커밋된 비밀값은 없다. 고도화 후보) |
| F9 | `tests/browser/server_admin.py:27,94-95` | 7, 디자인 사용성 | `pageerror`만 모으고 `console` 오류와 실패한 요청(4xx/5xx)은 모으지 않는다. 키보드 확인은 최초 설정에만 있다. 이전은 `check()`·`click()`으로 한다. 비관리자 빈 상태와 오류 · 다시 읽기 상태는 브라우저에서 돌리지 않았다. | 높음 | 아니오 |
| F10 | `server/admin/ServerAdministration.kt` | 정책 경로 표 | 서버 관리 권한 코드가 `admin/` 아래에 있어 경로 표(`**/auth/**`, `**/permission/**`, `**/security/**`)에 걸리지 않는다. 이번에는 변경 종류 표시(권한)로 호출 대상이 됐다. | 높음 | 아니오 (경로 표에 `**/admin/**`를 더할지는 소유자가 정한다) |
| F11 | `apps/web/DESIGN.md` 「지은 것」 | 12, 디자인 「검토」 | 최초 설정 화면과 서버 관리 화면의 설명이 없다. 「나」 화면이 바뀌었는데 바꾸기 전과 후의 스크린샷이 없다. | 높음 | 아니오 |
| F12 | `ServerManagement.tsx:77-78`, `ServerSetup.tsx:58`, 스크린샷 | 디자인 | 「조직 열기」와 「내 계정과 기기」 단추가 간격 없이 붙어 있다. 로그아웃 단추가 폭 전체를 차지해 주 동작보다 눈에 띈다. 로그인 화면(자물쇠와 「zm-baton」)과 머리 모양이 다르다. `#server`에서는 사이드바에 현재 위치 표시가 없다. | 중간 | 아니오 |
| F13 | `apps/agent/internal/agent/control.go:131` | 6 | 401(기기 폐기)과 409(실행 회수)가 같은 로그 문구다. 운영자가 기기가 폐기된 것을 알기 어렵다. | 높음 | 아니오 |
| F14 | `ServerAdministrationTests.kt:31-39` | 7 | `initialize`가 바꾼 조직 이름과 `usr_owner`의 표시 이름을 되돌리지 않는다. 시험 순서에 따라 결과가 달라질 위험이 있다. | 중간 | 아니오 |

### 막음 지적과 고칠 방법

1. **F1**: 다음 셋을 모두 한다.
   - Windows에서 `go test ./internal/agent -run TestRevokedDevice -v`(또는 패키지 전체)를 돌리고, 명령 · 결과 · 커밋 · Go 판을 기록한다.
   - Linux 결과에는 이 시험이 SKIP이었다는 것을 PR의 「실패·건너뜀」에 적는다.
   - F2 문서에 「Windows만, Linux·macOS는 H-16」을 덧붙인다.
2. **F3**: 둘 중 하나를 한다.
   - `96f1bba`를 체크아웃해 `npm test`와 `npm run build`를 다시 돌리고 표를 고친다. Playwright 행은 지운다.
   - 세 커밋을 한 묶음으로 `d93074b`에서 검증한 결과로 병합하고, PR을 그렇게 정리한다.

## 사용자가 짚은 점검 항목

### 권한 이전의 동시성

결론: 문제없다(확신 중간, 실행하지 않음).

- `initialize`·`transfer`·`updatePerson`(status)은 모두 `server_installation` 행을 `for update`로 먼저 잠근 뒤 사용자 행을 잠근다.
  - `ServerAdministration.kt:81-86,104-133`
  - `Org.kt:219`
- 상태를 바꾸는 경로는 `Org.kt:244` 하나뿐이다(grep으로 확인).
- 사용자 행을 먼저 잡고 설치 행을 기다리는 경로는 찾지 못했다. 그래서 잠금 순서가 거꾸로 엇갈릴 경로가 없다.
- READ COMMITTED에서 잠금을 기다린 뒤 다시 읽으므로 다음 경합이 막힌다.
  - 대상을 비활성화하는 것과 이전이 겹치면 409가 난다.
  - 옛 관리자의 동시 이전은 `lockAndRequire`의 재확인으로 403이 난다.
  - 동시 초기화는 한 번만 성공하고, 이것은 시험이 있다(`:74-80`).
- 이전과 비활성화의 동시성은 시험이 없다(F6).

### 비활성화 후 예전 자격 부활

| 자격 | 처리 | 시험 |
|---|---|---|
| 웹 로그인 | 기존대로 종료 | 재활성화 뒤 401 |
| 미사용 설정 코드 | 기존대로 폐기 | 재활성화 뒤 401 |
| 기기 토큰 | 새로 `revoked_at`을 기록 | 재활성화 뒤 세션 등록 401(`LoginAdministrationTests.kt:146`) |
| 세션 토큰 | 새로 `endForPerson` | 비활성 중과 재활성화 뒤 모두 401 |
| 승인됐지만 아직 받지 않은 기기 로그인 | 새로 거절 | 시험 없음 |

- 새 기기 · 세션 처리가 없으면 위 시험이 실패한다. 세션 인증 `SessionService.kt:580-591`은 사람의 상태를 보지 않기 때문이다. 그래서 이 시험은 새 동작을 실제로 가른다.
- 경합 경로 하나(F4)가 남는다.
- **정책 질문**(확신 높음, 의도는 확인 못 함): 비활성화해도 `person_passwords`는 남는다. 다시 활성화하면 **이전 비밀번호로 로그인할 수 있다**(`Logins.kt:101-120`은 상태만 본다). `docs/stage1/auth.md:87`은 이것을 막지 않는다. 이것이 의도인지 소유자에게 묻는다.

### 세션 · 기기의 권한 경계

결론: 경계가 지켜진다(확신 높음).

- `/api/server`의 세 엔드포인트는 사람 전용(`PERSON`)이다.
- `required` 모드에서는 다음처럼 거절하고 감사 기록을 남긴다.
  - 기기 토큰은 인터셉터가 403으로 거절한다(`AuthInterceptor.kt:57,98-102`).
  - 세션 토큰은 `Requester`가 403으로 거절한다(`Requester.kt:78-84`).
- 관리자 본인의 기기 · 세션 토큰이 거절되는 시험이 있다(`ServerAdministrationTests.kt:82-97`).
- 쓰기 요청의 CSRF 거절도 시험이 있다(:57 시험).
- `local` 모드에서 기기 토큰을 소유자로 보는 것은 기존 동작이고, 초기화와 이전은 `local`에서 409다.
- 연결 프로그램은 401을 받으면 실행을 「회수됨」과 같이 다룬다. 정지는 기존의 PID 고정 경로를 쓴다(`control.go:130-133`). 자기가 띄운 것만 멈추는 규칙은 바뀌지 않았다.

### 개인정보 권한 확대

결론: 없다(확신 높음).

- `isAdministrator`를 부르는 곳은 셋뿐이다. 관리 API, `AuthState`, 상태 변경 보호다.
- 관리 화면은 다음만 보인다.
  - 조직 이름
  - 소유자 · 관리자 ID와 관리자 이름
  - 설정 시각과 모드
- 후보 목록은 기존 `/api/org`(모든 활동 구성원이 읽음)을 쓴다.
- 이전받은 사람이 조직 운영 권한을 얻지 못한다는 거부 시험이 있다(`:99-114`).
- 내보내기는 `server_installation`을 이유와 함께 뺀다(`Export.kt:67`).

### 브라우저: 에러 · 빈 상태 · 키보드 · 모바일

스크린샷 8장과 `browser-results.json`을 본 결과다.

- **에러**: `page_errors: []`이다. 다만 `pageerror`만 모은 결과다(F9).
- **빈 상태**: 다음을 확인했다.
  - 후보가 없을 때 「먼저 조직에 다른 구성원을 등록한다」 이유 표시
  - 빈 양식에서 주 단추가 잠기고 이유가 보임(`setup-empty-390.png`)
  - 비관리자 빈 상태는 브라우저에서 확인하지 않았다.
- **키보드**:
  - 최초 설정은 Tab과 Enter로 끝냈다(`server_admin.py:64-71`).
  - 첫 칸 자동 포커스는 스크린샷의 포커스 링으로 확인했다.
  - 이전은 키보드로 하지 않았다.
  - 잠긴 단추에는 `aria-describedby`로 이유가 붙고, 오류는 `role="alert"`이다.
- **모바일**:
  - 390px 스크린샷에서 가로 넘침이 없다. 각 캡처에서 `scrollWidth <= innerWidth`를 단언한다.
  - `server-team-390.png`에서 긴 구성원 이름이 잘리지 않는다.
- **B/C 안**: 같은 DOM에 CSS만 덮어쓴 시안이다. 실제 코드는 A안이다.

### 시험이 새 동작을 실제로 검증하는가

- **서버**: 비활성화 · 재활성화 시험, 운영자 · 기기 · 세션 거부 시험, 이전 뒤 403 · 409 시험은 바뀌기 전 코드에서 실패한다. 새 동작을 실제로 가른다.
- **Go**: 「401이면 정지」는 바뀌기 전 코드에서 20초 시한에 걸려 실패한다. 이 부분은 실제로 가른다. 「자식 프로세스 종료」는 Linux에서 건너뛰었다(F1).
- **웹**: 단위 시험이 없다(F6).

## 검토 항목 열두 가지

| 항목 | 결과 |
|---|---|
| 1. 수락 조건 | 새 빈 설치 흐름(첫 비밀번호 → 초기 설정 1회 → 이전 → 옛 관리자 403 → 현 관리자 비활성 409 → 반환)이 코드상 충족된다. 실패 경로(400·403·409)가 있다. 기존 설치는 F5, 오류 상태는 F7 |
| 2. 계약 | `packages/contracts`가 없다. 새 API는 엔드포인트와 `AuthState` 필드(기본값 false)를 더하기만 한다. 협업 계약 v1 원문은 비공개라 대조하지 못했다 |
| 3. 권한 | 서버가 판정한다. 화면 숨김에 기대지 않는다. 허용과 거부 시험이 있다. 권한 확대가 없다. 실행기 권한 건너뛰기가 없다 |
| 4. 중복 · 경합 | 초기화 1회와 이전 직렬화는 확인했다. F4 |
| 5. 비밀값 | diff에 비밀값 · 토큰 · 절대 경로가 없다. F8 |
| 6. 로그 · 보고 | 감사 기록이 있다(`server.initialized`, `server.administrator_transferred`, `permission.refused`, `session.ended`(person_inactive)). F13 |
| 7. 시험 | F1, F6, F9, F14. 자료는 합성이다. Playwright는 localhost이고 빈 설치일 때만 돈다(`:17,51`) |
| 8. 파일 책임 | 1,000줄을 넘는 파일이 없다. 500줄을 넘는 파일은 `SessionService.kt` 735줄, `Org.kt` 544줄로 기존부터 넘었고 이번에 늘었다. PR에 이유가 필요하다 |
| 9. 의존성 | 새 런타임 의존성이 없다. Python Playwright는 시험 전용이고 `apps/web/tests/work-regressions.py`에 선례가 있다. 다만 위치(`tests/browser/`)가 기존과 다르다 |
| 10. 라이선스 | 확인한 범위에 제삼자 코드가 없다 |
| 11. 부채 | 아래 「부채 후보」 |
| 12. 문서 | 절차에 번호와 전제조건이 있다. F2, F11 |

## 소유자 호출 조건

- **조건 1**(경로 `**/auth/**`):
  - `server/auth/AuthApi.kt`(`ed37fca` 추정)
  - `apps/web/src/auth/AuthGate.tsx`(`96f1bba` 추정)
- **조건 1**(변경 종류 「권한·보안」 표시, v0.4 확장 1):
  - `server/admin/ServerAdministration.kt`
  - `server/Org.kt`(비활성화 때 폐기)
  - `server/SessionService.kt`
  - `apps/agent/internal/agent/control.go`
- **조건 2**: `apps/server/src/main/resources/db/migration/V15__server_administration.sql`(`ed37fca`).
- **조건 3·5**: 해당 없음. **조건 4**: 작성 AI의 이견 여부는 아직 모른다.
- **주의**: 커밋하지 않은 `deploy/compose/compose.yaml` 수정이 이 PR들에 섞이면 조건 2(`deploy/**`)와 결론 1(연결되지 않은 변경)에 걸린다.

## 고도화 후보

1. **F4**: 기기 로그인 poll(`Devices.kt:253-283`)과 approve에서 기기를 만들기 전에 다음을 한다.
   - 승인자 행을 `select status from users ... for update`로 잠근다.
   - `active`가 아니면 `access_denied`를 돌려준다.
   - `Logins.kt:144`의 「사람 행으로 직렬화」 방식과 같다.
2. **F7**:
   - 설정 409를 받으면 상태를 다시 읽는다.
   - 관리 화면이 403을 받으면 `onAuthChanged`를 부른다.
   - 새 관리자에게 바로 알린다.
3. **F8**: 증거 폴더가 저장소 안이면 거부한다. 비밀번호 파일은 쓰지 않는다.
4. **F9**: `console` 오류와 실패한 요청을 모은다. 이전을 키보드로 하고, 비관리자 빈 상태와 오류 상태를 추가한다.

## 부채 후보

- **F10**: 경로 표에 `**/admin/**`를 더할지 소유자가 정한다.
- **F11**: `DESIGN.md` 「지은 것」을 갱신하고 「나」 화면의 바꾸기 전과 후 스크린샷을 붙인다.
- **F12**: 디자인 세부를 다듬는다.
- **F13**: 로그 문구를 나눈다.
- **F14**: 시험이 바꾼 자료를 되돌린다.
- **Go 위생**: `control_test.go:118`의 `pid, _ := strconv.Atoi`는 기존 `:61`과 같은 모양이다. 버리는 이유 주석이 없다.
- **이전 후보 목록**: 비밀번호가 없는 구성원도 후보에 나오고, 서버가 409로 막는다. 조직 화면에 비밀번호 설정 여부가 없기 때문이다.
- **인지부채**: 「서버 관리자 1명, 조직 소유권과 분리」라는 임시 정책은 결정 기록이 아직 없다. 소유자가 확정할 때 고른 이유와 고르지 않은 방법(여러 관리자, 소유권 이전)을 남긴다.

## PR 본문(초안) 보완 사항

기술 판정과는 따로, 검토 요청으로 바꾸기 전에 필요하다.

- 본문 첫 줄에 에이전트가 썼다는 표시가 없다(G38.2).
- 다음 정보가 빠졌다.
  - Codex CLI 판
  - 실행 계정 종류. 「소유자 본인 개발 환경」 대신 개인 구독 · 조직 계정 · API 가운데 하나로 쓴다.
  - Node.js 판, Windows 판, Python 판(결론 8)
- 변경 종류를 기준 표의 이름으로 바꾼다.
  - 「인증」은 「보안」으로 쓴다.
  - 「웹 화면」과 「브라우저 시험」은 「일반 코드」로 쓴다.
- 보안 변경에 대응하는 위협 모델 항목이 적혀 있지 않다.
- `96f1bba`와 `d93074b` 초안에 `ed37fca`의 「핵심 모듈 2」와 「조건 2」 문구가 그대로 복사돼 있다. 커밋마다 맞게 고친다.
- F1의 건너뜀을 적는다.
- F3의 「돌린 커밋」을 고친다.
- 500줄을 넘는 파일의 이유를 적는다.

## 확인한 것

- `AGENTS.md`, 검토 기준 v0.5 전문, `apps/web/DESIGN.md`를 읽었다.
- 검토 입력 diff 전체(`f7897d7..d93074b`)를 읽었다.
- 작업 폴더의 다음 원본을 읽었다.
  - `ServerAdministration.kt`, `Org.kt`(`updatePerson`, 컨트롤러)
  - `SessionService.kt`(등록, `endForDevice`, `endForPerson`, `authenticate`)
  - `auth/Requester.kt`, `AuthInterceptor.kt`, `Devices.kt`, `Logins.kt`, `AuthApi.kt`
  - 마이그레이션 V2, V3(grep), V13(grep), V14, V15
  - 에이전트 `control.go`, `api/client.go`, `runner.go`(`Run`), `report/reporter.go`(grep)
  - `control_test.go`, `child_other_test.go`, `runner_test.go`(grep)
  - 웹 `AuthGate.tsx`, `ServerSetup.tsx`, `ServerManagement.tsx`, `Me.tsx`(grep)
  - `designRules.test.ts`가 하위 폴더를 재귀로 본다는 것
  - `docs/hardening-backlog.md`(H-16), `docs/core-modules/README.md`
- 이번 변경이 건드리는 모듈 1·2는 첫 병합이 아니다. README에 이미 항목이 있다.
- 스크린샷 8장(지정된 것)과 `browser-results.json`을 봤다.
- PR 초안 세 개를 읽었다.
- 시험 로그의 요약 줄을 확인했다.
  - `web-tests.log`: `Test Files 25 passed`, `Tests 336 passed`
  - `server-full-tests.log`: `BUILD SUCCESSFUL`. 293이라는 건수는 로그에 없다.

## 확인하지 않은 것

- **시험을 하나도 직접 돌리지 않았다**(Gradle, npm, go, Playwright). 작성자가 측정한 수치는 진술과 로그 요약으로만 봤다.
- Windows에서 Go 시험을 돌린 결과가 없다. 그래서 401일 때 자식 프로세스가 종료되는지는 확인하지 못했다.
- 셸을 쓰지 않아 git으로 커밋별 파일 배정을 보지 못했다. 경로 조건 명령(`git diff --no-renames ...`)도 돌리지 않았다. 커밋별 판정은 커밋 메시지와 PR 초안으로 추정했다.
- GitHub PR 본문과 댓글, 소유자 결정 댓글을 열람하지 못했다.
- Playwright와 Chromium 판, 런타임 판은 진술로만 알고 있다.
- 실제 AI 실행은 이 묶음에 들어 있지 않다.
- 협업 계약 v1과 위협 모델을 대조하지 못했다. 비공개 저장소라 열지 않았다.
- 지시에 따라 열지 않은 파일:
  - `preview-env.json`, 로그인 상태 · 비밀번호 json, 원본 서버 로그(`preview-stdout.log`·`preview-stderr.log`)
- 독립성을 위해 열지 않은 파일: 다른 검토 결과로 보이는 `independent-review.json`.
- 그 밖에 열지 않은 파일:
  - `review-prompt.txt`
  - 시작 · 재시작 스크립트, DB 백업 묶음, 실행 파일
  - 나머지 로그(`npm-ci.log`, `web-build.log`, `server-admin-tests.log`, `account-suspension-tests.log`)
- 보지 않은 스크린샷:
  - `setup-empty-1440.png`
  - `setup-variant-*-390.png`
  - `server-solo-*.png`
  - `server-team-1440.png`
  - `server-variant-*-390.png`
- 깊게 읽지 않은 것: `Shell.tsx` 전체, `usePoll`, `ExportTests`.
