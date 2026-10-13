## AI 검토 결과

- 적용한 기준: 기준 커밋 `c0341ff25bfc70b923d98c7d77c26c7b077bfbdd`의 `docs/review-standard.md`, zm-baton 검토 기준 **v0.4**.
- 검토자: Codex / `codex-cli 0.162.0` / GPT-6 계열(실행 환경 안내, 세부 모델 식별자는 확인 못 함) / 독립 검토자, 2차.
- 검토 세션: `01a12a62-cd7e-7d61-afe8-20784a87a0da`. 환경 변수 `CODEX_THREAD_ID`와 `CODEX_SESSION_ID`로 확인했다.
- 작성자: Claude Code / 세션 `409394de-ef95-46af-bf81-42c556a8b817`(검토 지시서). 작성자와 실행기·세션이 모두 다르다. 작성 대화를 이어받지 않고 검토 지시서와 저장소를 받았다.
- 검토한 커밋: **`1280f84f0fe4d6119ffaa1cb0a282fd32b6ac02e`**, `work/sessions-server`. `origin/main`과 공통 조상은 위 기준 커밋이다.
- 범위: `git diff ef84c9a HEAD`의 수정 파일 3개, 1차 기록, 관련 등록·인증·사건·첫 화면·알림·sweeper 경로와 시험. 협업 계약 v1의 「직접 연 AI 세션을 서버에 등록한다」·「충돌을 찾아 관계자에게 알린다」·「첫 화면」, 데이터 모델의 「직접 연 AI 세션」·「첫 화면 항목과 사람 검토 요청」을 읽기만 했다.
- 판정: **병합 막음**. R2의 비공개 요약 노출이 남는다. 이번 막음 지적은 높음 1건이며, N1·N2와 추가 확인한 N3는 비막음이다.
- 소유자 호출 조건: **해당**. 조건 2의 DB 마이그레이션 변경 및 경로 표 밖의 권한·보안 변경이다. 아래에 파일과 근거를 적었다.
- 소유자 호출 근거: **확인 못 함**. PR 조회가 연결 거부로 실패했다. 결정 댓글의 부재나 호출 누락 0건으로 판정하지 않는다.

### 1차 지적별 판정

아래 Kotlin 파일명은 `apps/server/src/main/kotlin/io/github/hanumoka/zmbaton/server/`, 시험 파일명은 대응하는 `src/test/kotlin/` 아래다. 줄 번호는 검토한 HEAD 기준이다. 마이그레이션은 `apps/server/src/main/resources/db/migration/V2__sessions_inbox_notices.sql`이다.

| 번호 | 판정 | 위치 | 확인 결과 |
|---|---|---|---|
| R1 | **해소** | `SessionService.kt:550`, `SessionServiceTests.kt:291` | 원문 전체를 분석한 뒤 userinfo·쿼리·fragment를 버리고 재구성한다. 길이 초과 결과는 잘라 저장하지 않고 버린다. 긴 자격 증명과 쿼리 토큰의 기존 누출 경로는 닫혔다. |
| R2 | **부분 해소** | `SessionService.kt:247`, `SessionService.kt:249`, `SessionServiceTests.kt:303` | 오래된 요약·명시적 공유 설정의 덮어쓰기는 막았다. 그러나 최신 요약의 `share` 생략과 늦게 도착한 공유 해제가 결합하면 비공개 요약이 공개된다. **막음 유지**. |
| R3 | **해소** | `SessionService.kt:87`, `SessionService.kt:224`, `SessionService.kt:234`, `SessionServiceTests.kt:318` | 재등록과 sweeper가 같은 만료 종료 함수를 사용한다. 종료된 ID의 기다림도 같은 트랜잭션에서 닫는다. |
| R4 | **부분 해소** | `SessionService.kt:157`, `SessionService.kt:166`, `SessionService.kt:586` | 같은 X·Y 파일을 반대 사건 순서로 처리하던 원래 교착은 해소했다. 실제 advisory lock 번호의 해시 충돌까지는 해결하지 않았다. 원래의 막음 사유는 해소로 보고, 특수한 충돌 조건의 잔여 위험은 중간 N3로 구분한다. |
| N1 | **부분 해소** | `SessionService.kt:265`, `SessionService.kt:314`, `SessionService.kt:316`, `SessionServiceTests.kt:328` | 옛 문구 덮어쓰기는 해결했다. 시작 순번·시각 보정은 마지막 보고를 선택하므로 여러 기다림·재개가 섞이면 틀린다. |
| N2 | **부분 해소** | `SessionServiceTests.kt:351`, `SessionServiceTests.kt:367`, `SessionServiceTests.kt:375` | 두 파일의 역순 배치와 알림별 열린 항목 수를 검사하도록 보강했다. 시작 latch와 15회 반복만으로 실제 DB 잠금 경합을 보장하지는 않는다. |

