# apps/agent — 로컬 연결 프로그램

1단계 설치·호환 시험([docs/stage1/compat-test.md](../../docs/stage1/compat-test.md) 3항)에 쓰는 Go 프로그램이다. 표준 라이브러리만 쓴다.

## 하는 일

1. 서버(`apps/server`)에 할 일(`GET /api/runs?state=requested`)을 주기적으로 묻는다. 실행기가 `claude_code`인 실행 시도만 청구한다. 위임으로 만든 실행 시도(`delegation_id`가 있는 것)는 가져가지 않는다. 그것은 아래 `zm-baton agent`의 몫이다. 다른 연결 프로그램이 먼저 가져가 409를 받으면 다음 것으로 넘어간다.
2. 청구에 성공하면 실행 시도마다 새 UUIDv4 세션 번호를 만든다. 그 번호로 Claude Code를 셸 없이 직접 띄운다.

   ```text
   claude -p --output-format stream-json --verbose --session-id <UUID> --permission-prompts none --allowedTools <목록> --restricted --strict-mcp-config -- <프롬프트>
   ```

   - 작업 폴더는 `--workdir`이다. 띄운 직후 PID를 기록한다.
   - Windows에서는 실행기를 일시 정지 상태로 띄운다. 그 프로세스의 핸들을 열고, 새 작업 객체(Job Object)에 넣은 뒤에 실행을 재개한다. 그래서 실행기가 보통 방법으로 띄우는 하위 프로세스는 같은 작업 객체에 들어간다. WMI처럼 다른 경로로 띄운 프로세스는 들어가지 않을 수 있다.
   - 프롬프트 앞의 `--`는 프롬프트가 `--allowedTools`의 값이나 옵션으로 읽히지 않게 막는다.
   - `--restricted`와 `--strict-mcp-config`(Claude Code 2.1.296, 2026-10-14부터)는 이 PC의 사용자·프로젝트 설정(권한 모드 `auto` 등)과 사용자의 MCP 서버가 `--allowed-tools`를 넓히지 못하게 한다. 명령 실행 도구(Bash·PowerShell)는 빠진다.
   - 청구한 실행 시도의 임대를 5초마다 갱신한다(`POST /api/runs/{id}/lease`). 서버가 중단을 요청하면(`stop: cancel`) 아래 5번 방법으로 실행기를 멈추고 `cancelled`로 끝낸다. 서버가 그 실행을 더는 잡고 있지 않다고 답해도(409) 같다. 갱신 API가 없는 옛 서버면 갱신하지 않고 계속 돈다.
3. 출력 한 줄을 협업 계약 v1의 사건으로 바꾼다.
   - `thinking`은 `thought`, `tool_use`는 `action`, `tool_result`는 `action_result`, `text`는 `answer`가 된다.
   - `rate_limit_event`와 `result`의 사용량은 `usage`가 된다. 실패한 `result`는 `error`가 된다.
   - 그 밖의 줄(`system` 등)은 보내지 않고 종류별 개수만 로컬 로그에 남긴다.
4. 사건마다 `seq`(1부터)와 `event_id`(`evt_`와 UUIDv7)를 붙인다. 0.5초마다, 그리고 끝날 때 묶어 보낸다. 다시 보낼 때는 같은 `event_id`를 쓴다.
   - 시작할 때 `lifecycle` `started`(세션 번호 포함)를 보낸다. 끝날 때 `lifecycle` `ended`를 보낸다. 보고는 `protocol_version` 2이고, `ended`에는 실행기를 띄웠는지(`executor_started`)와 결과를 받았는지(`result_seen`)가 실린다. 서버는 띄운 뒤 결과 없이 끝난 실행의 작업에 「결과 확인 필요」를 단다.
   - 성공한 `result`가 오면 종료 이유는 `submitted`이고, 오류 `result`가 오면 `failed`다.
   - `result` 없이 출력이 끝나면 `lost`다. 무엇을 했는지 알 수 없으므로 서버가 작업에 「결과 확인 필요」를 단다.
   - 실행 시간 제한에 걸려 멈추면 `failed`다.
   - 중단하면 종료 이유는 `cancelled`이다.
