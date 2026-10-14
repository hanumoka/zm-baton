# 내 AI 세션이 보이는 화면 — 1단계 첫 묶음 쓰기

1단계 첫 묶음이다. 터미널에서 직접 연 Claude Code 세션이 서버에 등록되고, 웹 화면에서 세션마다 하는 일과 고친 파일, 같은 파일을 두 세션이 고친 충돌, 입력을 기다리는 세션이 보인다. 세션의 권한 요청과 선택형 질문은 첫 화면으로 중계되어 거기서 답할 수 있다. 근거는 협업 계약 v1의 「직접 연 AI 세션을 서버에 등록한다」(그 안의 「권한 요청과 선택형 질문을 첫 화면으로 중계한다」)·「충돌을 찾아 관계자에게 알린다」·「첫 화면」 절이다(설계 허브 zm-docs, `protocol_version` 2).

- **대상**: 소유자 PC 한 대. 서버는 이 PC의 `127.0.0.1`에서만 받는다. 사람 인증이 아직 없으므로 이 PC 밖으로 열지 않는다(1단계 종료 기준 4번).
- **소유자**: 저장소 소유자. 마지막 확인 2026-10-14.
- **전제**: 서버(`work/sessions-server`)·`zm-baton` 명령(`work/sessions-cli`)·웹(`work/sessions-web`) 세 PR이 모두 `main`에 들어간 판에서 따른다. 하나라도 빠지면 API나 명령이 없어 중간에 막힌다.

## 무엇이 어디에 뜨는가

| 무엇 | 어디 |
|---|---|
| 웹 화면 「나를 기다리는 것」·「세션 현황」·「실행 시험」 | `http://127.0.0.1:18081/` |
| 서버 API | 같은 주소의 `/api` |
| PostgreSQL | `127.0.0.1:15432` (Docker 볼륨 `zm-baton-pg`) |
| `zm-baton` 명령의 설정·세션 토큰·로그 | 사용자 설정 폴더의 `zm-baton\` (Windows는 `%AppData%\zm-baton\`) |
| Claude Code 훅 | Claude Code 플러그인 `zm-baton`. 파일은 `%AppData%\zm-baton\claude-plugin\`에 있고, 켜는 일은 Claude Code가 한다(5단계) |

## 전제조건

- Docker Desktop이 실행 중이다. JDK·Node.js·Go는 없어도 된다. 서버와 웹은 Docker 안에서 빌드하고, `zm-baton` 명령도 Docker로 만든다.
- Claude Code가 설치돼 있고 소유자가 직접 로그인해 둔 상태다. zm-baton은 로그인 정보를 읽지 않는다.
- PowerShell에서 저장소 루트를 `$repo`에 담는다. 예: `$repo = 'C:/work/zm-baton'`. 아래 명령은 단계마다 `cd`로 자리를 옮기므로, 각 단계는 그 단계의 첫 줄부터 실행한다.

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
# 세션 안의 AI가 `zm-baton session work`를 부를 수 있게 사용자 PATH에 더한다(새로 연 창부터 적용).
# 이 경로와 똑같은 항목이 없을 때만 더하고, 다른 항목은 건드리지 않는다.
$ours = "$env:LOCALAPPDATA\zm-baton\bin"
$parts = @(([Environment]::GetEnvironmentVariable('Path', 'User') -split ';') | Where-Object { $_ })
if (($parts | ForEach-Object { $_.TrimEnd('\') }) -notcontains $ours) {
  [Environment]::SetEnvironmentVariable('Path', (($parts + $ours) -join ';'), 'User')
}
cd $repo
```

훅은 이 위치의 실행 파일을 부른다. 옮기면 `hooks install`을 다시 한다. PATH는 바꾼 뒤에 새로 연 PowerShell 창과 새로 띄운 Claude Code에만 적용된다.

### 4. 이 PC를 등록한다(처음 한 번)

```powershell
& $zb login --server http://127.0.0.1:18081
& $zb status
```

