# zm-baton

팀원과 AI 에이전트가 같은 업무·문서·기억을 중앙에서 공유하는 설치형 오픈소스 협업 제품이다. 이름은 작업명이다.

## 지금 상태

- 1단계 설치·호환 시험을 마쳤다. 서버, 로컬 연결 프로그램, Web 화면, 데스크톱 껍데기가 이 PC에서 실제 Claude Code와 함께 돈다. 결과는 [docs/stage1/compat-test-results.md](docs/stage1/compat-test-results.md)에 있다.
- 직접 돌려 보려면 [docs/stage1/run-locally.md](docs/stage1/run-locally.md)를 따른다. 서버는 이 PC 안(`127.0.0.1`)에서만 접속을 받는다. 사람 로그인·기기 토큰·TLS를 켜는 방법과, 이 PC 밖에서 받으려면 무엇이 있어야 서버가 뜨는지는 [docs/stage1/auth.md](docs/stage1/auth.md)에 있다.
- 1단계 첫 묶음: 터미널에서 직접 연 Claude Code 세션이 서버에 등록되고, 웹의 「나를 기다리는 것」과 「세션 현황」에 세션마다 하는 일, 같은 파일 충돌, 입력을 기다리는 세션이 보인다. 쓰는 방법은 [docs/stage1/sessions.md](docs/stage1/sessions.md)에 있다.
- 맡긴 AI 실행의 결과는 스냅샷 커밋으로 고정하고, 위험 경로에 걸린 것과 표본만 「나를 기다리는 것」으로 보내 사람이 승인·반려한다. 나머지는 「사람 미확인」으로 남는다. 쓰는 방법은 [docs/stage1/human-review.md](docs/stage1/human-review.md)에 있다.
- 작업에 질문을 남기면 원문 책임자의 「나를 기다리는 것」으로 가고, 답이 올 때까지 작업에 「질문 답 대기」가 붙는다. AI 실행이 물은 것은 답을 다음 실행의 지시에 넣어 이어 간다. 쓰는 방법은 [docs/stage1/questions.md](docs/stage1/questions.md)에 있다.
- 문서마다 정본 위치(제품 안 / Git)를 하나로 정하고, 제품 안 문서는 판을 쌓아 승인한다. 쓰는 방법은 [docs/stage1/documents.md](docs/stage1/documents.md)에 있다.
- 조직(사람·역할·팀·제품·프로젝트·저장소·환경)을 서버에 두고, 「누가 무엇을 해도 되나」를 서버의 한 판정기가 역할과 공유 범위(개인·제한·팀·조직·공개)로 정한다. 사람마다 매니저 기록(상태 카드·작업·세션·개인 메모)이 있다. 기본 모드에서는 요청한 사람이 늘 소유자이고, 로그인을 요구하는 모드에서는 로그인한 사람이다. 쓰는 방법은 [docs/stage1/org-and-permissions.md](docs/stage1/org-and-permissions.md)에 있다.
- 데이터베이스는 매일 백업되고, 손으로 백업·복원하고 백업을 먼저 뜬 뒤 업데이트하는 스크립트가 있다([docs/stage1/backup-restore-update.md](docs/stage1/backup-restore-update.md)). 감사 기록·보존 규칙·내보내기는 [docs/stage1/audit-retention-export.md](docs/stage1/audit-retention-export.md)에 있다.
- `work/preview-final`의 독립 서버 시험판은 최초 서버 관리자·구성원·계정·백그라운드 AI 실행을 연결한다. [확인·정책 선택·반영·되돌리기 안내](docs/stage1/independent-preview-guide.md)에 검증된 범위와 남은 조직 API·OS 검증을 구분했다. 최종 소유자 확인 전에는 병합·기존 운영 적용을 하지 않는다.
- 변경은 PR로 들어온다. 모든 PR에 CI가 돈다. 검토 기준은 [docs/review-standard.md](docs/review-standard.md)에 있다.

## 구성

폴더는 1단계에서 필요한 것부터 만든다.

| 폴더 | 무엇인가 | 언어 후보 |
|---|---|---|
| `apps/server` | 중앙 업무 서비스. 작업·실행 시도·사건을 판정하고 저장한다 | Kotlin + Spring Boot |
| `apps/agent` | 로컬 연결 프로그램. 실행기를 띄우고 사건을 중앙에 보낸다 | Go |
| `apps/web` | Web 화면. 작업·실행 시도·사건과 보고 상태를 보인다 | React + TypeScript |
| `apps/desktop` | 데스크톱 껍데기. 서버의 Web 화면을 창에 띄운다 | Electron |
| `deploy/compose` | 로컬 개발용 PostgreSQL | Docker Compose |
| `.github/workflows` | CI | GitHub Actions |
| `docs/` | 작업 계획, 검토 기록, 핵심 모듈 기록 | Markdown |

언어는 「먼저 시험할 후보」다. 설치·호환 시험을 마쳤고, 그 결과로 소유자가 확정한다.

## 개발 규칙

[AGENTS.md](AGENTS.md)를 따른다. AI 에이전트가 구현하고, 다른 실행기의 독립 검토를 거쳐 작성 AI가 병합한다. 소유자는 설계 확정과 판정이 갈린 PR을 맡는다.

## 라이선스

[Apache License 2.0](LICENSE)