5. 실행기를 끝낼 때는 띄울 때 붙잡아 둔 `proc.Run`의 `Kill(세션 번호)`만 쓴다.
   - 열어 둔 핸들이 PID를 붙잡고 있으므로, 표식을 확인하는 동안 그 PID가 다른 프로세스에게 넘어가지 않는다. 핸들은 종료 판단이 끝난 뒤에 닫는다.
   - 실행기가 살아 있고 명령줄에 세션 번호가 있을 때만 끝낸다. Windows는 살아 있는지와 명령줄을 모두 붙잡은 핸들로 확인하고, 작업 객체를 통째로 끝낸다. PID로 다시 찾지 않는다.
   - 표식이 비었거나 16자보다 짧거나 명령줄에 없으면 아무것도 끝내지 않는다. 확인할 때 실행기가 이미 끝났으면 아무것도 끝내지 않는다. 확인과 종료 사이에 실행기가 끝나고 종료 호출이 성공하면, 그 작업 객체에 남은 하위 프로세스만 끝난다. 그 사이 명령줄 조회가 실패하면 아무것도 끝내지 않는다. 이름으로 프로세스를 고르지 않는다.
   - Linux에서는 Go가 쥔 프로세스 객체로 실행기 하나만 끝낸다. 하위 프로세스는 끝내지 않는다. macOS는 명령줄을 읽을 수 없어 종료를 거부한다. 두 OS 모두 실제로 시험하지 않았다.
6. 서버가 사건을 받지 못했거나 거절했으면, 실행기가 성공했어도 「전달 실패」로 따로 알린다. 종료 대기는 30초까지다.
   - `--once`이면 종료 코드 1로 끝난다.
   - `--once`가 아니면 오류를 로그에 남기고 다음 할 일로 넘어간다. 보내지 못한 사건은 다시 보내지 않는다(디스크 보관은 이 시험 범위 밖).

## 플래그

| 플래그 | 기본값 | 뜻 |
|---|---|---|
| `--server` | `http://127.0.0.1:18081` | 서버 주소. 서버는 기본으로 이 PC 안에서만 접속을 받는다 |
| `--device-id` | 없음(필수) | 청구할 때 보내는 기기 번호. 예: `dev_compat_a` |
| `--workdir` | 없음(필수) | 실행기 작업 폴더. 있어야 하고, OS 임시 폴더(`TMP`·`TEMP`·`TMPDIR` 포함) 안이면 거부한다 |
| `--prompt` | 없음(필수) | Claude Code에 넘길 프롬프트 |
| `--once` | `false` | 하나를 청구해 실행하고 끝낸다. `submitted`로 끝나고 서버가 사건을 모두 받았을 때만 종료 코드 0 |
| `--poll-interval` | `2s` | 할 일이 없을 때 다시 묻기까지 기다리는 시간 |
| `--allowed-tools` | `Read` | Claude Code `--allowedTools`에 넘길 쉼표 목록 |
| `--claude` | `claude` | Claude Code 실행 파일. `.cmd`·`.bat`는 cmd.exe를 거치므로 거부한다 |
| `--run-timeout` | `15m` | 이 시간이 지나면 실행기를 끝낸다. `0`이면 제한 없음 |

종료 코드: 0 정상, 1 실패(`--once`에서 `submitted`가 아니거나 서버에 사건이 다 전달되지 않음), 2 플래그 오류, 130 Ctrl+C(할 일을 묻는 중이든 실행 중이든).

## 빌드와 시험

Go 1.27이 필요하다. `apps/agent`에서 실행한다.

```sh
go vet ./...
go test ./...
go build -o bin/zm-baton-agent.exe ./cmd/zm-baton-agent
```

- 시험은 진짜 Claude Code와 서버를 띄우지 않는다. 실행기 자리에는 시험 바이너리 자신을 「가짜 Claude」로 띄운다. 서버 자리에는 `httptest` 서버를 쓴다.
- `proc` 시험이 끝내는 프로세스는 시험이 직접 띄운 도우미 프로세스뿐이다. 도우미는 무작위 표식을 명령줄에 단다. 살았는지 끝났는지는 열어 둔 핸들로 판정하고, 조회 실패를 종료로 치지 않는다.
- `bin/`은 Git에 넣지 않는다.