`서버 응답: 정상`이 나오면 된다.

### 5. Claude Code에 훅을 붙인다

훅은 Claude Code 플러그인으로 붙는다. zm-baton은 Claude Code 설정 파일을 고치지 않고, 설정은 Claude Code의 명령이 고친다. 먼저 저장소 하나에만 붙여 써 보기를 권한다. 그 저장소에서 연 세션만 등록된다.

1. 플러그인 파일을 만든다. 붙일 명령이 함께 출력된다.

   ```powershell
   & $zb hooks install --project C:/work/zm-docs
   ```

2. 출력된 Claude Code 명령을 그대로 실행한다. 아래와 같은 모양이다.

   ```powershell
   claude plugin marketplace add "$env:AppData\zm-baton\claude-plugin"   # 처음 한 번
   cd C:/work/zm-docs
   claude plugin install zm-baton@zm-baton-local --scope local
   cd $repo
   ```

`--scope local`은 그 저장소의 `.claude\settings.local.json`에, 모든 세션에 붙이는 `--scope user`(`--project` 없이 `hooks install`)는 사용자 `~\.claude\settings.json`에 Claude Code가 기록한다. `claude plugin marketplace add`는 사용자 설정에 이 로컬 마켓플레이스를 기록한다.

### 6. 써 본다

