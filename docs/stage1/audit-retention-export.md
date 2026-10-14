# 감사 기록·보존 규칙·내보내기

서버가 누가 무엇을 바꿨는지 남기고(감사 기록), 자료 종류별로 얼마나 둘지 정하고(보존 규칙), 모든 기록을 출처와 함께 파일로 내려받는(내보내기) 방법이다. 근거는 기능 명세 FN-1404(감사 기록, 자료별 보존 규칙, 출처·권한을 담은 내보내기)와 기준 G13.1~G13.3, 데이터 모델의 `audit_log`다(설계 허브 zm-docs).

- **대상**: [sessions.md](sessions.md)대로 서버를 띄워 쓰는 소유자 PC 한 대. 서버는 이 PC의 `127.0.0.1`에서만 받는다.
- **소유자**: 저장소 소유자. 마지막 확인 2026-10-14. 이 문서가 적은 동작은 `work/ops-backup` 브랜치(바탕 `main` 4671e5e)의 서버 시험과 임시 Compose 프로젝트로 확인했다.
- **웹 화면은 아직 없다.** 감사 기록 화면과 설정 화면은 다음 묶음에서 만든다. 지금은 API와 `zm-baton` 명령으로 쓴다.

## 감사 기록

### 무엇을 남기나

서버의 상태를 바꾸는 요청마다 한 줄씩 `audit_log` 표에 남는다. 한 줄은 행위자 종류(`person` 사람, `session` 직접 연 AI 세션, `system` 서버 자신)와 행위자, 동작, 대상, 상세(`details`), 시각이다. 세션이 한 일은 행위자를 그 세션으로, 책임지는 사람을 `on_behalf_of_user_id`로 따로 남긴다(협업 계약 「등록과 세션 토큰」 4).

| 무엇을 했나 | 동작 | 대상 |
|---|---|---|
| 기기 등록(`zm-baton login`) | `device.registered` | `dev` |
| 세션 등록·재등록 | `session.registered`, `session.reregistered` | `ses` |
| 세션이 「지금 하는 일」 공유를 켜거나 끔 | `session.sharing_changed` | `ses` |
| 세션 끝남·등록 해제 | `session.ended`, `session.deregistered` | `ses` |
| 토큰이 만료돼 서버가 등록을 끝냄 | `session.expired`(행위자 `system`) | `ses` |
| 두 세션이 같은 파일을 고쳐 충돌 알림이 생김·해결한 충돌이 다시 생김 | `notice.raised`, `notice.reopened`(행위자는 그 보고를 보낸 세션) | `ntc` |
| 충돌 판정 | `notice.acknowledged`, `notice.not_a_conflict`, `notice.resolved` | `ntc` |
| 작업 만들기·고치기·단계 넘기기·대기 사유 붙이기·떼기 | `work_item.created`, `.updated`, `.stage_changed`, `.wait_added`, `.wait_removed` | `wrk` |
| 검토 단계 설정 | `workflow.skipped_stages` | `org` |
| zm-docs 가져오기 | `import.zm_docs.applied`(묶음), 기록마다 `work_item.imported`·`.synced`, `project.imported`·`.synced`, `decision.imported`·`.synced` | `wrk`, `prj`, `dcs` |
| 기기에 저장소 등록·삭제(`zm-baton repo add`·`remove`) | `device.repository_added`, `device.repository_removed` | `dev` |
| 작업을 AI에게 맡기기·거두기 | `work_item.delegated`, `work_item.delegation_withdrawn` | `wrk` |
| 실행 시험으로 실행 시도 만들기 | `run.requested` | `run` |
| 연결 프로그램이 실행 시도를 가져감(청구) | `run.claimed`(행위자 `system`, 번호는 그 기기) | `run` |
| 실행 시도가 끝남 | `run.ended`(행위자 `system`, 번호는 그 실행 시도. 끝난 이유와 「결과 확인 필요」를 달았는지) | `run` |
| 맡긴 실행이 작업 단계를 옮김 | `work_item.stage_changed`(행위자 `system`, 번호는 그 실행 시도) | `wrk` |
| 사람만 할 수 있는 요청을 세션 토큰으로 시도 | `person_action.refused` | `api` |
| 보존 규칙으로 지움 | `retention.purged`(행위자 `system`·`retention`) | 없음 |
| 내보내기 | `export.downloaded` | 없음 |
| 복원 스크립트로 복원 | `backup.restored`(행위자 `system`·`restore`) | 없음 |

