# apps/web — Web 화면

1단계 설치·호환 시험([docs/stage1/compat-test-ui.md](../../docs/stage1/compat-test-ui.md))에 쓰는 React + TypeScript 화면이다.

- 작업 목록과 실행 시도의 사건을 서버에서 짧은 주기로 묻는다. 고른 것이 바뀌면 옛 응답은 버린다.
- 협업 계약 v1의 보고 상태를 보여 준다(`src/runState.ts`). 종료 여부와 종료 이유는 서버가 정한 값을 쓰고, 오류·응답 없음은 사건과 마지막 사건 시각에서 계산한다. 「입력 필요」와 「첫 응답 기한」은 근거가 없어 판정하지 않고, 화면의 보고 상태 아래에 그렇다고 보여 준다(`src/RunStatusLine.tsx`).
- 사건 내용은 실행기 출력이므로 글자로만 보여 준다.

```sh
npm ci
npm test          # 단위 시험(Vitest)
npm run build     # 타입 검사 뒤 dist/에 빌드
npm run dev       # http://127.0.0.1:15173, /api는 http://127.0.0.1:18081로 넘긴다
```

서버가 화면을 함께 내주게 하려면 서버를 띄울 때 `ZM_BATON_WEB_DIR=file:<이 폴더>/dist/`를 준다. `dist/`와 `node_modules/`는 Git에 넣지 않는다.
