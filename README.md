# zm-baton

팀원과 AI 에이전트가 같은 업무·문서·기억을 중앙에서 공유하는 설치형 오픈소스 협업 제품이다. 이름은 작업명이다.

## 지금 상태

- 1단계 설치·호환 시험을 마쳤다. 서버, 로컬 연결 프로그램, Web 화면, 데스크톱 껍데기가 이 PC에서 실제 Claude Code와 함께 돈다. 결과는 [docs/stage1/compat-test-results.md](docs/stage1/compat-test-results.md)에 있다.
- 직접 돌려 보려면 [docs/stage1/run-locally.md](docs/stage1/run-locally.md)를 따른다. 인증이 아직 없어 서버는 이 PC 안(`127.0.0.1`)에서만 접속을 받는다.
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