### 남은 지적과 이번 수정에서 확인한 결함

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| R2 · 높음 | `SessionService.kt:247`, `SessionService.kt:249`; 노출 지점 `SessionService.kt:444`, `InboxApi.kt:117` | 1 동작, 2 사건 순서, 3 공유 권한 | 최신 요약에 `share`가 없으면 도착 순서상의 공유 값을 유지한다. 뒤늦게 도착한 이전 공유 해제는 새 순번 조건에 막혀 반영되지 않는다. | 높음 | **예** |
| N1 · 중간 | `SessionService.kt:265`, `SessionService.kt:268`, `SessionService.kt:316` | 1 동작, 2 계약, 4 상태 일관성 | 재개 뒤 첫 기다림을 찾지 않고 `wait_seq`로 시작을 옮긴다. 추가 재개나 늦은 기다림에도 시작 시각을 정확히 다시 계산하지 않는다. | 높음 | 아니오 |
| N2 · 중간 | `SessionServiceTests.kt:358`, `SessionServiceTests.kt:367` | 7 시험 충분성 | 두 요청이 순차 처리돼도 새 동시성 시험은 통과할 수 있다. DB 대기 관찰이나 잠금 경계를 통제하는 장치가 없다. | 높음 | 아니오 |
| N3 · 중간 | `SessionService.kt:84`, `SessionService.kt:166`, `SessionService.kt:586` | 4 동시성, 7 시험 | 문자열 정렬 순서는 충돌 가능한 32비트 `hashtext` 잠금 번호의 정렬 순서가 아니다. 등록·파일 잠금도 같은 번호 공간을 사용한다. 아래 합성 예와 대기 순환을 확인했다. | 중간 | 아니오 |

**R2 — 공유 해제가 늦게 오면 새 순번 조건이 노출을 고착시킨다.**

사건의 실제 순서와 내용은 다음과 같다. 사건 ID는 각각 다르다.

| 순번 | 요약 | `share` |
|---|---|---|
| 1 | 공개 요약 | `true` |
| 2 | 공유 해제 시 요약 | `false` |
| 3 | 공유를 끈 뒤 작성한 비공개 요약 | 생략 |

서버에 별도 배치로 **1→3→2** 순서로 도착하면, 3은 `coalesce` 때문에 `share_current_work=true`로 저장된다. 이어서 2는 `current_work_seq < :seq`를 만족하지 못해 공유 해제까지 무시된다. 모든 사건을 받은 뒤에도 3의 비공개 요약이 다른 세션의 상태 카드와 사람의 세션 목록에 보인다. 등록 직후 순번 1이 공유 해제이고 순번 2가 `share`를 생략한 비공개 요약이라면, 2→1 도착만으로도 같은 문제가 생긴다.

기존 시험은 최신 사건 자체에 `share=true`를 넣어 이 경우를 놓친다. 최신 요약 보존만으로 세션별 공유 상태의 순서를 보장할 수 없다. 요약마다 생성 당시 유효한 공유 상태를 명시적으로 묶어 검증하거나, 순서·공유 상태가 확정되지 않은 요약을 공개하지 않도록 해야 한다. 별도 공유 순번만 추가하여 나중에 비공개로 되돌리는 것은 그 전의 노출을 막지 못한다. 협업 계약 v1 「지금 하는 일」의 공유 해제와 「세션이 보내는 사건」의 순서 규칙에 어긋난다. SQL 조건과 표시 코드를 직접 확인하고 동일 대입 규칙을 메모리에서 추적했다. 서버·DB 통합 재현은 못 했다.

**N1 — 새 보정은 재개 뒤 첫 보고가 아닌 마지막 보고로 시작을 옮긴다.**

같은 `wait_kind`의 실제 순서가 `waiting10 → resumed20 → waiting30 → waiting40 → resumed50 → waiting60 → waiting70`이고, 도착 순서가 **10→30→40→60→70→20→50**이라고 하자. 20을 받으면 새 SQL이 `wait_first_seq=70`, `waiting_since=70의 received_at`으로 바꾼다. 뒤이어 50을 받아도 `70 < 50`이 거짓이라 보정하지 않는다. 최종 기다림은 60에서 시작했으므로 시작 순번은 60, 시작 시각은 60의 보고 시각이어야 한다. 지금은 반복 보고 70을 기준으로 삼아 첫 화면의 오래 기다린 순서가 틀린다.