1. 훅을 붙인 저장소에서 Claude Code를 새로 연다. 이미 열려 있던 세션에는 훅이 적용되지 않는다.
2. 웹의 「세션 현황」(`#sessions`)에 그 세션이 「살아 있음」 묶음으로 나타난다. 행을 고르면 오른쪽 상세에 세션 번호 전체, 저장소와 브랜치, 등록·마지막 신호 시각, 최근 고친 파일이 보인다. 맨 위 입력줄에 세션 번호 일부나 브랜치, 하는 일의 낱말을 쓰면 찾고, 「저장소」 칩으로 저장소별로 거른다. 「끝난 세션 보기」를 켜면 끝났거나 등록을 끈 세션도 「그 밖」 묶음에 보인다. 세션의 AI는 시작할 때 현재 상태 카드(열린 작업, 나를 기다리는 것의 개수, 같은 저장소의 다른 세션이 하는 일)를 받는다.
3. 세션 안에서 AI나 사람이 `zm-baton session work "<무엇을, 어디서>"`를 실행하면 「지금 하는 일」이 올라간다. 300자 이하다. 공유하지 않으려면 `zm-baton session work --private "<무엇을, 어디서>"`처럼 옵션을 요약 **앞에** 쓴다(요약 뒤에 쓰면 요약의 일부가 된다). 그러면 그 세션의 이후 요약도 다른 세션의 상태 카드와 세션 현황에 「공유 안 함」으로만 보인다. 다시 공유하려면 `--share`를 붙인다. 세션 밖의 PowerShell에서 부를 때는 `--session <세션 번호>`를 함께 준다.
4. 같은 저장소에서 세션을 하나 더 열고, 두 세션이 같은 파일을 고치게 해 본다. 「나를 기다리는 것」의 「내 작업과 겹친 충돌」 묶음에 그 파일이 뜨고 브라우저 알림이 온다. 행을 고르면 오른쪽 상세에 겹친 파일과 두 세션이 보인다(390px에서는 시트로 열린다). 상세의 「확인」·「충돌 아님」(이유를 적어야 보낸다)·「해결」로 판정하면 그 항목은 목록에서 빠지고 다음 항목이 열린다. 고른 항목은 주소(`#inbox?item=…`)에 남아 새로 고쳐도 그대로다.
5. 세션이 다음 지시나 입력을 기다리면 「멈춘 AI의 질문」 묶음에 뜬다. 여기서는 답하지 않는다. 그 터미널에서 이어서 입력하면 저절로 닫힌다. 위험 목록·표본 검토(4·5번 묶음)는 아직 서버가 만들지 않아, 그 묶음 머리에 그렇다고 나온다. 알림 문구는 서버로 보내지 않는다. 예외는 「어떤 도구의 권한을 기다리는가」를 담은 `Claude needs your permission to use <도구>` 한 가지다.
6. 세션이 도구 권한을 물으면(권한 모드가 `default`일 때 잘 보인다. `auto`면 권한 질문이 거의 없다) 「권한 요청」 묶음에 바로 뜨고 브라우저 알림이 온다. 상세에 도구 이름, 명령 전문이나 파일 경로, 실행기가 붙인 설명, 작업 위치, 남은 시간(시작값 60초)이 보인다. 「허용」은 이번 한 번만 허용한다. 「거절」은 이유를 붙여 보낼 수 있고 그 이유가 AI에게 전해진다. 「터미널에서 답하기」는 중계를 멈춘다. 터미널에도 같은 질문이 떠 있어 먼저 답한 쪽이 쓰인다. 답을 누르면 「전달하는 중」을 거쳐 「전달됨: 허용」처럼 바뀐다. 터미널에서 먼저 답했으면 첫 화면의 답은 쓰이지 않았을 수 있어 화면이 그렇다고 함께 적는다. 기한이 지나면 「이미 터미널로 넘어감」, 누른 답을 세션이 가져가기 전에 기한이 지나거나 세션이 끝나면 「전달 안 됨」이 된다. 끝난 중계는 10분 동안 「방금 끝난 중계」 줄 아래에 남는다. 명령에 비밀값으로 보이는 것이 있으면 내용을 서버로 보내지 않고 「비밀값이 섞여 터미널에서 확인」만 남는다.
7. 세션이 선택형 질문(`AskUserQuestion`)을 내면, 그때 첫 화면(「나를 기다리는 것」)이 브라우저에 보이고 있을 때만 「멈춘 AI의 질문」 묶음으로 중계된다. 중계하는 동안 터미널에는 질문 창이 뜨지 않으므로, 기한 안에 선택지를 골라 「답 보내기」를 누르거나 「터미널로 넘기기」를 누른다. 첫 화면이 가려져 있었으면 중계하지 않고 터미널에 질문이 뜨며, 첫 화면에는 어느 세션이 무엇을 묻는지만 남는다.
8. 사이드바 아래(좁은 화면은 「더 보기」)의 「집중 모드 켜기」를 누르면, 켜진 동안 내 작업과 겹친 충돌만 바로 알리고 나머지 바로 알릴 것은 모아 두었다가 끌 때 한 번에 알린다. 새 권한 요청과 선택형 질문은 첫 화면으로 오지 않고 터미널에서 묻는다. 이미 기다리던 중계는 기한까지 그대로다.

## 완료 확인

- 「세션 현황」에 지금 연 세션이 「살아 있음」 묶음에 보이고, 겹친 파일이 있으면 행에 겹침 표시가 붙으며 상세의 「나를 기다리는 것에서 판정하기」가 그 충돌 항목을 연다.
- 두 세션이 같은 파일을 고치면 「내 작업과 겹친 충돌」 묶음에 항목이 하나 생기고, 같은 일이 반복돼도 항목은 하나로 남고 반복 수만 오른다.
- `claude --permission-mode default`로 연 세션에서 권한 질문이 뜨면 「권한 요청」 묶음에 그 명령 전문과 남은 시간이 보이고, 「허용」을 누르면 터미널의 질문이 사라지며 명령이 실행되고 항목이 「전달됨: 허용」이 된다. 아무도 답하지 않으면 60초 뒤 「이미 터미널로 넘어감」이 되고 터미널의 질문은 그대로 남는다.

## 실패하면