## 이 시험에서 하지 않는 것

다음은 이 시험이 끝난 뒤 1단계 본작업에서 한다: WebSocket 깨우기, 보내지 못한 사건의 디스크 보관, `request_id`, 질문 경로. 계정에 따른 인증 방식 고정과 설정 격리(`CLAUDE_CONFIG_DIR`)는 아래 `zm-baton agent`에만 있고, 이 시험은 그대로다. 임대 갱신은 둘째 판에서 이 시험에도 들어왔고, 끊긴 실행의 재개는 아래 `zm-baton agent`에 있다. 도구 결과 본문은 4,000자로 자르기만 하고 비밀값을 가리지 않는다.

## `zm-baton repo`·`zm-baton agent` — 맡긴 작업을 실행한다

같은 모듈의 `zm-baton` 명령에 있다(`cmd/zm-baton/handoff.go`, `internal/agent/handoff.go`, `internal/workspace`). 협업 계약 v1 「위임」·「실행마다 별도 작업 트리」를 따른다. 쓰는 순서는 [docs/stage1/handoff.md](../../docs/stage1/handoff.md)에 있다.

1. `zm-baton repo add [폴더] [--worktrees 폴더]`가 이 기기에서 맡긴 일을 실행할 저장소를 등록한다. 서버에는 저장소 키(`git:<첫 커밋>`)·폴더 이름·자격 증명을 뺀 원격 주소만 보내고, 절대 경로는 설정 폴더의 `repos.json`에만 둔다. 저장소와 작업 트리 폴더가 임시 폴더 안이면 거부한다. `repo list`·`repo remove`가 있다.
2. `zm-baton agent`가 이 기기 몫의 위임(`GET /api/runs?state=requested&device_id=…`) 가운데 `repos.json`에 있는 저장소의 것만 청구한다.
3. 청구한 실행마다 그 저장소에 새 작업 트리(`<작업 트리 폴더>/<실행 시도 번호>`)와 전용 브랜치(`zm-baton/<실행 시도 번호>`)를 만든다. 기준 커밋은 위임의 기준 브랜치 끝, 없으면 저장소의 지금 커밋이다.
4. 그 작업 트리에서 서버가 만든 지시로 Claude Code를 띄운다. 도구는 위임이 정한 것(알려진 다섯 가지 `Read`·`Glob`·`Grep`·`Edit`·`Write` 안)만 `--allowedTools`로 넘기고, `--restricted`를 더해 사용자 설정의 권한 모드·허용 규칙이 넓히지 못하게 한다. 실행기 환경에 `ZM_BATON_OFF=1`을 넣어 소유자의 zm-baton 훅이 이 실행을 직접 연 세션으로 등록하지 않게 한다.
5. 시작 보고와 끝 보고에 작업 트리(저장소 키·기준 커밋·브랜치·기준 브랜치)를 싣는다. 실행 중 60초마다와 끝날 때 `git_changes`(바뀐 파일, 원래 체크아웃에도 커밋하지 않은 변경이 있는 파일, 끝날 때 기준 브랜치에서도 바뀐 파일)를 보낸다. 끝날 때는 작업 트리의 내용을 임시 인덱스로 묶은 스냅샷 커밋을 `refs/zm-baton/snapshots/<실행 시도 번호>`에 두고 그 SHA를 `git_changes`의 `snapshot`으로 보낸다. 작업 트리의 파일·인덱스·브랜치는 바꾸지 않는다(협업 계약 v1 「검토 대상을 고정한다」, [docs/stage1/human-review.md](../../docs/stage1/human-review.md)). 도구가 보고하는 절대 경로에서 작업 트리 경로를 지워 보낸다.
6. 실행이 끝나도 커밋하거나 작업 트리를 지우지 않는다.
7. 실행기를 띄우기 전에 준비 검사를 한다(`internal/agent/readiness.go`): 위임 범위, Claude Code 판(2.1.296 이상), 등록한 폴더의 첫 커밋이 저장소 키와 같은지, 작업 트리 폴더, 디스크(512 MB 이상), 기준 브랜치, 작업 트리. 미달이면 실행기를 띄우지 않고 `executor_started: false`와 끝 보고의 `readiness`(항목마다 통과 여부와 경로 없는 이유)로 끝낸다. 서버는 작업에 `readiness_failed`를 단다.
8. 맡긴 실행은 지시를 표준 입력(`--input-format stream-json --replay-user-messages`)으로 넣는다. 임대 갱신의 답으로 받은 사람의 지시도 같은 입력에 넣고, Claude Code가 되돌려 보내면 받은 것으로 친다. 결과가 오면 입력을 닫는다. 지시마다의 상태는 다음 갱신과 끝 보고의 `inputs`로 보낸다.
9. 이어서 맡긴 실행(청구 응답의 `resume`)은 같은 기기이고 `runs/<이전 실행 시도 번호>.json`(설정 폴더)이 이전 실행기와 하위 프로세스가 끝났다고 말할 때만 이전 작업 트리와 세션(`--resume`)을 이어 쓴다. 작업 트리 폴더 이름은 전용 브랜치 `zm-baton/<실행 시도 번호>`의 번호로 찾는다. 여러 번 재개해도 그 폴더와 브랜치는 유지하고, 스냅샷 참조에는 현재 실행 시도 번호를 쓴다. 아니면 이전 실행 브랜치 끝에서 새 작업 트리와 새 세션으로, 다른 기기면 기준에서 시작한다. 어느 쪽인지는 작업 트리 보고의 `origin`과 지시 끝에 덧붙인 문장으로 알린다. Claude Code에 그 세션이 없으면 같은 작업 트리에서 새 세션으로 다시 띄운다.