추가된 시험 자체의 **10→30→20→25**에서도 마지막 사건이 `wait_first_seq`를 25로 낮추지만 `waiting_since`는 30의 시각에 남는다. 시험은 이때 문구만 검사한다. 최신 재개 이후 같은 종류의 첫 기다림을 사건 기록에서 구해 순번과 시각을 함께 계산해야 한다. 현재 열린 항목의 존재 여부와 최신 문구 보호는 유지되므로 중간·비막음으로 남긴다.

**N3 — 문자열을 정렬해도 해시 충돌 시 실제 잠금 순서는 뒤집힌다.**

PostgreSQL 17의 결정적 collation에서 쓰는 `hashtext`·`hash_bytes`의 little-endian 산식을 메모리에서 옮겨 계산했다. 다음은 합성 키이며 실제 DB의 `hashtext` 실행이나 교착 재현 결과는 아니다. [PostgreSQL hashtext 소스](https://raw.githubusercontent.com/postgres/postgres/REL_17_STABLE/src/backend/access/hash/hashfunc.c), [hash_bytes 소스](https://raw.githubusercontent.com/postgres/postgres/REL_17_STABLE/src/common/hashfn.c).

| 키 | 계산한 잠금 번호 |
|---|---|
| `touch\|git:synthetic\|f098405.md` | `-477407222` |
| `touch\|git:synthetic\|f103646.md` | `1521834926` |
| `touch\|git:synthetic\|f108888.md` | `-477407222` |

첫째·둘째 파일 배치는 문자열 정렬 후 H1→H2를 요청하고, 둘째·셋째 파일 배치는 H2→H1을 요청한다. 각각 첫 잠금을 얻으면 원래와 같은 순환 대기가 가능하다. 또한 합성 등록 키 `register|dev_synthetic|codex|executor-050838`와 파일 키 `touch|git:synthetic|f043941.md`의 계산 결과는 모두 `96714068`이다. 같은 세션에 대해 ingest가 세션 행을 보유하고, 재등록이 이 등록 잠금을 먼저 얻으면, ingest는 파일 잠금 때문에 재등록을 기다리고 재등록은 세션 행 때문에 ingest를 기다릴 수 있다.

해시 함수와 공용 번호 공간은 기존 코드에도 있었다. 이번 정렬 수정으로 새로 생긴 일반 교착으로 보고하지 않는다. 합성 충돌을 전제로 한 잔여 위험이므로 중간·비막음이다. 해결 방향은 파일 잠금을 실제 번호로 중복 제거·정렬하고 등록 잠금과 이름 공간을 분리하는 것이다. 단순히 해시 폭만 늘리면 충돌 확률은 줄지만 순서 보장은 되지 않는다.

### 요청된 경계 검토

**원격 주소 파서.** `cleanRemote`와 같은 분기·정규식·URI 재구성을 Java로 옮겨 제공된 JDK의 JShell에서 24개 합성 입력을 확인했다. Kotlin 서비스 자체나 DB 저장을 실행한 시험은 아니다.

| 입력 종류 | 확인 결과 |
|---|---|
| 긴 userinfo, 쿼리·fragment의 합성 토큰 | 제거된 주소만 남는다. 분석 전 절단은 없다. |
| `ssh://user:…@[2001:db8::1]:2222/a.git` | `ssh://[2001:db8::1]:2222/a.git`이 된다. IPv6 대괄호와 포트가 보존된다. scope ID가 있는 IPv6도 확인했다. |
| userinfo의 `%40`, `%2F`, `%3F`, `%23`, `%3A` | userinfo 전체가 제거된다. 여러 개의 원문 `@` 등 호스트를 해석할 수 없는 주소는 null이다. |
| 포트 | 2222는 보존, 문자·음수 포트는 null, 빈 포트는 생략된다. 65536은 보존된다. 여기서는 접속하지 않고 메타데이터로만 저장하므로 이를 높음 결함으로 보지 않는다. |
| scp 형식 | `user@example.invalid:a.git`의 사용자는 제거된다. IPv6 scp 형식 `git@[2001:db8::1]:a.git`은 null이다. URL 형식 IPv6와 지원 범위가 다르다. |
| Windows·로컬 경로 | 드라이브 경로, UNC, `file:`, POSIX 절대 경로, `../` 경로는 null이다. 한 글자 호스트 `x:a.git`도 드라이브 검사에 걸린다. |
| 기타 호환성 | 밑줄 호스트 URL은 null이다. 알 수 없는 scheme은 URI로 해석되면 보존한다. raw path의 percent escape는 그대로 유지한다. |

`URI.getHost()`는 IPv6의 대괄호를 포함하며, `getRawPath()`는 percent escape를 해석하지 않는다. 따라서 여기서 대괄호 중복이나 디코딩에 의한 userinfo 재유입은 확인되지 않았다. [JDK 25 URI 명세](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/URI.html). 지원 형식 축소는 아래 부채 후보로 남기며, 확인한 경계에서 새 높음 자격 증명 누출은 찾지 못했다.

**파일 잠금과 다른 트랜잭션.** N3의 해시 충돌을 제외하면 다음 잠금 순서에서 새 순환 대기는 찾지 못했다.

| 경로 | 잠금 순서와 확인 |
|---|---|
| ingest | 자기 세션 행 → 배치의 모든 파일 잠금 → 사건·기다림·알림. 다른 세션 조회는 `FOR UPDATE`가 없는 일반 조회다(`SessionService.kt:345`). `session_events`의 FK는 이미 잠근 자기 세션만 참조한다. |
| 재등록 | 기기 행 → 등록 잠금 → 해당 세션 행 → 그 세션의 기다림. 파일 잠금을 요청하지 않는다(`SessionService.kt:79`, `SessionService.kt:84`, `SessionService.kt:234`). |
| sweeper | 만료 세션 행들 → 그 세션들의 기다림. 기기·등록·파일·알림 잠금을 요청하지 않는다(`SessionService.kt:224`). 같은 세션의 ingest는 세션 행에서 직렬화된다. |
| 사람의 알림 판정 | 알림 행 → 그 알림의 첫 화면 항목(`InboxApi.kt:167`, `InboxApi.kt:174`). 충돌 알림 생성도 같은 순서다(`SessionService.kt:376`, `SessionService.kt:408`). 세션·파일 잠금으로 돌아가지 않는다. |

사건별 `checkConflicts`의 파일 잠금 재취득은 이미 보유한 잠금의 재취득이다. 일반 조회는 다른 트랜잭션의 행 잠금 때문에 해당 행 잠금을 기다리지 않으며, 교착 방지에는 실제 잠금 대상의 일관된 순서가 필요하다. [PostgreSQL 17 잠금 명세](https://www.postgresql.org/docs/17/explicit-locking.html). 이는 경로·SQL 검토이며 실제 DB 동시 실행 검증은 아니다.

### V2 수정과 소유자 호출

**V2 직접 수정은 이번 조건에서 허용된다.** 기준 v0.4 「Kotlin과 Spring Boot로 쓴 서버」는 마이그레이션을 **병합된 뒤** 고치지 말라고 한다. `git ls-tree c0341ff apps/server/src/main/resources/db/migration/V2__sessions_inbox_notices.sql`의 출력은 없으며, V2는 이번 PR에서 처음 추가된다. 사용자가 제공한 「미배포·시험 밖 적용 없음」을 전제로, 미병합 V2에 `current_work_seq`와 `wait_first_seq`를 넣는 데 V3는 필요하지 않다. 시험 DB에 이전 V2가 남아 있으면 재생성이 필요하며, 배포 이력을 직접 확인한 것은 아니다.

- **조건 1의 경로 표:** 기준의 전체 패턴으로 비교했으며 일치 파일은 없다. 다만 `SessionService.kt`, `SessionApi.kt`, `InboxApi.kt`는 토큰 인증·사람 동작 거부·외부 입력·공유를 다룬다. 변경 종류 **권한·보안**에 따른 호출 대상이며 v0.4 「결정보다 넓힌 곳」 1이 적용된다.
- **조건 2:** `apps/server/src/main/resources/db/migration/V2__sessions_inbox_notices.sql`이 일치한다. 미배포이고 직접 수정이 허용된다는 판단은 소유자 호출을 면제하지 않는다.
- **조건 3:** 해당 없음. 확인한 결함에 따른 병합 막음이다.
- **조건 4·5:** 현재 이견이나 면제 시도는 확인하지 못했다. 작성 AI가 막음 지적·호출 판정에 동의하지 않거나 면제를 시도하면 소유자가 판단해야 한다.

PR 전체 변경 종류는 일반 코드·권한·보안·DB 구조·일반 문서다. 핵심 모듈 6의 기록 미작성 등록은 `docs/core-modules/README.md`에 유지돼 있다. 병합 전 소유자의 결정 댓글과 R2 수정 후 새 커밋의 독립 검토가 필요하다.

### 검증 결과

| 명령·확인 | 결과 |
|---|---|
| `git rev-parse HEAD`, `git rev-parse origin/main`, `git merge-base origin/main HEAD` | 지시된 검토·기준 커밋 확인. |
| `git diff ef84c9a HEAD`, `git diff --check c0341ff HEAD` | 수정 파일 3개 확인. 공백 검사 종료 코드 0. |
| 제공된 JDK를 `JAVA_HOME`에 지정하고 `java -version`, `apps/server`에서 `./gradlew test --no-daemon` | 버전 확인 성공. 테스트 명령은 종료 코드 1. `gradle-9.7.1-bin.zip.lck` 접근 거부로 `java.io.FileNotFoundException`. **서버 테스트는 실행되지 않았다.** 개인 절대 경로는 기록하지 않았다. |
| `docker version` | CLI 29.8.2. 엔진 접근은 `permission denied while trying to connect to the docker API`로 실패. |
| 기존 `build/test-results/test/TEST-*.xml` 읽기 | 7개 클래스, 39건, 실패·오류·건너뜀 0건이 기록돼 있다. 작성자의 HEAD에서 39건 통과 보고와 일치하지만 기존 산출물이며 **내 테스트 통과나 커밋 동일성 검증으로 취급하지 않았다**. |
| 제공된 JDK의 `jshell --execution local --feedback concise -` | 원격 파서와 같은 Java 식으로 합성 입력 24개 확인. 위 표에 결과 기재. |
| `python -B -` 메모리 계산 | 공유·기다림 SQL의 대입 규칙으로 위 반례를 추적하고, PostgreSQL 공식 해시 산식으로 합성 충돌 키를 계산했다. DB 시험이 아니다. |
| `gh pr view 11 --repo hanumoka/zm-baton --json body,headRefOid,state,isDraft,comments` | `proxyconnect tcp` 연결 거부. PR 필수 항목·원격 HEAD·소유자 댓글 확인 못 함. |

환경: Windows NT `10.0.26300.0`, PowerShell `7.6.6`, OpenJDK Temurin `25.0.4.1+1-LTS`, Codex CLI `0.162.0`. 저장소 설정은 Gradle `9.7.1`, Kotlin `2.3.21`, Spring Boot `4.1.1`, 시험 컨테이너 `postgres:17`이다. 설정·기존 산출물을 실행 성공으로 표시하지 않았다.

### 부채 후보

- 역순 공유 거부 시험, 여러 재개 사이에 반복 보고가 있는 기다림 시험, 실제 잠금 대기를 확인하는 동시성 시험을 보강할 필요가 있다. 재등록·sweeper·사람 알림 판정과의 경합도 별도 시험 대상이다.
- 원격 표시에서 IPv6 scp·밑줄 호스트·한 글자 호스트를 버리는 지원 범위를 정하고 시험으로 남길 필요가 있다. 현재 충돌 판정은 별도 `repo_key`를 쓰므로 원격 표시 누락만으로 충돌 감지가 사라지지는 않는다.
- 기준 항목 8: `SessionService.kt`는 605줄이다. 1,000줄 금지에는 걸리지 않지만 500줄 초과 이유와 PR 분할 검토 기록은 PR 조회 실패로 확인하지 못했다.
- 기준 항목 9·10: 이번 수정은 새 의존성·제삼자 포함물을 추가하지 않는다. 코드 출처의 전수 감사는 하지 않았다. 항목 11의 부채 후보는 이 절에 기록했다.
- 기준 항목 12: 1차 기록은 과거 커밋의 검토로 보존된다. 현재 코드 판정은 이 기록을 사용해야 한다. 사람 인증 등 1차에서 명시한 미구현 범위가 이번 수정으로 해소된 것은 아니다.

### 확인하지 않은 것

- 서버 컴파일·39개 테스트의 독립 실행, 실제 PostgreSQL에서 R2·N1·N3 재현, DB 경합 관찰, 컴파일 경고 0건 여부.
- PR의 필수 여덟 항목, 최신 원격 커밋, CI, 소유자 호출 시각·결정 댓글, 병합 직전 조건. 조회 실패를 통과로 표시하지 않았다.
- V2의 실제 배포·적용 이력 전수 조사, 운영 DB 변경·복구, Linux·macOS, 실제 CLI 훅, 공급자 인증, 사람 인증과 다중 사용자 운영. 지정된 절 밖의 비공개 설계는 재검토하지 않았다.

지정된 2차 검토 기록만 작성했다. 제품 코드·1차 기록 수정, 커밋·push·병합은 하지 않았다.