작업 상세 화면의 「이력」도 이 표에서 읽는다.

### 남기지 않는 것

1. **읽기.** 목록·상세·감사 기록을 읽는 요청은 남기지 않는다. 예외는 내보내기다. 모든 기록이 서버 밖으로 나가기 때문이다.
2. **보고 자체.** 세션의 「지금 하는 일」 요약, 파일 수정 보고, 기다림 보고, 실행 시도의 사건은 각자의 표(`session_events`, `run_events`)에 원문으로 남는다. 감사 기록에는 그 보고가 만든 판단(공유 켜고 끔, 충돌 알림 생김)만 남긴다. 같은 충돌이 반복되면 알림의 반복 수만 오르고 감사 기록은 늘지 않는다.
3. **비밀값.** 상세에는 번호·개수·짧은 이유만 넣는다. 세션 토큰과 그 해시는 넣지 않는다(시험으로 확인한다).
4. **실행 시도가 시작됨.** 상태가 `running`으로 바뀌는 것은 실행 사건(`run_events`)에 남고, 맡긴 실행이면 작업의 단계 이동이 감사 기록에 남는다.

### 읽기

```powershell
# 한 작업의 이력, 새것부터
Invoke-RestMethod "http://127.0.0.1:18081/api/audit?target_kind=wrk&target_id=<작업 번호>"
# 충돌 알림에 관한 모든 동작(점으로 끝나면 그것으로 시작하는 동작)
Invoke-RestMethod "http://127.0.0.1:18081/api/audit?action=notice."
# 서버가 스스로 한 일 50건
Invoke-RestMethod "http://127.0.0.1:18081/api/audit?actor_kind=system&limit=50"
```

| 칸 | 뜻 |
|---|---|
| `target_kind`, `target_id` | 대상 종류(`wrk`·`ses`·`ntc`·`dev`·`run`·`prj`·`dcs`·`org`·`api`)와 번호 |
| `actor_kind`, `actor_id` | 행위자 종류(`person`·`session`·`system`)와 번호 |
| `action` | 동작. `.`으로 끝나면 그것으로 시작하는 동작 전부 |
| `before` | 이 줄보다 오래된 것만. 앞 쪽의 마지막 줄 번호(`aud_…`)를 주면 다음 쪽이 나온다. 시각(`2026-10-14T09:00:00+09:00`)도 된다 |
| `limit` | 한 번에 받을 수. 기본 100, 최대 500 |

새것부터 나온다. 같은 요청 안에서 남은 줄은 시각이 같으므로 번호로 순서를 정한다. 세션 토큰으로 부르면 거절된다(사람이 하는 일이다).

## 보존 규칙

### 기본값은 아무것도 지우지 않는 것이다

조직이 무엇을 얼마나 둬야 하는지 모르는 채로 보존 기간을 정하지 않는다(G13.1). 그래서 모든 종류가 기본으로 **영원히 남는다.** 무엇을 언제 지울지는 소유자가 정한다.

| 종류 | 설정(`.env`) | 기간이 지나면 지우는 것 | 기간과 상관없이 남는 것 |
|---|---|---|---|
| 감사 기록 | `ZM_BATON_RETENTION_AUDIT_LOG_DAYS` | 그보다 오래된 감사 기록 | — |
| 끝난 세션과 그 사건 | `ZM_BATON_RETENTION_ENDED_SESSIONS_DAYS` | 끝나거나 등록을 끄거나 토큰이 만료된 지 그만큼 지난 세션과 그 세션의 보고 | 살아 있는 세션과 그 보고(충돌 판정이 읽는다) |
| 닫힌 첫 화면 항목 | `ZM_BATON_RETENTION_CLOSED_INBOX_ITEMS_DAYS` | 닫힌 지 그만큼 지난 「나를 기다리는 것」 항목 | 열린 항목 |
| 닫힌 충돌 알림 | `ZM_BATON_RETENTION_CLOSED_NOTICES_DAYS` | 「해결」·「충돌 아님」으로 판정하고, 판정과 마지막 반복이 모두 그만큼 지난 알림 | 열린 알림, 「확인」한 알림, 계속 반복되는 알림 |
| 끝난 실행 시도의 사건 | `ZM_BATON_RETENTION_ENDED_RUN_EVENTS_DAYS` | 끝난 지 그만큼 지난 실행 시도의 사건 | 실행 시도 자체 |

