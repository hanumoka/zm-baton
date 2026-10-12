## AI 검토 결과

- 적용한 기준: 기준 커밋 `c0341ff25bfc70b923d98c7d77c26c7b077bfbdd`의 `docs/review-standard.md`, zm-baton 검토 기준 **v0.4**.
- 검토자: Codex / `codex-cli 0.162.0` / GPT-6 계열(실행 환경 안내, 세부 모델 식별자는 확인 못 함) / 독립 검토자, 3차.
- 검토 세션: `01a12ef5-0b96-7b12-8dab-484832c8831f`. `CODEX_THREAD_ID`와 `CODEX_SESSION_ID`로 확인했다.
- 작성자: Claude Code / 세션 `409394de-ef95-46af-bf81-42c556a8b817`(검토 지시서). 작성자와 실행기·세션이 모두 다르며, 작성 대화를 이어받지 않고 검토 지시서와 저장소를 받았다.
- 검토한 커밋: **`37f231e4e097f74939104f171a51b47b184603dd`**, `work/sessions-server`. `origin/main`과 공통 조상은 위 기준 커밋이다.
- 범위: `git diff 98c9898 HEAD`의 수정 파일 3개, 이전 검토 기록, 관련 등록·사건 수신·표시·알림 판정·sweeper 경로와 시험. 협업 계약 v1의 「직접 연 AI 세션을 서버에 등록한다」·「첫 화면」, 데이터 모델의 「직접 연 AI 세션」·「첫 화면 항목과 사람 검토 요청」을 읽기만 했다.
- 판정: **병합 가능**. 검토한 코드에서 R2의 막음 사유는 해소됐고 새 높음·막음 결함은 찾지 못했다. N2의 경합 보장 부족과 새 성능 부채 후보 N4는 비막음이다. 이 판정은 시험의 독립 실행 성공이나 소유자의 병합 결정을 뜻하지 않는다.
- 소유자 호출 조건: **해당**. 조건 2의 DB 마이그레이션 변경 및 경로 표 밖의 권한·보안 변경이다. 아래에 파일과 근거를 적었다.
- 소유자 호출 근거: **확인 못 함**. PR 조회가 연결 거부로 실패했다. 결정 댓글이 없다고 단정하거나 호출 누락을 0건으로 세지 않는다.

### 2차 지적별 판정

아래 Kotlin 파일은 `apps/server/src/main/kotlin/io/github/hanumoka/zmbaton/server/`, 시험 파일은 대응하는 `src/test/kotlin/` 아래다. 줄 번호는 검토한 HEAD 기준이다. V2는 `apps/server/src/main/resources/db/migration/V2__sessions_inbox_notices.sql`을 뜻한다.

| 번호 | 판정 | 위치 | 확인 결과 |
|---|---|---|---|
| R2 | **해소** | `SessionService.kt:528`, `SessionService.kt:250`, `SessionServiceTests.kt:317` | `share` 생략·null·비불리언을 저장 전에 거절한다. 요약·참조·공유 설정·순번을 함께 갱신하고 이전 순번은 무시한다. 다른 사건의 설정을 상속하던 비공개 요약 노출 경로가 닫혔다. |
| N1 | **해소** | `SessionService.kt:259`, `SessionService.kt:262`, `SessionService.kt:294`, `SessionServiceTests.kt:347`, `SessionServiceTests.kt:372` | 가장 큰 재개 순번 뒤의 같은 종류 보고를 다시 집계한다. 첫 순번·마지막 순번·최소 수신 시각·반복 수와 최신 문구가 함께 맞춰진다. |
| N2 | **부분 해소, 경합 보장은 미해소** | `SessionServiceTests.kt:403`, `SessionServiceTests.kt:455`, `SessionServiceTests.kt:468` | 해시 충돌을 실제 DB에서 찾는 시험과 작성자의 역변경 시험 근거가 추가됐다. 그러나 시작 latch와 15회 반복은 여전히 두 트랜잭션의 잠금 경합을 보장하지 않는다. |
| N3 | **해소** | `SessionService.kt:31`, `SessionService.kt:87`, `SessionService.kt:618`, `SessionServiceTests.kt:428` | SQL에서 실제 `hashtext` 번호를 중복 제거·정렬한 뒤 번호별로 잠근다. 등록은 첫 키 1, 파일은 첫 키 2를 사용한다. 문자열 정렬의 역전과 등록·파일 잠금 충돌이 제거됐다. |

