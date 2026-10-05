// zm-baton desktop shell for the stage-1 compat test (docs/stage1/compat-test-ui.md).
// It shows the Web screen the server serves; the window gets no Node access and may
// only show pages from that server.
import { app, BrowserWindow, session } from 'electron';
import { writeFileSync } from 'node:fs';

const serverUrl = new URL(process.env.ZM_BATON_SERVER_URL ?? 'http://127.0.0.1:18081/');
// Compat test only: write what the page shows once its newest run ends, then quit.
const checkFile = process.env.ZM_BATON_DESKTOP_CHECK;
const checkSeconds = Number(process.env.ZM_BATON_DESKTOP_CHECK_SECONDS ?? '120');

const webPreferences = {
  contextIsolation: true,
  sandbox: true,
  nodeIntegration: false,
  webSecurity: true,
} as const;

const csp = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "connect-src 'self'",
  "img-src 'self' data:",
  "object-src 'none'",
  "base-uri 'none'",
  "frame-ancestors 'none'",
].join('; ');

function fromServer(url: string): boolean {
  try {
    return new URL(url).origin === serverUrl.origin;
  } catch {
    return false;
  }
}

type PageView = { state: string | null; events: number; nodeVisible: boolean; title: string };

const readPage = `(() => ({
  title: document.title,
  state: document.querySelector('[data-testid="run-state"]')?.textContent ?? null,
  events: Number(document.querySelector('[data-testid="event-count"]')?.textContent ?? 0),
  nodeVisible: typeof require !== 'undefined' || typeof process !== 'undefined',
}))()`;

async function check(win: BrowserWindow, file: string): Promise<void> {
  const started = Date.now();
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
  // The newest work item is first; open it the way a person would.
  for (let i = 0; i < 20; i++) {
    if (await win.webContents.executeJavaScript(`!!document.querySelector('ul.list button')`)) break;
    await sleep(500);
  }
  await win.webContents.executeJavaScript(`document.querySelector('ul.list button')?.click(); true`);
  const seen: string[] = [];
  let view: PageView | undefined;
  while (Date.now() - started < checkSeconds * 1000) {
    view = (await win.webContents.executeJavaScript(readPage)) as PageView;
    if (view.state && seen.at(-1) !== view.state) seen.push(view.state);
    if (view.state?.startsWith('끝남')) break;
    await sleep(500);
  }
  writeFileSync(
    file,
    JSON.stringify(
      {
        url: win.webContents.getURL(),
        electron: process.versions.electron,
        chrome: process.versions.chrome,
        webPreferences,
        statesSeen: seen,
        final: view,
        seconds: Math.round((Date.now() - started) / 1000),
      },
      null,
      2,
    ),
  );
  app.quit();
}

app.whenReady().then(async () => {
  session.defaultSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    callback({ responseHeaders: { ...details.responseHeaders, 'Content-Security-Policy': [csp] } });
  });

  // A copy: Electron writes into the object it is given.
  const win = new BrowserWindow({ width: 1200, height: 800, title: 'zm-baton', show: !checkFile, webPreferences: { ...webPreferences } });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (event, url) => {
    if (!fromServer(url)) event.preventDefault();
  });

  try {
    await win.loadURL(serverUrl.href);
  } catch (err) {
    if (checkFile) writeFileSync(checkFile, JSON.stringify({ error: String(err) }));
    app.exit(1);
    return;
  }
  if (checkFile) await check(win, checkFile);
});

app.on('window-all-closed', () => app.quit());
