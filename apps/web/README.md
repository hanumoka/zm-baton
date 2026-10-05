# apps/web — Web 화면

1단계 설치·호환 시험([docs/stage1/compat-test-ui.md](../../docs/stage1/compat-test-ui.md))에 쓰는 React + TypeScript 화면이다.

- 작업 목록과 실행 시도의 사건을 서버에서 짧은 주기로 묻는다. 실행 상태는 사건에서 계산한다(`src/runState.ts`).
- 사건 내용은 실행기 출력이므로 글자로만 보여 준다.

```sh
npm ci
npm test          # 단위 시험(Vitest)
npm run build     # 타입 검사 뒤 dist/에 빌드
npm run dev       # http://127.0.0.1:15173, /api는 http://127.0.0.1:18081로 넘긴다
```

서버가 화면을 함께 내주게 하려면 서버를 띄울 때 `ZM_BATON_WEB_DIR=file:<이 폴더>/dist/`를 준다. `dist/`와 `node_modules/`는 Git에 넣지 않는다.