1. **세션이 나타나지 않는다**: `& $zb status`로 서버 응답을 본다. 훅의 실패는 세션에 보이지 않고 `%AppData%\zm-baton\hook.log`에 남는다. 훅을 붙인 뒤 새로 연 세션인지, `claude plugin list`에 `zm-baton@zm-baton-local`이 켜져 있는지 확인한다.
2. **서버가 데이터베이스에 붙지 못한다**: `docker compose -f "$repo/deploy/compose/compose.yaml" logs server`를 본다. `.env`의 비밀번호를 볼륨을 만든 뒤에 바꿨다면, 시험 자료를 버려도 될 때만 `docker compose -f "$repo/deploy/compose/compose.yaml" --profile app down -v`로 볼륨을 지우고 다시 띄운다. 지우기 전에 `.env`의 비밀번호를 볼륨을 만들 때의 값으로 잠시 되돌리고 [backup-restore-update.md](backup-restore-update.md)의 「손으로 백업하기」를 먼저 한다. 옛 값을 모르면 백업할 수 없다.
3. **파일을 고쳤는데 고친 파일에 안 나온다**: 셸 명령(예: `sed`, 스크립트)으로 바꾼 파일은 보고되지 않는다. 파일을 고치는 도구(`Edit`·`Write`·`MultiEdit`·`NotebookEdit`)만 보고된다.
4. **훅 때문에 세션이 느려졌다고 느낀다**: 훅마다 시간 상한이 있다(시작 4초, 끝 1초, 권한 요청·선택형 질문 중계 80초, 나머지 3초). 상한을 넘기면 훅 프로세스가 스스로 끝난다. 서버가 꺼져 있으면 훅은 연결 실패 즉시 끝난다. 권한 요청 중계는 기다리는 동안에도 터미널에서 바로 답할 수 있다. 선택형 질문 중계가 터미널을 막는다고 느끼면 첫 화면의 「터미널로 넘기기」를 누르거나 집중 모드를 켠다. 그래도 문제면 「정리」의 훅 빼기를 한다.
5. **`claude plugin marketplace add`가 이미 추가되어 있다며 실패한다**: 앞서 한 번 했다는 뜻이다. `claude plugin marketplace list`에 `zm-baton-local`이 보이면 그 줄은 건너뛰고 `claude plugin install`부터 한다.
6. **`session off`가 「서버에 알리지 못했다」로 끝났다**: 대개 이 세션의 보고는 이미 멈췄다. 서버가 응답할 때 같은 명령을 다시 실행하면 서버에도 알린다. 다시 실행하지 않아도 서버는 12시간 안에 그 등록을 끝낸다. 드물게 이 PC에 해제 표시를 저장하지 못했을 때도 같은 문구가 나와 보고가 계속될 수 있다. 그래서 같은 명령을 한 번 더 실행해 「껐다」 또는 「이미 꺼져 있다」를 확인한다(고도화 후보 H-02).
7. **첫 화면에 권한 요청이 오지 않는다**: 플러그인 훅 목록이 바뀐 판이면 아래 「이미 플러그인을 붙였다면」을 했는지 본다. 권한 모드가 `auto`이거나 허용 규칙에 든 명령은 권한 질문이 없어 중계할 것도 없다. 집중 모드가 켜져 있으면 터미널에서 묻는다. 선택형 질문은 첫 화면이 보이고 있을 때만 온다.
8. **세션이 오래 쉬고 나서 `session work`가 HTTP 401로 거절됐다**: 세션 토큰은 사건이 없으면 12시간 뒤 끝난다(시작값). 이 판부터는 그 세션의 다음 훅이나 `session work`가 다시 등록하고 한 번 더 보낸다. 세션 번호(`ses_`)는 새로 붙는다. 이전 판의 `zm-baton`이면 아래 「이미 플러그인을 붙였다면」대로 실행 파일을 바꾼다.

## 이미 플러그인을 붙였다면 — 훅 목록이 바뀐 판으로 올리기

