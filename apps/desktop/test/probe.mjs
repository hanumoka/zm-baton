// Denial test for the desktop shell (docs/stage1/compat-test-ui.md), Node built-ins only.
// An "inside" server plays the zm-baton server and serves a probe page; an "outside"
// server on another loopback address counts every request that escapes the shell.
// The shell must keep the window on the inside server and grant no permission.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import electronPath from 'electron';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..');

function listen(server, host) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, host, () => resolve(`http://${host}:${server.address().port}`));
  });
}

const outsideHits = [];
const outside = createServer((req, res) => {
  outsideHits.push(req.url);
  res.end('outside');
});
const outsideUrl = await listen(outside, '127.0.0.2');

let report = null;
const probeJs = `(async () => {
  const OUT = ${JSON.stringify(outsideUrl)};
  const r = {};
  const settle = async (name, f) => { try { r[name] = await f(); } catch (e) { r[name] = 'threw: ' + e.name; } };
  await settle('notification', () => Notification.requestPermission());
  await settle('geolocation', async () => (await navigator.permissions.query({ name: 'geolocation' })).state);
  await settle('notificationsCheck', async () => (await navigator.permissions.query({ name: 'notifications' })).state);
  await settle('windowOpen', () => String(window.open(OUT + '/popup')));
  const frame = document.createElement('iframe');
  frame.src = OUT + '/frame';
  document.body.appendChild(frame);
  await new Promise((done) => setTimeout(done, 1500));
  await fetch('/report', { method: 'POST', body: JSON.stringify(r) });
  location.href = OUT + '/direct';
  setTimeout(() => { location.href = '/redirect'; }, 1500);
})();`;

const inside = createServer((req, res) => {
  if (req.url === '/') {
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.end('<!doctype html><title>probe</title><div id="out"></div><script src="/probe.js"></script>');
  } else if (req.url === '/probe.js') {
    res.setHeader('Content-Type', 'text/javascript');
    res.end(probeJs);
  } else if (req.url === '/redirect') {
    res.statusCode = 302;
    res.setHeader('Location', `${outsideUrl}/landing`);
    res.end();
  } else if (req.url === '/report' && req.method === 'POST') {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      report = JSON.parse(body);
      res.end('ok');
    });
  } else {
    res.statusCode = 404;
    res.end();
  }
});
const insideUrl = await listen(inside, '127.0.0.1');

const work = mkdtempSync(join(tmpdir(), 'zmb-probe-'));
const checkFile = join(work, 'check.json');
const shell = spawn(electronPath, [appDir], {
  env: {
    ...process.env,
    ZM_BATON_SERVER_URL: `${insideUrl}/`,
    ZM_BATON_DESKTOP_CHECK: checkFile,
    ZM_BATON_DESKTOP_CHECK_SECONDS: '6',
  },
  stdio: 'ignore',
});
const exitCode = await new Promise((resolve) => {
  const timer = setTimeout(() => {
    shell.kill(); // the child this test started, through its own handle
    resolve('timeout');
  }, 60_000);
  shell.on('exit', (code) => {
    clearTimeout(timer);
    resolve(code);
  });
});
outside.close();
inside.close();

let check = null;
try {
  check = JSON.parse(readFileSync(checkFile, 'utf8'));
} catch {
  // reported below
}
rmSync(work, { recursive: true, force: true });

const failures = [];
const expect = (ok, what) => ok || failures.push(what);
expect(exitCode === 0, `shell exit code ${exitCode}`);
expect(check !== null, 'the shell wrote no check file');
expect(outsideHits.length === 0, `requests reached the outside server: ${outsideHits.join(', ')}`);
expect(report !== null, 'the probe page never reported');
if (report) {
  expect(report.notification === 'denied', `notification permission: ${report.notification}`);
  expect(report.geolocation === 'denied', `geolocation permission check: ${report.geolocation}`);
  expect(report.notificationsCheck === 'denied', `notifications permission check: ${report.notificationsCheck}`);
  expect(report.windowOpen === 'null', `window.open returned ${report.windowOpen}`);
}
if (check) {
  expect(check.url?.startsWith(insideUrl), `window ended on ${check.url}`);
  const how = new Set((check.blocked ?? []).map((b) => b.how));
  for (const h of ['new-window', 'navigate', 'redirect']) expect(how.has(h), `no ${h} was blocked`);
}

console.log(JSON.stringify({ exitCode, outsideHits, report, blocked: check?.blocked, url: check?.url }, null, 2));
if (failures.length > 0) {
  console.error('FAIL\n- ' + failures.join('\n- '));
  process.exit(1);
}
console.log('PASS: the window stayed on the server and no permission was granted');
