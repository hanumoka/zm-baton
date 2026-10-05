# apps/agent — 로컬 연결 프로그램

1단계 설치·호환 시험([docs/stage1/compat-test.md](../../docs/stage1/compat-test.md) 3항)에 쓰는 Go 프로그램이다. 표준 라이브러리만 쓴다.

## 하는 일

1. 서버(`apps/server`)에 할 일(`GET /api/runs?state=requested`)을 주기적으로 묻는다. 실행기가 `claude_code`인 실행 시도만 청구한다. 다른 연결 프로그램이 먼저 가져가 409를 받으면 다음 것으로 넘어간다.
2. 청구에 성공하면 실행 시도마다 새 UUIDv4 세션 번호를 만든다. 그 번호로 Claude Code를 셸 없이 직접 띄운다.

   ```text
   claude -p --output-format stream-json --verbose --session-id <UUID> --permission-prompts none --allowedTools <목록> -- <프롬프트>
   ```

   - 작업 폴더는 `--workdir`이다. 띄운 직후 PID를 기록한다.
   - 프롬프트 앞의 `--`는 프롬프트가 `--allowedTools`의 값이나 옵션으로 읽히지 않게 막는다.
3. 출력 한 줄을 협업 계약 v1의 사건으로 바꾼다.
   - `thinking`은 `thought`, `tool_use`는 `action`, `tool_result`는 `action_result`, `text`는 `answer`가 된다.
   - `rate_limit_event`와 `result`의 사용량은 `usage`가 된다. 실패한 `result`는 `error`가 된다.
   - 그 밖의 줄(`system` 등)은 보내지 않고 종류별 개수만 로컬 로그에 남긴다.
4. 사건마다 `seq`(1부터)와 `event_id`(`evt_`와 UUIDv7)를 붙인다. 0.5초마다, 그리고 끝날 때 묶어 보낸다. 다시 보낼 때는 같은 `event_id`를 쓴다.
   - 시작할 때 `lifecycle` `started`(세션 번호 포함)를 보낸다. 끝날 때 `lifecycle` `ended`를 보낸다.
   - 성공한 `result`가 오면 종료 이유는 `submitted`이고, 오류 `result`가 오면 `failed`다.
   - `result` 없이 출력이 끝나면 `lost`다. 무엇을 했는지 알 수 없으므로 서버가 작업에 「결과 확인 필요」를 단다.
   - 실행 시간 제한에 걸려 멈추면 `failed`다.
   - 중단하면 종료 이유는 `cancelled`이다.
5. 실행기를 끝낼 때는 `proc.KillRun(pid, 세션 번호)`만 쓴다.
   - 기록한 PID의 명령줄을 먼저 읽는다. 세션 번호가 있을 때만 그 PID와 하위 프로세스를 끝낸다. Windows는 `taskkill /T /F`를 쓴다.
   - 표식이 비었거나 16자보다 짧거나 명령줄에 없으면 아무것도 끝내지 않는다. 이름으로 프로세스를 고르지 않는다.

## 플래그

| 플래그 | 기본값 | 뜻 |
|---|---|---|
| `--server` | `http://127.0.0.1:18081` | 서버 주소. 서버는 기본으로 이 PC 안에서만 접속을 받는다 |
| `--device-id` | 없음(필수) | 청구할 때 보내는 기기 번호. 예: `dev_compat_a` |
| `--workdir` | 없음(필수) | 실행기 작업 폴더. 있어야 하고, OS 임시 폴더(`TMP`·`TEMP`·`TMPDIR` 포함) 안이면 거부한다 |
| `--prompt` | 없음(필수) | Claude Code에 넘길 프롬프트 |
| `--once` | `false` | 하나를 청구해 실행하고 끝낸다. `submitted`로 끝났을 때만 종료 코드 0 |
| `--poll-interval` | `2s` | 할 일이 없을 때 다시 묻기까지 기다리는 시간 |
| `--allowed-tools` | `Read` | Claude Code `--allowedTools`에 넘길 쉼표 목록 |
| `--claude` | `claude` | Claude Code 실행 파일. `.cmd`·`.bat`는 cmd.exe를 거치므로 거부한다 |
| `--run-timeout` | `15m` | 이 시간이 지나면 실행기를 끝낸다. `0`이면 제한 없음 |

종료 코드: 0 정상, 1 실패(또는 `--once`에서 `submitted`가 아님), 2 플래그 오류, 130 Ctrl+C.

## 빌드와 시험

Go 1.27이 필요하다. `apps/agent`에서 실행한다.

```sh
go vet ./...
go test ./...
go build -o bin/zm-baton-agent.exe ./cmd/zm-baton-agent
```

- 시험은 진짜 Claude Code와 서버를 띄우지 않는다. 실행기 자리에는 시험 바이너리 자신을 「가짜 Claude」로 띄운다. 서버 자리에는 `httptest` 서버를 쓴다.
- `KillRun` 시험이 끝내는 프로세스는 시험이 직접 띄운 도우미 프로세스뿐이다. 도우미는 무작위 표식을 명령줄에 단다.
- `bin/`은 Git에 넣지 않는다.

## 이 시험에서 하지 않는 것

다음은 1단계 뒤 작업이다: 임대 갱신, WebSocket 깨우기, 보내지 못한 사건의 디스크 보관, `request_id`, 끊긴 실행의 재개, 질문 경로, 실행기 설정 격리(`CLAUDE_CONFIG_DIR`). 도구 결과 본문은 4,000자로 자르기만 하고 비밀값을 가리지 않는다.
