# 백업·복원·업데이트

이 PC에서 Docker Compose로 띄운 zm-baton의 데이터베이스를 매일 백업하고, 손으로 백업·복원하고, 새 코드로 안전하게 업데이트하는 방법이다. 근거는 기능 명세 FN-1403(매일 백업 기본값, 키 복구 자료 분리, 호환 확인 뒤 갱신)과 기준 G30.1·G30.2다(설계 허브 zm-docs).

- **대상**: [sessions.md](sessions.md)대로 `docker compose -f deploy/compose/compose.yaml --profile app up -d --build`로 서버를 띄워 쓰는 소유자 PC 한 대.
- **소유자**: 저장소 소유자. 마지막 확인 2026-10-14. 이 문서의 명령은 `work/ops-backup` 브랜치(바탕 `main` 4671e5e)에서, 이름·포트·볼륨이 다른 임시 Compose 프로젝트로 모두 돌려 확인했다.
- **함께 읽을 것**: 감사 기록·보존 규칙·내보내기는 [audit-retention-export.md](audit-retention-export.md)에 있다.

## 무엇이 어디에 있는가

| 무엇 | 어디 |
|---|---|
| 데이터베이스 | Docker 볼륨 `zm-baton-dev_zm-baton-pg` |
| 백업 폴더 | `.env`의 `ZM_BATON_BACKUP_DIR`. 없으면 이 저장소 폴더 **옆**의 `zm-baton-backups` 폴더다(예: 저장소가 `C:/work/zm-baton`이면 `C:/work/zm-baton-backups`). Git 밖이다 |
| 매일 백업 | Compose 서비스 `backup`. `--profile app`으로 서버를 띄우면 함께 뜬다 |
| 손으로 백업·복원·업데이트 | `deploy/compose/backup.ps1`, `restore.ps1`, `update.ps1` (Linux·macOS는 같은 이름의 `.sh`) |
| 백업 파일 | `zm-baton-<종류>-<UTC 시각>.dump`와 그 옆의 `.sha256`. 종류는 `daily`(매일), `manual`(손으로), `before-restore`(복원 직전), `before-update`(업데이트 직전)다 |

백업 파일은 PostgreSQL `pg_dump`의 사용자 지정 형식이다. 데이터베이스의 모든 표가 들어 있다. 세션 토큰 해시, 기기 이름, 작업과 세션의 글도 들어 있으므로 **백업 폴더를 공개된 곳이나 다른 사람과 함께 쓰는 곳에 두지 않는다.** 백업은 암호화하지 않는다.

## 데이터베이스 볼륨은 업데이트로 지워지지 않는다

1. 자료는 볼륨 `zm-baton-dev_zm-baton-pg`에 있다. 이 이름은 `compose.yaml`의 프로젝트 이름 `zm-baton-dev`와 볼륨 이름 `zm-baton-pg`로 정해진다. 두 이름을 바꾸지 않는다(`compose.yaml` 첫 주석).
2. `up -d --build`(다시 빌드), `restart`, `stop`, `down`은 볼륨을 남긴다.
3. 볼륨을 지우는 것은 `down -v`와 `docker volume rm`뿐이다. 이 문서의 스크립트는 둘 다 쓰지 않는다.
4. `down -v`를 써야 하는 안내(예: [sessions.md](sessions.md)의 「실패하면」 2번)를 따르기 전에는 먼저 손으로 백업한다.

## 전제조건

- Docker Desktop이 실행 중이다.
- `deploy/compose/.env`에 `ZM_BATON_DB_PASSWORD`가 있다([sessions.md](sessions.md) 1단계).
- PowerShell 7(`pwsh`)이 있다. 업데이트에는 Git도 필요하다.
- 명령은 PowerShell에서 실행하고, `$repo`에 이 저장소의 경로를 담는다. 예: `$repo = 'C:/work/zm-baton'`. 스크립트는 어느 폴더에서 실행해도 된다.

## 매일 백업

### 켜기

기본으로 켜져 있다. 서버를 띄우는 평소 명령이 `backup` 서비스도 띄운다.

```powershell
docker compose -f "$repo/deploy/compose/compose.yaml" --profile app up -d --build
```