### 남은 지적과 새 부채 후보

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| N2 · 중간, 기존 | `SessionServiceTests.kt:412`, `SessionServiceTests.kt:464` | 7 시험 충분성 | latch는 요청 시작만 맞춘다. 실제 잠금 대기를 관찰하거나 잠금 획득 경계를 제어하지 않아 순차 실행도 통과한다. | 높음 | 아니오 |
| N4 · 중간, 신규 | `SessionService.kt:265`, `SessionService.kt:298`; `V2:62` | 1 처리 비용, 7 부하 검증, 11 부채 | 기다림 보고마다 현재 대기 구간을 다시 집계한다. 재개 없이 보고가 쌓이면 누적 집계량이 제곱으로 증가하고, 재개는 열린 종류마다 범위를 다시 읽는다. | 높음(코드상 비용 증가) | 아니오 |

**N2.** 새 충돌 시험은 서로 다른 경로가 같은 잠금 번호를 갖는 경우를 실제 `hashtext`로 구성하므로 N3 회귀 검출에 유용하다. 작성자는 경로 정렬로 되돌리면 교착 실패, 번호 정렬이면 통과했다고 보고했다. 현재 로컬 XML에도 해당 시험의 `ERROR: deadlock detected` 실패가 남아 있고, 스택의 `lockFiles → lock` 및 SQL `pg_advisory_xact_lock(?, hashtext(?))`는 현재 번호별 루프와 다르다. 이는 작성자가 설명한 역변경 시험과 부합하지만, 산출물의 실행 소스·커밋을 독립 확인한 것은 아니다. 특정 실행에서 결함을 잡은 근거와 매 실행에서 경합을 보장하는 것은 구별해야 한다. 별도 연결로 잠금을 보유하고 `pg_locks` 등의 대기 상태를 확인한 뒤 해제하는 시험을 후속 보강할 수 있다.

**N4.** 사건 저장·인증·세션 갱신을 제외한 재계산 비용은 다음과 같다.

| 사건 | `session_events` 조회 수 | 항목 갱신 |
|---|---|---|
| 최신 재개보다 뒤의 `waiting` | 집계 1회 + 최신 문구 1회 | upsert 1회 |
| 최신 재개 이하의 `waiting` | 0회 | 없음 |
| `resumed` | 열린 종류가 K개이고 재계산 뒤 남는 종류가 M개이면 K + M회 | 종류마다 upsert 또는 닫기 1회. 별도로 재개 순번 갱신과 열린 종류 조회가 있다 |

