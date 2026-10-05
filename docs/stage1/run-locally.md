# 로컬에서 돌려 보기

**목적**: 1단계 설치·호환 시험으로 만든 것(서버, 로컬 연결 프로그램, Web 화면, 데스크톱 껍데기)을 소유자가 자기 PC에서 직접 돌려 본다. 핵심 모듈 2·3의 재현 시험([docs/core-modules/README.md](../core-modules/README.md))을 돌릴 때도 이 순서를 쓴다.
**대상**: Windows 11 PC. 명령은 PowerShell 기준이다.
**마지막 검토**: 2026-10-05. 작성 AI가 이 순서를 그대로 따라 해 완료 확인 세 가지를 만족했다. 다만 서버는 창 대신 백그라운드로 띄웠고, 화면의 버튼 대신 같은 API 두 개를 불렀다.

## 무엇이 어디에 뜨는가

| 무엇 | 주소 | 이 PC 밖에서 보이는가 |
|---|---|---|
| PostgreSQL 17(Docker) | `127.0.0.1:15432` | 아니다 |
| 서버(API와 Web 화면) | `http://127.0.0.1:18081/` | 아니다. 인증이 아직 없어 루프백에만 연다 |
| 로컬 연결 프로그램 | 서버에 접속만 한다 | 해당 없음 |
| 데스크톱 껍데기 | 서버의 화면을 창에 띄운다 | 해당 없음 |

8080 포트는 쓰지 않는다.

## 전제조건

- Docker Desktop이 실행 중이다.
- JDK 25, Go 1.27, Node.js 24가 설치돼 있다. 막 설치했다면 PowerShell 창을 새로 연다. 설치 전에 연 창은 바뀐 PATH를 모른다(`go`를 찾지 못한다).
- Claude Code(`claude.exe`)가 설치돼 있고 소유자가 직접 로그인해 둔 상태다. zm-baton은 로그인 정보를 읽거나 보내지 않는다.
- 연결 프로그램은 Claude Code를 **소유자 개인 구독**으로 돌린다. 아래 예시는 파일 한 줄을 읽는 짧은 지시라 사용량이 작다.

## 창 셋을 쓴다

서버와 데스크톱 껍데기는 끝날 때까지 창을 차지하므로 PowerShell 창 셋을 쓴다.

| 창 | 하는 일 |
|---|---|
| 창 A | 1~3단계. 서버를 띄운 채 둔다 |
| 창 B | 4단계. 데스크톱 껍데기를 띄운 채 둔다(선택) |
| 창 C | 5단계. 연결 프로그램을 한 번 돌린다 |

**모든 명령 묶음은 저장소 루트에서 시작한다.** 창마다 먼저 저장소 경로를 `$repo`에 담는다. 각 묶음의 첫 줄이 `$repo` 기준으로 옮겨 가므로, 앞 묶음이 어디서 끝났든 상관없다.

```powershell
$repo = '<이 저장소를 내려받은 절대 경로>'   # 구분자는 / 로 쓴다. 예: C:/work/zm-baton
```

경로 구분자를 `/`로 쓰는 것은 3단계의 `ZM_BATON_WEB_DIR`이 `file:` 주소이기 때문이다. 시험은 이 형식으로만 했다.

## 단계

### 1. 데이터베이스를 띄운다(창 A)

```powershell
Set-Location "$repo/deploy/compose"
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# 처음이면 .env 의 ZM_BATON_DB_PASSWORD 를 이 PC에서만 쓸 임의의 값으로 바꾼다. .env 는 Git에 들어가지 않는다.
# 이미 .env 가 있으면 그대로 둔다. PostgreSQL은 볼륨을 처음 만들 때만 비밀번호를 정하므로, 바꾸면 접속이 실패한다.
docker compose up -d
docker compose ps   # zm-baton-dev-postgres-1 이 Up, 127.0.0.1:15432 로 보이면 된다
```

### 2. Web 화면을 빌드한다(창 A)

```powershell
Set-Location "$repo/apps/web"
npm ci
npm run build       # dist/ 가 생긴다
```

### 3. 서버를 띄운다(창 A)

```powershell
Set-Location "$repo/apps/server"
./gradlew.bat bootJar
$env:ZM_BATON_DB_PASSWORD = '<.env 에 적은 값>'
$env:ZM_BATON_WEB_DIR = "file:$repo/apps/web/dist/"   # 끝의 / 를 빼지 않는다
java -jar build/libs/server-0.0.1-SNAPSHOT.jar
```

서버는 창 A를 차지한다. 창 C에서 확인한다.

```powershell
Invoke-RestMethod http://127.0.0.1:18081/actuator/health   # status 가 UP
```

브라우저로 `http://127.0.0.1:18081/`을 열면 화면이 보인다.

### 4. 데스크톱 껍데기로 열어 본다(창 B, 선택)

```powershell
Set-Location "$repo/apps/desktop"
npm ci
npm start
```

같은 화면이 창으로 뜬다. 서버 밖 주소로는 이동하지 않는다.

### 5. 연결 프로그램으로 Claude Code를 한 번 돌린다(창 C)

