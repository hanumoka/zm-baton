# zm-baton

팀원과 AI 에이전트가 같은 업무·문서·기억을 중앙에서 공유하는 설치형 오픈소스 협업 제품이다. 이름은 작업명이다.

## 지금 상태

- 1단계 설치·호환 시험을 준비하고 있다. 실행할 수 있는 기능은 아직 없다.
- 변경은 PR로 들어온다. 검토 기준은 [docs/review-standard.md](docs/review-standard.md)에 있다.

## 구성

폴더는 1단계에서 필요한 것부터 만든다.

| 폴더 | 무엇인가 | 언어 후보 |
|---|---|---|
| `apps/server` | 중앙 업무 서비스. 작업·실행 시도·사건을 판정하고 저장한다 | Kotlin + Spring Boot |
| `apps/agent` | 로컬 연결 프로그램. 실행기를 띄우고 사건을 중앙에 보낸다 | Go |
| `deploy/compose` | 로컬 개발용 PostgreSQL | Docker Compose |
| `docs/` | 작업 계획, 검토 기록, 핵심 모듈 기록 | Markdown |

언어는 「먼저 시험할 후보」다. 설치·호환 시험 결과를 보고 확정한다.

## 개발 규칙

[AGENTS.md](AGENTS.md)를 따른다. AI 에이전트가 구현하고 소유자가 설계 확정·검토·병합 승인을 맡는다.

## 라이선스

[Apache License 2.0](LICENSE)
