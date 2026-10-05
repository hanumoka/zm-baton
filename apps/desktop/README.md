# apps/desktop — 데스크톱 껍데기

1단계 설치·호환 시험([docs/stage1/compat-test-ui.md](../../docs/stage1/compat-test-ui.md))에 쓰는 Electron 앱이다. 서버가 내주는 Web 화면을 창에 띄운다.

- 창에는 Node 기능을 주지 않는다(`contextIsolation`, `sandbox`, `nodeIntegration: false`).
- 서버(`ZM_BATON_SERVER_URL`, 기본 `http://127.0.0.1:18081/`) 밖으로의 이동과 새 창을 막는다. 콘텐츠 보안 정책을 붙이고 권한 요청을 거부한다.

```sh
npm ci
npm start         # 컴파일 뒤 창을 연다
npm run package   # out/zm-baton-desktop-win32-x64/ 에 Windows 실행 파일 폴더를 만든다
```

시험용 확인 모드: `ZM_BATON_DESKTOP_CHECK=<파일>`을 주면 창을 숨긴 채 가장 최근 작업을 열고, 그 실행 시도가 끝날 때까지(최대 `ZM_BATON_DESKTOP_CHECK_SECONDS`초) 화면의 상태를 읽어 파일에 쓴 뒤 끝난다. `dist/`·`out/`·`node_modules/`는 Git에 넣지 않는다.
