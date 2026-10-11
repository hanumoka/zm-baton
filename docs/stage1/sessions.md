# 내 AI 세션이 보이는 화면 — 1단계 첫 묶음 쓰기

1단계 첫 묶음이다. 터미널에서 직접 연 Claude Code 세션이 서버에 등록되고, 웹 화면에서 세션마다 하는 일과 고친 파일, 같은 파일을 두 세션이 고친 충돌, 입력을 기다리는 세션이 보인다. 근거는 협업 계약 v1의 「직접 연 AI 세션을 서버에 등록한다」·「충돌을 찾아 관계자에게 알린다」·「첫 화면」 절이다(설계 허브 zm-docs, `protocol_version` 2).

- **대상**: 소유자 PC 한 대. 서버는 이 PC의 `127.0.0.1`에서만 받는다. 사람 인증이 아직 없으므로 이 PC 밖으로 열지 않는다(1단계 종료 기준 4번).
- **소유자**: 저장소 소유자. 마지막 확인 2026-10-11.

## 무엇이 어디에 뜨는가

| 무엇 | 어디 |
|---|---|
| 웹 화면 「나를 기다리는 것」·「세션 현황」·「실행 시험」 | `http://127.0.0.1:18081/` |
| 서버 API | 같은 주소의 `/api` |
| PostgreSQL | `127.0.0.1:15432` (Docker 볼륨 `zm-baton-pg`) |
| `zm-baton` 명령의 설정·세션 토큰·로그 | 사용자 설정 폴더의 `zm-baton\` (Windows는 `%AppData%\zm-baton\`) |
| Claude Code 훅 | 고른 설정 파일 하나: 저장소의 `.claude\settings.local.json` 또는 사용자 `~\.claude\settings.json` |

## 전제조건

- Docker Desktop이 실행 중이다. JDK·Node.js·Go는 없어도 된다. 서버와 웹은 Docker 안에서 빌드하고, `zm-baton` 명령도 Docker로 만든다.
- Claude Code가 설치돼 있고 소유자가 직접 로그인해 둔 상태다. zm-baton은 로그인 정보를 읽지 않는다.
- PowerShell에서 저장소 루트를 `$repo`에 담는다. 예: `$repo = 'C:/work/zm-baton'`.

## 단계

### 1. 데이터베이스 비밀번호를 정한다(처음 한 번)

```powershell
cd $repo/deploy/compose
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
notepad .env   # ZM_BATON_DB_PASSWORD 를 이 PC에서만 쓸 값으로 바꾼다
```

`.env`는 Git에 들어가지 않는다. PostgreSQL은 볼륨을 처음 만들 때만 비밀번호를 정하므로, 나중에 바꾸면 접속이 실패한다(「실패하면」 2번).

### 2. 서버와 웹을 띄운다

```powershell
cd $repo
docker compose -f deploy/compose/compose.yaml --profile app up -d --build
```

처음에는 서버와 웹을 빌드하느라 몇 분 걸린다. 브라우저로 `http://127.0.0.1:18081/`을 열어 「나를 기다리는 것」이 보이면 된다. 「브라우저 알림 켜기」를 누르면 같은 파일 충돌 같은 바로 알릴 것이 이 PC 알림으로 뜬다. 알림은 이 탭이 열려 있을 때만 뜬다.

### 3. `zm-baton` 명령을 만든다

```powershell
cd $repo/apps/agent
docker run --rm -v "${PWD}:/src" -w /src -e GOOS=windows -e GOARCH=amd64 golang:1.27 go build -o bin/zm-baton.exe ./cmd/zm-baton
New-Item -ItemType Directory -Force "$env:LOCALAPPDATA/zm-baton/bin" | Out-Null
Copy-Item bin/zm-baton.exe "$env:LOCALAPPDATA/zm-baton/bin/"
$zb = "$env:LOCALAPPDATA/zm-baton/bin/zm-baton.exe"
# 세션 안의 AI가 `zm-baton session work`를 부를 수 있게 사용자 PATH에 더한다(새로 연 창부터 적용)
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($userPath -notlike "*zm-baton\bin*") {
  [Environment]::SetEnvironmentVariable('Path', "$userPath;$env:LOCALAPPDATA\zm-baton\bin", 'User')
}
```

훅은 이 위치의 실행 파일을 부른다. 옮기거나 지우면 `hooks install`을 다시 한다. PATH는 바꾼 뒤에 새로 연 PowerShell 창과 새로 띄운 Claude Code에만 적용된다.

### 4. 이 PC를 등록한다(처음 한 번)