작업, 결정, 프로젝트, 기기, 실행 시도는 보존 규칙으로 지우지 않는다.

### 기간을 정하는 방법

1. `deploy/compose/.env`에 줄을 더한다. 값은 1 이상의 일수다. 비우거나 줄이 없으면 영원히 남는다.

   ```text
   ZM_BATON_RETENTION_CLOSED_INBOX_ITEMS_DAYS=90
   ```

2. `docker compose -f "$repo/deploy/compose/compose.yaml" --profile app up -d`로 서버를 다시 만든다. 0이나 음수면 서버가 뜨지 않고 로그에 그 설정 이름(`ZM_BATON_RETENTION_…`)이 나온다. 숫자가 아니면 `zm-baton.retention.…`을 읽지 못했다는 로그와 함께 뜨지 않는다.
3. 확인한다. `days`가 정한 값이면 된다.

   ```powershell
   Invoke-RestMethod http://127.0.0.1:18081/api/settings/retention
   ```

### 동작

1. 서버가 뜬 지 5분 뒤부터 한 시간마다 점검한다.
2. 기간을 정한 종류가 하나라도 있고 마지막으로 지운 지 24시간이 지났으면, 종류마다 기간보다 오래된 것을 지운다. PC가 밤에 꺼져 있어도 켜진 뒤의 점검에서 돈다.
3. 지운 뒤 감사 기록에 `retention.purged`를 남긴다. 상세에 종류별 기간(`days`)과 표별로 지운 수(`deleted`)가 있다. `GET /api/settings/retention`의 `last_run`도 같은 것을 보인다.
4. 기간을 정한 종류가 없으면 점검은 아무것도 지우지 않고 아무것도 남기지 않는다(시험으로 확인한다).

### 정하기 전에 알 것

- 감사 기록을 지우면 그 작업의 「이력」도 함께 사라진다. 이력은 감사 기록에서 읽기 때문이다.
- 「충돌 아님」 알림을 지운 뒤 같은 충돌이 다시 생기면 새 알림으로 뜬다. 판정을 서버가 잊었기 때문이다. 계속 반복되는 알림은 지우지 않으므로, 이 일은 한동안 조용하던 충돌에서만 일어난다.
- 보존 규칙은 데이터베이스에만 적용된다. 백업 파일 안의 기록은 그 백업이 지워질 때 사라진다. 매일 백업은 기본 14개를 남기므로, 지운 기록이 백업에서도 사라지기까지 최대 14일이 더 걸린다. 손으로 뜬 백업은 직접 지울 때까지 남는다(G13.2의 「온라인 삭제와 백업 만료를 구분한다」).
- 옛 백업으로 복원하면 지웠던 기록이 돌아온다. 규칙이 그대로면 다음 점검(늦어도 하루 안)에서 다시 지운다(G13.2의 「복원할 때 삭제 기록을 다시 적용한다」를 규칙으로 대신한다).

## 내보내기

### 내려받기

```powershell
zm-baton export --out C:/work/zm-baton-export-20261014.json
```

1. 파일을 새로 만든다. 같은 이름의 파일이 이미 있으면 덮어쓰지 않고 멈춘다.
2. 서버의 내보내기 묶음을 받아 그대로 쓴다. 중간에 실패하면 만든 파일을 지운다.
3. 내보낸 시각, 서버 판과 커밋, 데이터베이스 마이그레이션 판, 종류별 개수를 출력한다.

