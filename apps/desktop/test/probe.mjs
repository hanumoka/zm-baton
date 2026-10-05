// Denial test for the desktop shell (docs/stage1/compat-test-ui.md), Node built-ins only.
// An "inside" server plays the zm-baton server and serves a probe page; an "outside"
// server on another loopback address counts every request that reaches it.
//
// What it shows, and only this: for the scenarios below, no request from the shell reaches
// the outside server, the window stays on the inside server, and the permissions asked
// for are denied. It is not a proof that no traffic of any kind can leave the shell.
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

let outsideHits = [];
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
  } else if (req.url === '/redirect' || req.url === '/start-redirect') {
    res.statusCode = 302;
    res.setHeader('Location', `${outsideUrl}${req.url === '/redirect' ? '/landing' : '/start-landing'}`);
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

const failures = [];
const expect = (ok, what) => ok || failures.push(what);

// Positive control: the outside server really answers requests from this machine.
await (await fetch(`${outsideUrl}/control`)).text();
expect(outsideHits.includes('/control'), 'positive control: the outside server could not be reached at all');
outsideHits = [];

/** Starts the shell in probe mode against url and returns its exit code, check file and stderr tail. */
async function runShell(url, seconds) {
  const work = mkdtempSync(join(tmpdir(), 'zmb-probe-'));
  const checkFile = join(work, 'check.json');
  const shell = spawn(electronPath, [appDir], {
    env: {
      ...process.env,
      ZM_BATON_SERVER_URL: url,
      ZM_BATON_DESKTOP_CHECK: checkFile,
      ZM_BATON_DESKTOP_CHECK_MODE: 'probe',
      ZM_BATON_DESKTOP_CHECK_SECONDS: String(seconds),
    },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  let stderr = '';
  shell.stderr.on('data', (c) => (stderr = (stderr + c).slice(-4000)));
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
  let check = null;
  try {
    check = JSON.parse(readFileSync(checkFile, 'utf8'));
  } catch {
    // reported by the caller
  }
  rmSync(work, { recursive: true, force: true });
  return { exitCode, check, stderr };
}

// Case 1: the page tries permissions, a new window, a frame, a direct move and a redirect.
const page = await runShell(`${insideUrl}/`, 6);
expect(page.exitCode === 0, `page case: shell exit code ${page.exitCode}`);
expect(page.check !== null, 'page case: the shell wrote no check file');
expect(report !== null, 'page case: the probe page never reported');
if (report) {
  expect(report.notification === 'denied', `notification permission: ${report.notification}`);
  expect(report.geolocation === 'denied', `geolocation permission check: ${report.geolocation}`);
  expect(report.notificationsCheck === 'denied', `notifications permission check: ${report.notificationsCheck}`);
  expect(report.windowOpen === 'null', `window.open returned ${report.windowOpen}`);
}
if (page.check) {
  expect(page.check.url?.startsWith(insideUrl), `page case: window ended on ${page.check.url}`);
  const how = new Set((page.check.blocked ?? []).map((b) => b.how));
  for (const h of ['new-window', 'navigate', 'redirect']) expect(how.has(h), `page case: no ${h} was blocked`);
}
const pageHits = outsideHits;
outsideHits = [];

// Case 2: the very first load is redirected off the server.
const start = await runShell(`${insideUrl}/start-redirect`, 2);
expect(start.exitCode === 1, `first-load case: expected exit code 1 for a refused load, got ${start.exitCode}`);
expect(start.check?.outcome === 'error', `first-load case: outcome ${start.check?.outcome}`);
expect(
  (start.check?.blocked ?? []).some((b) => b.how === 'redirect' && b.url.endsWith('/start-landing')),
  'first-load case: the redirect was not blocked',
);
const startHits = outsideHits;

outside.close();
inside.close();
expect(pageHits.length === 0, `page case: requests reached the outside server: ${pageHits.join(', ')}`);
expect(startHits.length === 0, `first-load case: requests reached the outside server: ${startHits.join(', ')}`);

console.log(JSON.stringify({ page: { exitCode: page.exitCode, report, blocked: page.check?.blocked, url: page.check?.url, outsideHits: pageHits }, firstLoad: { exitCode: start.exitCode, outcome: start.check?.outcome, blocked: start.check?.blocked, outsideHits: startHits } }, null, 2));
if (failures.length > 0) {
  console.error('FAIL\n- ' + failures.join('\n- '));
  for (const [name, r] of [['page', page], ['first-load', start]]) {
    if (r.stderr.trim()) console.error(`--- ${name} shell stderr (last 4000 chars) ---\n${r.stderr}`);
  }
  process.exit(1);
}
console.log('PASS: in these scenarios the window stayed on the server, nothing reached the outside server, and permissions were denied');