이 판은 플러그인 훅을 다섯에서 일곱으로 늘렸다(권한 요청 `PermissionRequest`, 선택형 질문 `PreToolUse`의 `AskUserQuestion`). Claude Code는 이 로컬 마켓플레이스의 플러그인을 그 폴더에서 그대로 읽으므로, 플러그인을 다시 설치하지 않아도 된다.

1. 3단계대로 새 `zm-baton.exe`를 만들어 같은 자리에 덮어쓴다. 훅이 부르는 실행 파일이 이것이다.
2. 플러그인 파일을 다시 쓴다. 처음 붙일 때와 같은 명령이고, 출력되는 Claude Code 명령은 이미 했으면 다시 하지 않는다.

   ```powershell
   & $zb hooks install --project C:/work/zm-docs   # 처음에 --project 없이 했으면 없이
   ```

3. 새로 여는 Claude Code 세션부터 새 훅이 붙는다. 이미 열린 세션은 그 세션에서 `/reload-plugins`를 실행하면 바로 붙는다.
4. 확인: `Get-Content "$env:AppData\zm-baton\claude-plugin\zm-baton\hooks\hooks.json"`에 `PermissionRequest`와 `PreToolUse`(`"matcher": "AskUserQuestion"`)가 보이면 된다. 세션에서 권한 질문이 뜨면 첫 화면 「권한 요청」 묶음에도 뜬다.

되돌리려면 이전 판의 `zm-baton.exe`로 바꾸고 `hooks install`을 다시 실행한다. 훅이 다섯으로 돌아간다.

## 정리

```powershell
& $zb session off                                      # 지금 세션만 등록을 끈다(세션 안에서)
cd C:/work/zm-docs
claude plugin uninstall zm-baton@zm-baton-local --scope local   # 붙인 곳과 같은 범위에서 뗀다
cd $repo
docker compose -f "$repo/deploy/compose/compose.yaml" --profile app down   # 자료는 볼륨에 남는다
```

`& $zb hooks uninstall --project <폴더>`도 같은 명령을 보여 준다.

`zm-baton` 명령까지 지우려면 플러그인을 먼저 뗀다. 플러그인을 남긴 채 실행 파일을 지우면, 훅이 세션마다 없는 파일을 부르다 실패한다. 모든 곳에서 뗐으면 `claude plugin marketplace remove zm-baton-local`로 마켓플레이스도 지운다.

```powershell
# 3단계에서 더한 항목과 똑같은 것만 PATH에서 뺀다. 이름이 비슷한 다른 항목은 남는다.
$ours = "$env:LOCALAPPDATA\zm-baton\bin"
$parts = @(([Environment]::GetEnvironmentVariable('Path', 'User') -split ';') | Where-Object { $_ -and $_.TrimEnd('\') -ne $ours })
[Environment]::SetEnvironmentVariable('Path', ($parts -join ';'), 'User')
Remove-Item -Recurse "$env:LOCALAPPDATA/zm-baton/bin"   # 실행 파일
Remove-Item -Recurse "$env:AppData/zm-baton"            # 기기 등록·세션 토큰·로그(지우면 login부터 다시)
```

세션을 시작하기 전에 `ZM_BATON_OFF=1`을 두면 그 세션은 등록되지 않는다.

## 이 묶음이 하지 않는 것

- 사람 인증, 이 PC 밖에서의 접속, 휴대폰·메신저 알림. 그래서 이 PC에서 도는 다른 프로그램도 첫 화면 API로 중계에 답할 수 있다(고도화 후보 H-21)
- 묶어 알리는 때(하루 두 번)의 알림, 위험 경로 검토와 표본
- Codex 세션의 권한 요청 중계(계약 중계 16번: Codex 훅이 같은 일을 하는지 확인한 뒤 켠다)
- Codex 세션 등록(원격 MCP), 작업·결정 기록(둘째 묶음 [work-and-decisions.md](work-and-decisions.md)), zm-baton이 띄우는 실행과의 충돌 비교
- 「지금 하는 일」을 팀 자료와 뜻으로 대조해 알리기(3단계)
