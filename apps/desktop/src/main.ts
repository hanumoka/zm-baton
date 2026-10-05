// zm-baton desktop shell for the stage-1 compat test (docs/stage1/compat-test-ui.md).
// It shows the Web screen the server serves. The window gets no Node access, may only
// show pages from that server, and is granted no browser permission.
import { app, BrowserWindow, session } from 'electron';
import { writeFileSync } from 'node:fs';

const serverUrl = new URL(process.env.ZM_BATON_SERVER_URL ?? 'http://127.0.0.1:18081/');
// Compat test only: write what the page shows once its newest run ends, then quit.
const checkFile = process.env.ZM_BATON_DESKTOP_CHECK;
const checkSecondsRaw = Number(process.env.ZM_BATON_DESKTOP_CHECK_SECONDS ?? '120');
const checkSeconds = Number.isFinite(checkSecondsRaw) && checkSecondsRaw > 0 ? Math.min(checkSecondsRaw, 3600) : 120;

const webPreferences = {
  contextIsolation: true,
  sandbox: true,
  nodeIntegration: false,
  webSecurity: true,
} as const;

// Added to the server's responses; any policy the server sends itself still applies too.
const csp = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "connect-src 'self'",
  "img-src 'self' data:",
  "frame-src 'none'",
  "child-src 'none'",
  "form-action 'self'",
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

/** Off-server navigations stopped in this window, kept for the check file. */
const blocked: { how: string; url: string }[] = [];

type PageView = { lifecycle: string | null; status: string | null; events: number; nodeVisible: boolean; title: string };

const readPage = `(() => ({
  title: document.title,
  lifecycle: document.querySelector('[data-testid="run-lifecycle"]')?.textContent ?? null,
  status: document.querySelector('[data-testid="report-status"]')?.textContent ?? null,
  events: Number(document.querySelector('[data-testid="event-count"]')?.textContent ?? 0),
  nodeVisible: typeof require !== 'undefined' || typeof process !== 'undefined',
}))()`;

function writeCheck(file: string, result: object) {
  writeFileSync(
    file,
    JSON.stringify(
      { electron: process.versions.electron, chrome: process.versions.chrome, webPreferences, blocked, ...result },
      null,
      2,
    ),
  );
}

async function check(win: BrowserWindow, file: string): Promise<void> {
  const started = Date.now();
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
  const run = (code: string) => win.webContents.executeJavaScript(code);
  // The newest work item is first; open it the way a person would, if the page has one.
  for (let i = 0; i < 20 && !(await run(`!!document.querySelector('ul.list button')`)); i++) await sleep(500);
  await run(`document.querySelector('ul.list button')?.click(); true`);
  const statusesSeen: string[] = [];
  let view: PageView | undefined;
  while (Date.now() - started < checkSeconds * 1000) {
    view = (await run(readPage)) as PageView;
    if (view.status && statusesSeen.at(-1) !== view.status) statusesSeen.push(view.status);
    if (view.lifecycle?.startsWith('ended')) break;
    await sleep(500);
  }
  writeCheck(file, {
    url: win.webContents.getURL(),
    statusesSeen,
    final: view,
    seconds: Math.round((Date.now() - started) / 1000),
  });
}

function guardWindow(win: BrowserWindow) {
  const contents = win.webContents;
  contents.setWindowOpenHandler(({ url }) => {
    blocked.push({ how: 'new-window', url });
    return { action: 'deny' };
  });
  // Main-frame navigations, redirects (including the first load's) and frame navigations.
  contents.on('will-navigate', (event) => {
    if (!fromServer(event.url)) {
      blocked.push({ how: 'navigate', url: event.url });
      event.preventDefault();
    }
  });
  contents.on('will-redirect', (event) => {
    if (!fromServer(event.url)) {
      blocked.push({ how: 'redirect', url: event.url });
      event.preventDefault();
    }
  });
  contents.on('will-frame-navigate', (event) => {
    if (!event.isMainFrame && !fromServer(event.url)) {
      blocked.push({ how: 'frame', url: event.url });
      event.preventDefault();
    }
  });
  // Last line: if the main frame still ends up off the server, go back to the server.
  contents.on('did-navigate', (_event, url) => {
    if (!fromServer(url)) {
      blocked.push({ how: 'arrived', url });
      void contents.loadURL(serverUrl.href);
    }
  });
}

app.whenReady().then(async () => {
  const ses = session.defaultSession;
  ses.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  ses.setPermissionCheckHandler(() => false);
  ses.setDevicePermissionHandler(() => false);
  ses.webRequest.onHeadersReceived({ urls: [`${serverUrl.origin}/*`] }, (details, callback) => {
    const headers = { ...details.responseHeaders };
    const name = Object.keys(headers).find((k) => k.toLowerCase() === 'content-security-policy') ?? 'Content-Security-Policy';
    headers[name] = [...(headers[name] ?? []), csp];
    callback({ responseHeaders: headers });
  });

  // A copy: Electron writes into the object it is given.
  const win = new BrowserWindow({ width: 1200, height: 800, title: 'zm-baton', show: !checkFile, webPreferences: { ...webPreferences } });
  guardWindow(win);

  try {
    await win.loadURL(serverUrl.href);
    if (checkFile) await check(win, checkFile);
  } catch (err) {
    if (checkFile) writeCheck(checkFile, { error: String(err), url: win.webContents.getURL() });
    app.exit(1);
    return;
  }
  if (checkFile) app.quit();
});

app.on('window-all-closed', () => app.quit());