1. 임시 폴더(`$env:TEMP`) 밖에 빈 폴더를 하나 만들고, 그 안에 `hello.txt`를 둔다. 연결 프로그램은 임시 폴더 안의 작업 폴더를 거부한다.
2. 화면 왼쪽 위 입력란에 제목을 쓰고 「작업과 실행 시도 만들기」를 누른다.
3. 연결 프로그램을 빌드하고 한 번만 실행한다.

```powershell
Set-Location "$repo/apps/agent"
go build -o bin/zm-baton-agent.exe ./cmd/zm-baton-agent
./bin/zm-baton-agent.exe --device-id dev_me --workdir '<만든 폴더>' --prompt 'Read hello.txt in the current folder and reply with its first line only.' --once
```

4. 화면에서 그 작업을 고르면 보고 상태가 「청구 대기 → 작업 중 → 완료」로 바뀌고, 사건 목록에 도구 호출·도구 결과·답이 보인다.

## 완료 확인

- 연결 프로그램이 종료 코드 0으로 끝났다.
- 화면의 실행 시도 상태가 `ended/submitted`이고 보고 상태가 「완료」다.
- 사건 목록의 답이 `hello.txt`의 첫 줄과 같다.

## 실패하면

| 증상 | 원인과 대응 |
|---|---|
| `docker compose up`이 비밀번호를 요구하며 멈춘다 | `.env`가 없거나 `ZM_BATON_DB_PASSWORD`가 비었다. 1단계를 다시 한다 |
| 서버가 시작하다 DB 연결 오류로 끝난다 | 컨테이너가 떠 있는지, 서버 창의 `ZM_BATON_DB_PASSWORD`가 `.env`와 같은지 본다 |
| 서버가 18081 포트를 이미 쓰고 있다며 끝난다 | 이 절차로 띄운 서버가 다른 창에서 아직 돌고 있으면 그 창에서 Ctrl+C로 끈다. 무엇이 그 포트를 쓰는지 모르면 **끄지 않는다.** 대신 서버를 다른 포트로 띄운다: 창 A에서 `$env:ZM_BATON_SERVER_PORT = '18082'`를 준 뒤 다시 실행하고, 연결 프로그램에는 `--server http://127.0.0.1:18082`, 데스크톱 껍데기에는 `$env:ZM_BATON_SERVER_URL = 'http://127.0.0.1:18082/'`를 준다 |
| 화면 주소가 404다 | `ZM_BATON_WEB_DIR`이 `file:`로 시작하고 `/`로 끝나는지, `apps/web/dist/`가 있는지 본다 |
| 연결 프로그램이 `bad --workdir`로 끝난다 | 오류 줄의 내용을 본다. 폴더가 없거나, 폴더가 아니라 파일이거나, 임시 폴더(`TMP`·`TEMP`·`TMPDIR` 포함) 안에 있을 때 거부한다. 각각 폴더를 만들거나, 폴더 경로를 주거나, 임시 폴더 밖으로 옮긴다 |
| 연결 프로그램이 `bad --claude`로 끝난다 | `claude`가 PATH에 없거나 `.cmd`·`.bat`다. `--claude`로 `claude.exe` 경로를 준다 |
| 연결 프로그램이 할 일을 못 찾고 기다린다 | 화면에서 실행 시도를 만들었는지, 서버 주소가 기본값(`http://127.0.0.1:18081`)인지 본다 |

## 정리

1. 연결 프로그램은 `--once`이면 스스로 끝난다.
2. 서버 창에서 Ctrl+C로 서버를 끈다.
3. `Set-Location "$repo/deploy/compose"` 뒤 `docker compose down`으로 컨테이너를 끈다. 자료는 볼륨에 남는다. 자료까지 지우려면 `docker compose down -v`.

## 핵심 모듈 재현 시험에 쓸 명령

핵심 모듈 기록의 「재현 시험 하나」에 쓸 수 있는 명령이다. 어느 것을 쓸지는 기록을 쓰는 소유자가 정한다. 명령은 `$repo`를 담은 아무 창에서나 실행한다.

| 모듈 | 무엇을 재현하는가 | 명령 |
|---|---|---|
| 2 실행 계약과 로컬 연결 프로그램 | 같은 실행 시도를 여럿이 동시에 청구하면 하나만 성공한다 | `Set-Location "$repo/apps/server"; ./gradlew.bat test --tests '*RunServiceTests*concurrent*'` |
| 2 | 옛 세대의 늦은 보고는 기록만 되고 상태를 바꾸지 않는다 | `Set-Location "$repo/apps/server"; ./gradlew.bat test --tests '*RunServiceTests*older generation*'` |
| 3 실행기 어댑터와 실행 보고 표준 | 표식이 맞을 때만 실행기를 끝낸다 | `Set-Location "$repo/apps/agent"; go test -v -run TestKill ./internal/proc` |
| 3 | Claude Code 출력이 계약의 사건으로 바뀐다 | 위 「5. 연결 프로그램으로 Claude Code를 한 번 돌린다」 |