`session_events_by_session(session_id, local_seq)`는 세션 일치와 재개 이후 순번 범위를 제한할 수 있다. 다만 `kind`와 JSON의 `wait_kind`는 추가 필터이며, 현재 인덱스만으로 `count(*)`·`min(received_at)`를 상수 비용으로 구하지 못한다. 최신 문구 조회는 세션·순번 동등 조건으로 좁힐 수 있다. 이는 인덱스 정의와 [PostgreSQL 17 다중 열 인덱스 규칙](https://www.postgresql.org/docs/17/indexes-multicolumn.html)에 따른 분석이며, 실제 실행 계획은 확인하지 못했다.

한 종류의 보고만 N건 연속 들어오면 집계 대상은 누적 `N(N+1)/2`건이다. 100건이면 5,050건, 10,000건이면 50,005,000건이다. 배치 상한 100은 누적 이력을 제한하지 않는다. 같은 세션 행을 잠근 채 처리하므로 이 비용은 후속 배치의 대기도 늘릴 수 있다. 처리 시간이나 장애를 실측한 것은 아니어서 높음 결함으로 올리지 않는다. 배치당 종류별 재계산을 모으고, 재개 경계가 바뀌지 않는 보고는 정확한 누적값으로 갱신하는 방법을 검토할 수 있다. 전용 인덱스만 추가해도 불필요한 필터 비용은 줄지만 반복 전체 집계 자체는 남는다.

### 공유 요약의 표시 경로

`current_work` 검증은 사건 저장보다 먼저 실행된다(`SessionService.kt:181`, `SessionService.kt:187`, `SessionService.kt:528`). 명시적 `false`는 유효한 값이다. 받아들인 요약은 자기 설정과 함께 한 UPDATE로 반영되며, 같은 세션의 두 배치도 세션 행 잠금으로 직렬화된다.

- **상태 카드:** 같은 조회 결과의 `share_current_work`를 검사해 `false`이면 「공유 안 함」을 만든다(`SessionService.kt:460`, `SessionService.kt:465`).
- **세션 목록:** 공유를 끄면 `currentWork`와 `currentWorkAt`가 모두 null이고 `shared=false`다. 종료 세션 포함 조회도 같은 매핑을 쓴다(`InboxApi.kt:109`, `InboxApi.kt:117`, `InboxApi.kt:119`).
- **inbox:** `current_work`는 항목을 만들지 않는다. 기다림 항목은 `waiting.message`, 충돌 항목은 파일·세션 근거로 만들며 비공개 요약·참조를 복사하지 않는다(`SessionService.kt:327`, `SessionService.kt:341`, `SessionService.kt:376`, `InboxApi.kt:132`). 공유 설정이 별도로 보낸 대기 문구나 파일 활동까지 숨기는 기능은 아니다.

따라서 확인한 세 표시 경로에서 `share=false`인 요약이 다른 사건의 `true`와 결합해 노출되는 경로는 찾지 못했다. 추가된 시험은 1→3→2 도착과 공유 생략 거절을 확인한다. 별도 CLI PR `cf7b883`의 설정 기억·전송 구현은 이번 검토 대상에 포함하지 않았다.

### 대기 재계산의 순서·동시성·닫기

`insertEvent` 뒤에 `refreshWait`를 실행하므로 현재 사건도 집계에 들어간다. 가장 큰 재개 순번 R 뒤의 같은 종류 보고만 선택해 첫·마지막 순번, 최소 `received_at`, 건수에서 1을 뺀 반복 수를 구한다. 문구는 마지막 순번의 보고에서 가져온다(`SessionService.kt:187`, `SessionService.kt:298`, `SessionService.kt:316`, `SessionService.kt:342`).

2차 반례 10→30→40→60→70→20→50은 마지막에 첫 순번 60, 마지막 순번 70, 반복 수 1로 계산된다. 10→30→20→25는 첫 순번이 25로 바뀌어도 서버가 먼저 받은 30의 `received_at`을 유지한다. 클라이언트의 실제 대기 시작 시각을 복원하는 것이 아니라 저장된 서버 수신 시각의 최솟값이라는 이번 정의에 맞는다.

동일 세션은 `authenticate(..., forUpdate=true)`에서 행을 잠근 뒤 사건을 삽입한다(`SessionService.kt:160`, `SessionService.kt:504`). 기본 `READ COMMITTED`에서 대기한 요청은 갱신된 세션 행을 받고 이후 조회에서 앞선 커밋과 자기 삽입을 볼 수 있다. 따라서 집계와 문구 조회 사이에 같은 세션의 다른 ingest가 끼어들지 못한다. 다른 세션은 `session_id`·`source_id`가 다르므로 대기 집계와 열린 항목의 유일 키가 겹치지 않는다. 이 판단은 코드와 [PostgreSQL 17 격리 수준 명세](https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED)를 대조한 것이며 DB 동시 실행 재현은 아니다.

최신 재개 뒤의 보고가 없을 때 닫는 분기는 맞다(`SessionService.kt:305`). 아직 도착하지 않은 더 큰 순번의 보고가 나중에 오면 그 보고가 다시 연다. 이미 받은 더 큰 순번의 보고는 집계에 남으므로 닫히지 않는다. 늦은 작은 재개는 R을 낮추지 않는다. 닫힌 종류를 재개 때 다시 탐색하지 않아도, R이 커져 기존에 없던 대상 보고가 생길 수는 없다. 종료·해제·만료도 세션 행을 먼저 갱신한 뒤 기다림을 닫는다.

두 종류의 기다림과 두 재개를 포함한 8개 사건의 도착 순열 40,320개, 중간 상태 322,560개를 메모리 모델로 비교해 이 불변식을 확인했다. Kotlin·SQL 실행이나 잠금 시험을 대신하지 않는다.

### 파일 잠금의 인수와 다른 경로의 순서

로컬 Spring JDBC `7.0.9`의 `NamedParameterUtils.substituteNamedParameters`를 실제 실행했다. 키 1개는 `array[?]`, 100개는 자리표시자 100개로 정확히 확장됐다. 값은 문자열 매개변수로 전달되며 배열 하나를 JDBC 인수 하나로 바인딩하는 구조가 아니다. 이는 [Spring의 컬렉션 매개변수 처리](https://docs.spring.io/spring-framework/reference/data-access/jdbc/parameter-handling.html#jdbc-in-clause)와도 맞는다. PostgreSQL에서의 실제 1개·100개 배치 실행은 하지 못했다.

`lockFiles`는 빈 목록을 반환 처리하고, 중복 문자열과 중복 해시를 제거한다. SQL의 `order by h` 결과를 리스트로 받은 다음 `Int` 번호마다 별도의 잠금 SQL을 순서대로 실행한다(`SessionService.kt:619`). 음수도 포함한 일관된 숫자 순서다. 잠금 함수와 정렬을 한 SELECT의 평가 순서에 맡기지 않는다. 등록·파일은 각각 `(1, hash)`, `(2, hash)`이므로 같은 해시여도 다른 잠금이다. [PostgreSQL advisory lock 함수 명세](https://www.postgresql.org/docs/17/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS).

| 경로 | 잠금 순서와 검토 결과 |
|---|---|
| ingest | 자기 세션 행 → 배치의 파일 번호 오름차순 → 사건·기다림 또는 알림 → 알림의 inbox 항목. 다른 세션은 일반 조회만 한다(`SessionService.kt:160`, `SessionService.kt:163`, `SessionService.kt:365`, `SessionService.kt:397`). |
| 재등록 | 기기 행 → 등록 공간 잠금 → 해당 세션 행 → 그 세션의 기다림. 파일 공간 잠금을 요청하지 않는다(`SessionService.kt:82`, `SessionService.kt:87`, `SessionService.kt:90`, `SessionService.kt:101`). |
| sweeper | 만료 세션 행들 → 해당 세션들의 기다림. 등록·파일·알림 잠금을 요청하지 않는다(`SessionService.kt:232`, `SessionService.kt:239`). |
| 사람 알림 판정 | 알림 행 → 해당 알림의 inbox 항목. 세션·파일 잠금으로 돌아가지 않는다(`InboxApi.kt:167`, `InboxApi.kt:174`). |

번호 공간 분리로 새 역순 획득 경로는 생기지 않았다. `checkConflicts`의 재취득은 앞서 보유한 같은 파일 잠금이므로 추가 대기 순환을 만들지 않는다. 일반 조회와 행 잠금, 같은 트랜잭션의 잠금 재취득 성질은 [PostgreSQL 17 잠금 명세](https://www.postgresql.org/docs/17/explicit-locking.html)와 대조했다.

재취득에도 JDBC 왕복 비용은 있다. 고유 번호 D개를 미리 잠그는 데 조회 1회와 잠금 D회가 들고, 실제 처리하는 파일 사건마다 해시 조회·재취득 2회가 추가된다. 모두 유효한 서로 다른 100개 사건이면 잠금 관련 SQL만 최대 301회다. 이는 정확성 결함은 아니며 보유 잠금 재취득을 생략할 후속 최적화 후보다.

### V2 변경과 소유자 호출

V2의 이번 변경은 대기 순번 주석뿐이다. 기준 커밋의 `git ls-tree`에 V2는 없으므로 이번 PR에서 처음 추가되는 미병합 마이그레이션이다. 사용자가 밝힌 미배포 조건에서 주석을 직접 수정한 것을 기준의 「병합된 뒤 고치지 않는다」 위반으로 보지 않는다. 이전 V2를 적용한 시험 DB의 Flyway 체크섬 처리는 별도이며 실제 배포 이력을 조사하지 않았다.

- **조건 1의 경로 표:** 공통 조상부터 HEAD까지 전체 패턴을 적용했으며 일치 파일은 없다. 다만 PR 전체의 `SessionService.kt`, `SessionApi.kt`, `InboxApi.kt`는 토큰·사람 동작 거부·외부 입력·공유를 다룬다. 기준 v0.4의 변경 종류 규칙과 「결정보다 넓힌 곳」 1에 따른 **권한·보안** 호출 대상이다.
- **조건 2:** `apps/server/src/main/resources/db/migration/V2__sessions_inbox_notices.sql`이 일치한다. 미배포·주석 수정이라는 사실은 PR 전체 DB 변경의 호출 조건을 면제하지 않는다.
- **조건 3:** 해당 없음. 검토한 코드에 대한 판정은 병합 가능이다.
- **조건 4·5:** 현재 이견이나 막음 조건 면제 시도는 확인하지 못했다.

변경 종류는 일반 코드·권한·보안·DB 구조·일반 문서다. 핵심 모듈 6의 기록 미작성 등록은 `docs/core-modules/README.md`에 유지된다. 병합하는 세션은 PR 필수 항목·현재 커밋의 검증 근거·CI·소유자의 결정 댓글을 병합 전에 확인해야 한다.

### 검증 결과

| 명령·확인 | 결과 |
|---|---|
| `git rev-parse HEAD`, `git rev-parse origin/main`, `git merge-base origin/main HEAD` | 지시된 검토·기준 커밋 확인. |
| `git diff 98c9898 HEAD`, `git diff --check c0341ff HEAD` | 이번 수정 파일 3개 확인. 공백 오류 없음. |
| 제공된 JDK를 `JAVA_HOME`에 지정하고 `java -version`, `apps/server`에서 `./gradlew test --no-daemon` | JDK 버전 확인 성공. 시험 명령 종료 코드 1. `gradle-9.7.1-bin.zip.lck` 접근 거부로 `java.io.FileNotFoundException`. **서버 시험은 실행되지 않았다.** |
| `docker version` | CLI `29.8.2`. 엔진은 `permission denied while trying to connect to the docker API`로 접근 실패. |
| 제공된 JDK의 `jshell --execution local --feedback concise -`, 로컬 Spring JAR classpath | 키 1개·100개에 대해 실제 named parameter 확장 결과와 예상 SQL이 일치. DB 연결·서비스 실행 시험은 아니다. |
| `python -B -` 메모리 모델 | 대기 사건 도착 순열 40,320개·중간 상태 322,560개에서 집계 불변식 일치. 명시적 공유 설정을 가진 요약 3개의 도착 순열 6개에서 비공개 내용 노출 없음. SQL 실행 시험은 아니다. |
| 기존 `build/test-results/test/TEST-*.xml` 읽기 | 현재 남은 XML은 충돌 시험 1건·실패 1건이며 메시지는 `ERROR: deadlock detected`. 앞서 설명한 역변경 시험과 부합하지만 실행 소스·커밋은 확인 못 함. HEAD의 시험 실패나 통과로 계산하지 않았다. |
| 작성자 보고 | `37f231e` 서버 시험 42건·실패 0건, 세 PR 통합 브랜치의 실제 CLI E2E 22건 통과. **작성자 보고이며 독립 재실행·원본 전체 로그 검증은 못 했다.** |
| `gh pr view 11 --repo hanumoka/zm-baton --json body,headRefOid,state,isDraft,comments` | `proxyconnect tcp` 연결 거부. PR 본문·댓글·원격 HEAD 확인 못 함. |

환경: Windows NT `10.0.26300.0`, PowerShell `7.6.6`, OpenJDK Temurin `25.0.4.1+1-LTS`, Codex CLI `0.162.0`, Python `3.13.12`. 부분 검증에 사용한 로컬 Spring JDBC는 `7.0.9`다. 저장소 설정은 Gradle `9.7.1`, Kotlin `2.3.21`, Spring Boot `4.1.1`, 시험 컨테이너 `postgres:17`이다. 설정 버전을 실제 시험 실행 성공으로 표시하지 않았다.

### 부채 후보와 나머지 검토 항목

- 항목 1~4·7: 위 R2·N1·N2·N3와 경계 검토에 기록했다. 실제 DB 경합·부하 시험은 남는다.
- 항목 5·6: 이번 diff에서 새 비밀값 기록·공급자 인증 접근·실패를 성공으로 표시하는 경로는 찾지 못했다. 기존 교착 XML과 작성자의 통과 보고를 분리했다.
- 항목 8: `SessionService.kt`는 644줄로 1,000줄 제한 이하다. 500줄 초과 이유와 PR 전체 변경의 분할 검토 기록은 PR 조회 실패로 확인하지 못했다.
- 항목 9·10: 이번 수정에는 새 의존성·제삼자 포함물 추가가 없다. 코드 출처 전수 감사는 하지 않았다.
- 항목 11: N2의 경합 보장, N4의 반복 집계, 파일 잠금 재취득의 왕복 비용을 후속 후보로 남긴다.
- 항목 12: V2 주석은 새 집계 방식과 맞는다. 이전 검토 기록은 과거 커밋에 대한 기록이며 현재 판정은 이 기록을 사용한다.
- 이전 검토에서 밝힌 사람 인증 등 미구현 범위가 이번 수정으로 해소된 것은 아니다. `share` 필수화와 별도 CLI PR의 동시 적용도 배포 전에 함께 확인할 대상이다.

### 확인하지 않은 것

- 서버 컴파일·42개 시험의 독립 실행, 실제 PostgreSQL의 1개·100개 키 처리, 동일·다른 세션 동시 배치와 재등록·sweeper·사람 판정의 실제 경합, 실행 계획과 부하 측정, 컴파일 경고 0건 여부.
- 별도 CLI PR `cf7b883`의 코드, 세 PR 통합 브랜치와 E2E 22건의 독립 실행, 실제 훅·브라우저·Linux·macOS 동작.
- PR의 필수 여덟 항목·초안 여부·원격 HEAD·CI·소유자 호출 시각과 결정 댓글·병합 직전 조건. 조회 실패를 통과로 표시하지 않았다.
- V2의 실제 적용·배포 이력, 운영 DB 변경·복구, 지정된 절 밖의 비공개 설계 전체, 기존 단계적 미구현의 완료 여부.

지정된 `docs/reviews/2026-10-12-work-sessions-server-codex-3.md`만 작성했다. 제품 코드·이전 기록 수정, 커밋·push·병합은 하지 않았다.