서버 주소는 `zm-baton login`이 저장한 주소를 쓴다. 등록하지 않은 PC에서는 `--server http://127.0.0.1:18081`을 준다. 브라우저로 `http://127.0.0.1:18081/api/export`를 열어도 같은 파일이 내려받아진다.

### 묶음에 든 것

JSON 하나다. 형식 이름은 `zm-baton.export`, 판은 `format_version` 1이다. 이 형식은 밖에 내놓는 약속이므로 바꿀 때는 칸을 지우지 않고 더한다. 읽는 쪽은 모르는 칸을 무시한다.

| 칸 | 내용 |
|---|---|
| `provenance.exported_at`, `exported_by` | 내보낸 시각(UTC)과 사람 |
| `provenance.server` | 서버 이름, 판(`0.0.1-SNAPSHOT` 같은 jar 판), 빌드한 커밋. 커밋은 `update.ps1`로 빌드했을 때 채워지고, 그 밖에는 `unknown`이다 |
| `provenance.database` | 마이그레이션 판과 적용한 마이그레이션 목록 |
| `provenance.access` | 권한 정보. 내보낸 사람의 조직과 그 조직의 사람들. 내보내기는 운영자 역할만 한다. 매니저 기록의 개인 메모는 담지 않고(`not_exported`), 다른 사람의 개인 범위 작업·결정·문서와 공유하지 않은 「지금 하는 일」도 담지 않는다([org-and-permissions.md](org-and-permissions.md)) |
| `provenance.withheld_fields`, `not_exported` | 일부러 뺀 칸(`agent_sessions.token_hash`)과 뺀 표 |
| `collections.<이름>` | 표마다 `table`, `count`, `records`. 프로젝트, 작업, 결정, 결정과 프로젝트의 연결, 기기, 기기 저장소, 세션, 세션 사건, 첫 화면 항목, 충돌 알림, 위임, 실행 시도(실행 지시와 작업 트리 포함), 실행 사건, 감사 기록, 조직 설정. 기기 저장소의 원격 주소는 서버가 받을 때 이미 사용자 정보·쿼리·조각을 떼고 저장하므로 그대로 내보내도 자격 증명이 없다. 위임 범위와 작업 트리는 저장소 키·브랜치·도구 이름만 담고 경로는 담지 않는다 |
| 작업·결정의 `source` | 그 기록의 정본 위치. `{"of_truth": "zm-baton"}`이면 이 서버가 정본이다. `{"of_truth": "git", "repo_key", "path", "commit", "external_ref"}`이면 그 저장소·파일·커밋의 Git 파일이 정본이고 이 기록은 가져온 복사본이다 |

모든 표를 한 시점의 같은 상태로 읽는다. 내보낸 사실은 감사 기록 `export.downloaded`에 종류별 개수와 함께 남는다.

### 다룰 때

세션 토큰 해시 같은 비밀값은 빠져 있다. 그 밖의 기록, 즉 작업과 결정의 글, 세션의 요약(「공유 안 함」으로 둔 것 포함), 기기 이름, 저장소 주소는 모두 들어 있다. 그러므로 내보낸 파일을 공개된 곳에 두지 않는다. 서버에 새 표가 생기면 서버 시험이 내보내기에 넣을지 뺄지를 정하게 한다.

## 실패하면

| 증상 | 원인과 대응 |
|---|---|
| `zm-baton export`가 `already exists`로 끝난다 | 다른 파일 이름을 준다 |
| `HTTP 403 … person action` | 세션 토큰을 붙여 불렀다. 사람이 하는 일이라 세션으로는 안 된다 |
| `HTTP 404` | 서버가 이 묶음보다 옛 판이다. [backup-restore-update.md](backup-restore-update.md)의 업데이트를 한다 |
| 보존 기간을 정했는데 서버가 뜨지 않는다 | `docker compose -f "$repo/deploy/compose/compose.yaml" logs server`에 `ZM_BATON_RETENTION_… must be empty … or 1 or more days`나 `zm-baton.retention`이 보이면 `.env`의 그 값을 1 이상의 일수로 고치거나 지운다 |