```powershell
& $zb login --server http://127.0.0.1:18081
& $zb status
```

`서버 응답: 정상`이 나오면 된다.

### 5. Claude Code에 훅을 넣는다

먼저 저장소 하나에만 넣어 써 보기를 권한다. 그 저장소에서 연 세션만 등록된다.

```powershell
& $zb hooks install --project C:/work/zm-docs     # 그 저장소의 .claude/settings.local.json 에만
# 모든 세션에 넣으려면:  & $zb hooks install      # 사용자 ~/.claude/settings.json
```

바뀌는 내용을 보여 주고 묻는다. `y`로 답하면 이전 파일을 `*.zm-baton-backup-<시각>`으로 남긴 뒤 쓴다. 다른 설정과 다른 도구의 훅은 그대로 둔다.

### 6. 써 본다

1. 훅을 넣은 저장소에서 Claude Code를 새로 연다. 이미 열려 있던 세션에는 훅이 적용되지 않는다.
2. 웹의 「세션 현황」에 그 세션이 나타난다. 세션의 AI는 시작할 때 현재 상태 카드(열린 작업, 나를 기다리는 것의 개수, 같은 저장소의 다른 세션이 하는 일)를 받는다.
3. 세션 안에서 AI나 사람이 `zm-baton session work "<무엇을, 어디서>"`를 실행하면 「지금 하는 일」이 올라간다. 300자 이하다. `--private`를 붙이면 다른 세션의 상태 카드와 세션 현황에 「공유 안 함」으로만 보인다.
4. 같은 저장소에서 세션을 하나 더 열고, 두 세션이 같은 파일을 고치게 해 본다. 「나를 기다리는 것」의 3번 묶음에 충돌이 뜨고 브라우저 알림이 온다. 「확인」·「충돌 아님(이유)」·「해결」로 처리한다.
5. 세션이 권한 확인이나 다음 지시를 기다리면 2번 묶음에 뜬다. 그 터미널에서 이어서 입력하면 저절로 닫힌다.

## 완료 확인

- 「세션 현황」에 지금 연 세션이 「살아 있음」으로 보인다.
- 두 세션이 같은 파일을 고치면 3번 묶음에 항목이 하나 생기고, 같은 일이 반복돼도 항목은 하나로 남고 반복 수만 오른다.

## 실패하면

1. **세션이 나타나지 않는다**: `& $zb status`로 서버 응답을 본다. 훅의 실패는 세션에 보이지 않고 `%AppData%\zm-baton\hook.log`에 남는다. 훅을 넣은 뒤 새로 연 세션인지 확인한다.
2. **서버가 데이터베이스에 붙지 못한다**: `docker compose -f deploy/compose/compose.yaml logs server`를 본다. `.env`의 비밀번호를 볼륨을 만든 뒤에 바꿨다면, 시험 자료를 버려도 될 때만 `docker compose -f deploy/compose/compose.yaml down -v`로 볼륨을 지우고 다시 띄운다.
3. **파일을 고쳤는데 고친 파일에 안 나온다**: 셸 명령(예: `sed`, 스크립트)으로 바꾼 파일은 보고되지 않는다. 파일을 고치는 도구(`Edit`·`Write`·`MultiEdit`·`NotebookEdit`)만 보고된다.
4. **훅 때문에 세션이 느려졌다고 느낀다**: 훅마다 시간 상한이 있다(시작 4초, 끝 1초, 나머지 3초). 서버가 꺼져 있으면 훅은 연결 실패 즉시 끝난다. 그래도 문제면 「정리」의 훅 빼기를 한다.

## 정리

```powershell
& $zb session off                                      # 지금 세션만 등록을 끈다(세션 안에서)
& $zb hooks uninstall --project C:/work/zm-docs        # 넣은 곳과 같은 대상에서 뺀다
docker compose -f deploy/compose/compose.yaml --profile app down   # 자료는 볼륨에 남는다
```

세션을 시작하기 전에 `ZM_BATON_OFF=1`을 두면 그 세션은 등록되지 않는다.

## 이 묶음이 하지 않는 것

- 사람 인증, 이 PC 밖에서의 접속, 휴대폰·메신저 알림, 집중 모드
- 권한 요청과 선택형 질문을 첫 화면에서 답하기(다음 묶음), 위험 경로 검토와 표본
- Codex 세션 등록(원격 MCP), 작업·결정 기록 운영, zm-baton이 띄우는 실행과의 충돌 비교
- 「지금 하는 일」을 팀 자료와 뜻으로 대조해 알리기(3단계)