`--profile app`에 함께 넣은 이유: FN-1403이 「매일 백업 기본값」을 정했고, 소유자의 실제 자료가 이 데이터베이스에 있다. 소유자가 이미 쓰는 명령 그대로 켜져야 따로 챙기지 않아도 백업이 쌓인다. 끄는 것은 `.env`의 한 줄이다. 데이터베이스만 띄우는 설치·호환 시험에서는 `--profile backup`을 붙이면 데이터베이스와 함께 뜬다.

### 동작

1. `backup` 컨테이너가 10분마다 백업 폴더를 본다.
2. 가장 새로운 `daily` 백업이 24시간보다 오래됐거나 없으면 바로 하나 뜬다. PC가 밤에 꺼져 있어도 켜진 뒤 10분 안에 뜬다.
3. 덤프는 숨김 파일(`.….partial`)로 쓰고, `pg_restore`로 다시 읽혀야 `.sha256`과 함께 제 이름을 단다. 반쯤 쓴 파일이 백업 이름을 다는 일은 없다.
4. 그다음 `daily` 백업을 새것부터 14개만 남기고 지운다. **지우는 것은 `zm-baton-daily-*.dump`와 그 `.sha256`뿐이다.** 손으로 뜬 백업과 복원·업데이트 직전 백업은 지우지 않는다. 필요 없으면 직접 지운다.
5. 실패하면 로그에 남기고 10분 뒤 다시 한다.

### 확인

```powershell
docker compose -f "$repo/deploy/compose/compose.yaml" logs --tail 20 backup
Get-ChildItem ../zm-baton-backups   # 저장소 폴더에서. ZM_BATON_BACKUP_DIR을 바꿨으면 그 폴더
```

로그에 `daily backup is on`과 `took zm-baton-daily-….dump`가 보이고, 폴더에 그 파일과 `.sha256`이 있으면 된다.

### 끄기와 바꾸기

`deploy/compose/.env`에 아래 줄을 더하고 `docker compose -f "$repo/deploy/compose/compose.yaml" --profile app up -d`를 다시 실행한다. 바뀐 값으로 `backup` 컨테이너만 다시 만들어진다.

| 줄 | 뜻 | 없을 때 |
|---|---|---|
| `ZM_BATON_BACKUP=off` | 매일 백업을 끈다. 컨테이너는 떠 있되 아무것도 하지 않는다 | `on` |
| `ZM_BATON_BACKUP_DIR=D:/zm-baton-backups` | 백업 폴더. 절대 경로로 쓴다. 다른 디스크를 권한다 | 저장소 옆 `zm-baton-backups` |
| `ZM_BATON_BACKUP_KEEP=30` | 남길 `daily` 백업 수. `0`이면 하나도 지우지 않는다 | `14` |
| `ZM_BATON_BACKUP_EVERY_HOURS=12` | 백업 간격(시간) | `24` |
| `ZM_BATON_BACKUP_USER=1000:1000` | Linux에서 백업 파일을 내 사용자로 쓴다 | `0:0`(root) |

잠깐만 멈추려면 `docker compose -f "$repo/deploy/compose/compose.yaml" stop backup`, 다시 켜려면 `start backup`이다.

## 손으로 백업하기

```powershell
pwsh "$repo/deploy/compose/backup.ps1"
```

1. 데이터베이스 컨테이너가 떠 있는지 본다.
2. `backup` 서비스의 일회용 컨테이너로 `pg_dump`를 돌려 `zm-baton-manual-<UTC 시각>.dump`를 쓴다. 매일 백업과 같은 방식이다.
3. 마지막 줄 `== backup written: <전체 경로>`가 만든 파일이다.

데이터베이스를 읽기만 한다. 서버는 그대로 돈다.

## 복원하기

복원은 지금 데이터베이스를 백업 시점의 내용으로 **바꾼다.** 백업 뒤에 쌓인 작업·세션·감사 기록은 사라진다. 그래서 스크립트가 복원 직전에 지금 상태를 한 번 더 백업한다.

### 단계

1. 서버와 매일 백업을 멈춘다. 데이터베이스는 띄워 둔다.

   ```powershell
   docker compose -f "$repo/deploy/compose/compose.yaml" stop server backup
   ```

2. 복원할 파일과, 덮어쓸 Compose 프로젝트 이름을 함께 준다. 프로젝트 이름은 확인용이다. 다르면 아무것도 하지 않는다.

   ```powershell
   pwsh "$repo/deploy/compose/restore.ps1" -File ../zm-baton-backups/zm-baton-daily-20261014T030000Z.dump -ConfirmProject zm-baton-dev
   ```