10. 위임마다 AI 계정이 붙는다(청구 응답의 `account`·`limits`, `internal/agent/auth.go`). 계정이 정한 인증 방식이 아닌 자격 환경 변수(`CLAUDE_CODE_USE_BEDROCK`·`CLAUDE_CODE_USE_VERTEX`·`CLAUDE_CODE_USE_FOUNDRY`·`ANTHROPIC_AUTH_TOKEN`·`ANTHROPIC_API_KEY`·`CLAUDE_CODE_OAUTH_TOKEN`·`ANTHROPIC_PROFILE`)를 실행기 환경에서 빼고, 뺀 이름을 준비 검사의 `account` 항목에 적는다. 값은 읽어 남기거나 보내지 않는다. API 키 계정은 이 창의 `ANTHROPIC_API_KEY`가 있어야 하고, 설정 폴더의 `executors/<계정 번호>/claude`를 `CLAUDE_CONFIG_DIR`로 넘긴다. 구독 계정은 사람의 설정 폴더를 그대로 쓴다(그곳에 로그인이 있다). 턴·비용 상한은 `--max-turns`·`--max-budget-usd`로 넘기고, 시간 상한은 연결 프로그램이 잰다. 시작 보고에 `auth_method`, 끝 보고에 실행기 첫 줄의 `apiKeySource`로 확인한 `auth`와 닿은 상한 `limit_hit`을 싣는다. 쓰는 순서는 [docs/stage1/accounts.md](../../docs/stage1/accounts.md)에 있다.

종료 코드는 실행 시험과 같다: 0 정상(`--once`이면 `submitted`이고 서버가 사건을 다 받음), 1 실패, 2 플래그·설정 오류, 130 Ctrl+C. `--run-timeout` 기본값은 0이고, 0이면 계정의 「실행마다 시간」을 쓴다(서버가 보내지 않으면 30분). 값을 주면 그것이 더 짧을 때만 쓴다.

## `zm-baton` 명령 — 사람이 직접 연 AI 세션을 서버에 잇는다