3. 서버와 매일 백업을 다시 띄운다.

   ```powershell
   docker compose -f "$repo/deploy/compose/compose.yaml" --profile app up -d
   ```

### 스크립트가 하는 일

1. `-ConfirmProject`가 이 프로젝트 이름(`zm-baton-dev`)과 같은지 본다.
2. 데이터베이스가 떠 있고 서버와 `backup`이 멈췄는지 본다. 하나라도 어긋나면 멈추고 할 일을 알린다.
3. 파일이 `.sha256`과 맞는지, `pg_restore`가 읽을 수 있는지 본다.
4. 지금 데이터베이스를 `zm-baton-before-restore-<UTC 시각>.dump`로 백업한다.
5. 파일을 **새 데이터베이스**(`zm_baton_restoring`)에 먼저 복원한다. 그것이 끝까지 성공했을 때만 옛 데이터베이스를 지우고 새것의 이름을 `zm_baton`으로 바꾼다. 복원이 중간에 실패하면 지금 데이터베이스는 그대로다.
6. 감사 기록에 `backup.restored`(파일 이름과 체크섬)를 남긴다.
7. 복원된 데이터베이스의 마이그레이션 판을 이 저장소의 판과 비교해 알린다. 저장소에 더 새 마이그레이션이 있으면 서버가 뜰 때 적용한다. 백업에 저장소가 모르는 마이그레이션이 있으면 경고한다.

### 완료 확인

- 마지막에 `== restored.`와 마이그레이션 판이 나온다.
- 서버를 띄운 뒤 `http://127.0.0.1:18081/`의 작업·세션이 백업 시점의 모습이다.
- 되돌리려면 출력의 마지막 줄 `restore.ps1 -File '<before-restore 파일>' -ConfirmProject zm-baton-dev`를 같은 순서(서버 멈춤 → 복원 → 띄움)로 실행한다.

### 주의

- 복원한 데이터베이스는 백업 시점의 보존 상태다. 보존 기간을 설정했다면, 그 사이에 지운 기록이 돌아왔다가, 보존 점검이 하루 한 번 돌 때(늦어도 하루 안) 다시 지워진다([audit-retention-export.md](audit-retention-export.md)).
- 백업에는 PostgreSQL 사용자와 비밀번호가 들어 있지 않다. 새 PC에서 새 `.env` 비밀번호로 데이터베이스를 처음 띄운 뒤에도 같은 절차로 복원된다.

## 업데이트하기

새 코드로 서버를 다시 빌드해 올린다. 백업을 먼저 뜨고, 실패하면 멈추고 되돌리는 방법을 알린다. **스스로 되돌리지는 않는다.**

### 단계

```powershell
cd $repo
git pull --ff-only          # 코드를 받는 것은 스크립트가 하지 않는다
pwsh deploy/compose/update.ps1
```

### 스크립트가 하는 일

1. 빌드할 커밋을 보이고(고치지 않은 파일이 있으면 `-dirty`), 서버가 적용할 마이그레이션, 즉 저장소에는 있고 데이터베이스는 아직 적용하지 않은 것을 보인다. 이것이 FN-1403의 「호환 확인」의 시작판이다.
2. 데이터베이스에 저장소가 모르는 마이그레이션이 있으면(저장소가 더 옛 판이면) 멈춘다.
3. 끝나지 않은 실행 시도가 있으면 멈춘다. 끝난 뒤 다시 하거나 `-IgnoreLiveRuns`를 붙인다(G30.2).
4. `zm-baton-before-update-<UTC 시각>.dump`를 뜬다. 실패하면 멈춘다.
5. 지금 도는 서버 이미지에 `zm-baton-dev-server:before-update-<UTC 시각>` 이름을 붙여 남긴다. 되돌릴 때 쓴다.
6. `--profile app up -d --build`로 다시 빌드해 띄우고, 매일 백업을 재시작해 새 스크립트로 돌게 한다. 데이터베이스 볼륨은 그대로다.
7. `/actuator/health`가 `UP`이 될 때까지 기다리고(기본 300초, `-TimeoutSeconds`로 바꾼다), 데이터베이스에 저장소의 마이그레이션이 모두 적용됐는지 본다. 서버는 마이그레이션을 마친 뒤에 응답한다.