같은 Go 모듈의 두 번째 프로그램이다(`cmd/zm-baton`, `internal/session`). 협업 계약 v1 「직접 연 AI 세션을 서버에 등록한다」(`protocol_version` 2)를 따른다. 지금은 Claude Code 세션만 잇는다.

1. `zm-baton login --server http://127.0.0.1:18081`으로 이 기기를 한 번 등록한다. 설정과 기기 토큰은 사용자 설정 폴더의 `zm-baton/config.json`에 둔다(`ZM_BATON_HOME`으로 바꿀 수 있다). 서버가 `local` 모드면 바로 등록되고, `required` 모드면 명령이 보여 주는 주소를 웹에 로그인한 브라우저에서 열어 코드를 허락한다. 기기 토큰이 없는 기기(이 판 전에 등록한 기기)에서 다시 실행하면 같은 기기 번호로 토큰만 받는다. 연결 프로그램·훅·`repo`·`import`·`export`는 그 토큰을 저장한 서버에만 붙인다. 자세한 것은 [인증 안내](../../docs/stage1/auth.md)에 있다.
2. 훅은 Claude Code 플러그인으로 붙는다. zm-baton은 Claude Code 설정 파일을 고치지 않는다.
   1. `zm-baton hooks install`이 zm-baton 설정 폴더의 `claude-plugin/`에 로컬 마켓플레이스(`zm-baton-local`)와 플러그인(`zm-baton`, 훅 일곱)을 만들고, 붙이는 명령을 보여 준다.
   2. 사람이 Claude Code의 명령을 실행한다: `claude plugin marketplace add "<그 폴더>"`(처음 한 번), 그리고 `claude plugin install zm-baton@zm-baton-local --scope user`. `--project <폴더>`를 주면 그 폴더에서 `--scope local`로 설치하라고 보여 주며, 그 저장소에서 연 세션에만 붙는다. 설정 파일은 Claude Code가 고친다.
   3. 플러그인은 그 폴더를 그대로 읽으므로, 실행 파일을 옮기거나 훅 목록이 바뀐 판으로 올리면 `hooks install`만 다시 실행한다. 새로 여는 세션부터, 이미 열린 세션은 `/reload-plugins` 뒤부터 적용된다.
   4. `zm-baton hooks uninstall`은 빼는 명령(`claude plugin uninstall …`, 마켓플레이스 지우기)을 보여 준다. 플러그인 폴더는 그 뒤에 지운다.
3. 그 뒤 새로 여는 Claude Code 세션은 시작할 때 서버에 등록되고, 서버가 돌려준 현재 상태 카드가 AI의 맥락에 들어간다. 파일을 고치는 도구(`Edit`·`Write`·`MultiEdit`·`NotebookEdit`)가 끝나면 저장소 기준 상대 경로·`git hash-object`·HEAD를 보낸다. 알림 훅은 기다림을, 다음 입력이나 도구 실행은 재개를, 끝 훅은 세션 끝을 보낸다. 기다림으로 보내는 알림은 사람을 기다리는 다섯 종류(`permission_prompt`·`idle_prompt`·`elicitation_dialog`·`elicitation_url_dialog`·`agent_needs_input`)뿐이다. 알림 문구는 보내지 않는다. 예외는 도구 이름만 담은 `Claude needs your permission to use <도구>` 한 가지다. 재개를 보내지 못하면 다음 도구 실행에서 다시 보낸다. 서버가 등록을 살아 있지 않다고 답하면(HTTP 401, 12시간 동안 사건이 없어 토큰이 끝났을 때 등) 그 훅이 다시 등록하고 한 번 더 보낸다. 끝 훅은 다시 등록하지 않는다.
4. 권한 요청 훅(`PermissionRequest`)과 선택형 질문(`PreToolUse`, `AskUserQuestion`만)은 첫 화면으로 중계한다(계약 「권한 요청과 선택형 질문을 첫 화면으로 중계한다」). 단 서버가 `ZM_BATON_AUTH=required`와 `ZM_BATON_RELAY=on`으로 떠 있을 때만이다. 기본은 꺼짐이고, 훅은 등록할 때 서버가 알려 준 켜짐을 상태 파일에 두어 꺼져 있으면 서버에 묻지 않고 곧바로 끝난다(로그인한 사람만 답하게 하려는 것이다. 고도화 후보 H-21). 훅이 도구 입력에 비밀값 검사를 하고, 걸리면 상세를 보내지 않는다. 통과하면 중계 번호·입력 지문(SHA-256)·사람이 판단할 상세(도구 이름, 명령 전문이나 저장소 기준 파일 경로, 실행기 설명, 작업 위치)를 보내고, 0.5초마다 답을 묻는다. 사람이 첫 화면에서 누른 답을 서버에서 가져왔을 때만 결정(허용, 사유를 붙인 거절, 고른 답)을 Claude Code 형식으로 출력한다. 서버가 없거나, 기한이 지나거나, 지문이 다르거나, 무엇이든 어긋나면 아무것도 출력하지 않고 끝나 Claude Code가 터미널에서 묻는다. 훅의 시간 상한은 80초, 플러그인 대기 시간은 90초로 서버 기한(시작값 60초)보다 길다.
5. 중계 상세는 첫 화면에서만 쓴다. 항목이 닫히고 7일이 지나면 서버가 지운다.
6. 세션 안에서 `zm-baton session work "<무엇을, 어디서>"`로 「지금 하는 일」을 올린다(300자 이하, `--ref`). `--private`를 한 번 주면 그 세션의 이후 요약도 다른 세션에 보이지 않는다. `--share`로 되돌린다. 요약마다 공유 여부를 함께 보내므로, 늦게 도착한 요약이 다른 요약의 공유 설정을 바꾸지 못한다.
7. `zm-baton session off`는 먼저 이 세션의 보고를 멈추고, 그다음 서버에 등록 해제를 알린다. 서버에 알리지 못하면 보고는 멈춘 채로 다시 보낼 것을 남긴다. 같은 명령을 다시 실행하면 같은 해제를 다시 보낸다. 해제하는 동안 끝난 재등록도 해제한다. 시작 전에 끄려면 `ZM_BATON_OFF=1`을 둔다.

지키는 것:

- 훅은 늘 종료 코드 0으로 끝나고, 실패해도 세션에 아무것도 출력하지 않는다. 시간 상한은 시작 4초, 세션 끝 1초, 중계 80초, 나머지 3초다. 입력 읽기·상태 잠금·서버 요청·git이 모두 이 상한에서 멈추고, 그래도 끝나지 않으면 상한 0.25초 뒤에 프로세스를 끝낸다. 실패는 설정 폴더의 `hook.log`에 남는다.
- 한 세션의 훅 여럿이 동시에 돌 수 있어 상태 파일은 기기 하나에 하나인 잠금(`sessions/.lock`) 안에서만 바꾼다. 잠금은 운영체제의 파일 잠금(Windows `LockFileEx`, 그 밖 `flock`)이라, 잠근 프로세스가 죽으면 운영체제가 푼다. 오래된 잠금을 추측해 지우지 않는다.
- 실행기 버전은 보내지 않는다. 훅 입력과 환경 어디에도 이 세션의 Claude Code 버전이 없고, 물려받은 `CLAUDE_CODE_VERSION`은 부모 세션의 값이다.
- 기기의 절대 경로는 보내지 않는다. 저장소는 첫 커밋 번호(`git:<SHA>`)로 식별하고, 원격 주소는 호스트와 경로만 남긴다(자격 증명·쿼리·scp 형식의 사용자 부분을 버린다).
- 세션 토큰은 설정 폴더의 `sessions/` 아래 파일에만 둔다. 세션 번호는 Claude Code가 훅에 주는 `session_id`이고, `session work`·`off`는 `CLAUDE_CODE_SESSION_ID` 환경 변수(공식 문서에는 없고 2.1.296에서 확인)나 `--session`으로 찾는다.
- 셸 명령으로 바꾼 파일은 보고되지 않는다. 등록하지 않았거나 등록을 끈 세션은 충돌 판정에서 빠진다.

```sh
go build -o bin/zm-baton.exe ./cmd/zm-baton
```