### 완료 확인

- 마지막 줄이 `== updated: commit <커밋> is running and the database has every migration (<판>)`이다.
- 내보내기(`zm-baton export`)의 `서버 판` 괄호 안에 그 커밋이 나온다.

### 실패했을 때

스크립트가 `update stopped:` 다음에 이유와 할 일을 출력한다. 순서는 이렇다.

1. 로그를 본다: `docker compose -f "$repo/deploy/compose/compose.yaml" logs --tail 200 server`
2. 앞 판으로 되돌리려면 출력된 명령을 그대로 실행한다.
   1. `stop server backup`으로 멈춘다.
   2. 새 마이그레이션이 적용됐을 수 있으면 `before-update` 백업을 `restore.ps1`로 복원한다. 업데이트 뒤에 쌓인 기록은 사라진다. 새 마이그레이션이 없었으면 이 단계를 건너뛴다.
   3. `docker tag zm-baton-dev-server:before-update-<시각> zm-baton-dev-server:latest`로 앞 이미지를 되살린다.
   4. `--profile app up -d --no-build`로 띄운다.

남겨 둔 `before-update` 이미지는 저절로 지워지지 않는다. 필요 없으면 `docker image rm zm-baton-dev-server:before-update-<시각>`으로 지운다.

## 실패하면

| 증상 | 원인과 대응 |
|---|---|
| `docker compose config failed` | Docker가 꺼졌거나 `.env`에 `ZM_BATON_DB_PASSWORD`가 없다 |
| `the database is not running` | `docker compose -f "$repo/deploy/compose/compose.yaml" up -d postgres` 뒤 다시 한다 |
| 로그에 `pg_dump failed` | 데이터베이스가 멈췄거나 비밀번호가 볼륨을 만들 때와 다르다. 데이터베이스를 띄우고 [sessions.md](sessions.md)의 「실패하면」 2번을 본다 |
| 복원이 `stop the server and the daily backup first`로 멈춘다 | 복원 단계 1을 먼저 한다 |
| 복원이 `does not match … .sha256`으로 멈춘다 | 백업 파일이 바뀌었거나 손상됐다. 다른 백업을 고른다 |
| 백업 폴더에 `.….partial` 파일이 남았다 | 백업 도중 컨테이너가 멈춘 흔적이다. `backup` 컨테이너가 다시 시작할 때 한 시간 지난 것을 지운다 |
| 업데이트가 `the checkout is older than the database`로 멈춘다 | 더 새 코드를 받는다. 옛 판으로 돌아가는 것이 목적이면 위 「실패했을 때」 2번의 방법을 쓴다 |
| 업데이트가 `additional privileges requested`로 빌드에 실패한다 | 스크립트 밖에서 다른 폴더를 기준으로 `docker compose`를 돌린 경우다. 스크립트는 저장소 루트로 옮겨서 돌리므로 스크립트를 쓴다 |

## 키 복구 자료 분리(G30.1)

1. 지금은 따로 둘 키가 없다. zm-baton에 금고가 아직 없고, 데이터베이스에 암호화한 칸이 없다. 데이터베이스 비밀번호(`.env`)도 복원에 필요하지 않다(위 「복원하기」의 「주의」).
2. 금고가 생기면(2단계, 설계 허브의 `vault_refs`·`vault_access`와 위협 모델 T14), 다음은 **백업 폴더와 다른 곳에** 둔다.
   1. 금고의 마스터 키·봉인 해제 키.
   2. 개인 기억·제한 문서처럼 칸 단위로 암호화한 자료의 암호화 키.
   3. 백업을 암호화하게 되면 그 키.
3. 이유: 키와 백업이 한곳에 있으면 그곳을 얻은 사람이 암호문을 바로 푼다. 반대로 키를 잃으면 백업이 있어도 암호화한 칸은 되살리지 못한다. 금고를 만드는 PR이 이 절과 백업 스크립트를 함께 고친다.

## 이 묶음이 하지 않는 것

- 백업을 암호화하거나 다른 PC·클라우드로 보내기
- 업데이트 실패 때 스스로 되돌리기
- 마이그레이션의 체크섬 비교(이미 적용한 마이그레이션 파일을 고친 경우는 서버가 뜰 때 Flyway가 거부한다)
- 웹 화면에서 백업·복원하기
